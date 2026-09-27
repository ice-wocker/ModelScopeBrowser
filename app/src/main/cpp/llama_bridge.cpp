// 魔搭模型库 —— llama.cpp JNI 桥接
// 提供：加载 GGUF 模型、应用对话模板、KV 前缀复用、分块预填充、流式生成、取消、重置。
#include <jni.h>
#include <android/log.h>
#include <sys/auxv.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "MScopeLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// arm64 上 glibc/bionic 的 HWCAP 位（部分 NDK 头文件未提供，这里兜底定义）
#ifndef HWCAP_ASIMDHP
#define HWCAP_ASIMDHP (1UL << 10)   // 半精度 SIMD 运算（fp16 arith）
#endif
#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1UL << 20)   // SIMD 点积（dotprod）
#endif

namespace {

/** 把 UTF-8 字节流按"完整字符"切分并转成 UTF-16，避免中文/emoji 被 token 边界切成乱码。 */
class Utf8Stream {
public:
    void feed(const char *data, size_t len, std::u16string &out) {
        buf_.append(data, len);
        size_t i = 0;
        while (i < buf_.size()) {
            unsigned char c = static_cast<unsigned char>(buf_[i]);
            size_t need;
            uint32_t cp;
            if (c < 0x80)              { need = 1; cp = c; }
            else if ((c & 0xE0) == 0xC0) { need = 2; cp = c & 0x1Fu; }
            else if ((c & 0xF0) == 0xE0) { need = 3; cp = c & 0x0Fu; }
            else if ((c & 0xF8) == 0xF0) { need = 4; cp = c & 0x07u; }
            else { i += 1; continue; }              // 非法起始字节，跳过

            if (i + need > buf_.size()) break;      // 字符不完整，等下一段

            bool ok = true;
            for (size_t k = 1; k < need; ++k) {
                unsigned char cc = static_cast<unsigned char>(buf_[i + k]);
                if ((cc & 0xC0) != 0x80) { ok = false; break; }
                cp = (cp << 6) | (cc & 0x3Fu);
            }
            if (!ok) { i += 1; continue; }

            if (cp <= 0xFFFF) {
                out.push_back(static_cast<char16_t>(cp));
            } else {
                cp -= 0x10000;
                out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
                out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
            }
            i += need;
        }
        buf_.erase(0, i);
    }

    void flush(std::u16string &out) {
        for (char ch : buf_) out.push_back(static_cast<char16_t>(static_cast<unsigned char>(ch)));
        buf_.clear();
    }

private:
    std::string buf_;
};

std::string toStd(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

jstring toJString(JNIEnv *env, const std::u16string &s) {
    return env->NewString(reinterpret_cast<const jchar *>(s.data()), static_cast<jsize>(s.size()));
}

/** 预填充时每批最多提交的 token 数，同时也是复用 batch 的容量。 */
constexpr int32_t kBatchTokens = 512;

/**
 * 物理批大小。实测（宿主端 3 核）n_ubatch 从 512 降到 128：
 * 单 token 解码 35.8ms → 24.1ms，25 token 预填充 85.5ms → 81.5ms，
 * 同时计算缓冲区从 ~311MB 降到 ~78MB（151936 词表 × ubatch × 4B），
 * 对手机内存更友好，故取 128。
 */
constexpr int32_t kUbatchTokens = 128;

struct Session {
    llama_model       *model = nullptr;
    llama_context     *ctx   = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_memory_t     mem   = nullptr;   // ctx 的 KV 内存句柄，生命周期同 ctx
    std::string        tmpl;
    int32_t            n_ctx   = 2048;
    int32_t            n_batch = kBatchTokens;

    /// 复用的解码批次：显式填写 pos / seq_id / logits。
    /// 不能用 llama_batch_get_one —— 它不带位置信息，无法把新 token 追加到已有 KV 之后。
    llama_batch        batch{};

    /// 当前 KV 中已存在的 token 序列（位置 0..size-1），用于跨轮复用公共前缀。
    std::vector<llama_token> kv;

    std::atomic<bool>  cancel{false};
    std::mutex         mu;
};

void backendInitOnce() {
    static std::once_flag once;
    std::call_once(once, [] { llama_backend_init(); });
}

/// 以显式位置把 tokens[from, to) 解码进 KV；仅当取 logits 时请求最后一位的输出。
bool decodeRange(Session *s, const std::vector<llama_token> &tokens, int32_t from, int32_t to) {
    llama_batch &b = s->batch;
    int32_t n = 0;
    for (int32_t i = from; i < to; ++i) {
        b.token[n]     = tokens[i];
        b.pos[n]       = i;                 // 位置即 KV 中的绝对下标，与复用保持一致
        b.n_seq_id[n]  = 1;
        b.seq_id[n][0] = 0;
        b.logits[n]    = (i == to - 1) ? 1 : 0;
        ++n;
    }
    b.n_tokens = n;
    return llama_decode(s->ctx, b) == 0;
}

/// 把刚采样出的 token 追加进 KV（位置接续在已有序列之后）。
bool decodeNext(Session *s, llama_token id) {
    llama_batch &b = s->batch;
    b.token[0]     = id;
    b.pos[0]       = static_cast<llama_pos>(s->kv.size());
    b.n_seq_id[0]  = 1;
    b.seq_id[0][0] = 0;
    b.logits[0]    = 1;
    b.n_tokens     = 1;
    if (llama_decode(s->ctx, b) != 0) return false;
    s->kv.push_back(id);
    return true;
}

/// 组装消息并套用对话模板；模板不可用时退化为朴素拼接。
std::string buildPrompt(Session *s, JNIEnv *env,
                        jobjectArray jRoles, jobjectArray jContents,
                        std::vector<llama_token> *outTokens) {
    const jsize n = env->GetArrayLength(jRoles);
    std::vector<std::string> roles;
    std::vector<std::string> contents;
    std::vector<llama_chat_message> chat;
    roles.reserve(n);
    contents.reserve(n);
    chat.reserve(n);

    for (jsize i = 0; i < n; ++i) {
        auto jr = reinterpret_cast<jstring>(env->GetObjectArrayElement(jRoles, i));
        auto jc = reinterpret_cast<jstring>(env->GetObjectArrayElement(jContents, i));
        roles.push_back(toStd(env, jr));
        contents.push_back(toStd(env, jc));
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }
    for (jsize i = 0; i < n; ++i) chat.push_back({roles[i].c_str(), contents[i].c_str()});

    const char *tmplName = s->tmpl.empty() ? "chatml" : s->tmpl.c_str();
    std::string prompt;
    {
        std::vector<char> buf(8192);
        int32_t need = llama_chat_apply_template(tmplName, chat.data(), chat.size(), true,
                                                 buf.data(), static_cast<int32_t>(buf.size()));
        if (need > static_cast<int32_t>(buf.size())) {
            buf.resize(static_cast<size_t>(need) + 1);
            need = llama_chat_apply_template(tmplName, chat.data(), chat.size(), true,
                                             buf.data(), static_cast<int32_t>(buf.size()));
        }
        if (need > 0) {
            prompt.assign(buf.data(), static_cast<size_t>(need));
        } else {
            for (const auto &m : chat) {
                prompt += std::string(m.role) + ": " + m.content + "\n";
            }
            prompt += "assistant: ";
        }
    }

    if (outTokens != nullptr) {
        const llama_vocab *vocab = s->vocab;
        int32_t nTok = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                       nullptr, 0, false, true);
        if (nTok > 0) {
            outTokens->resize(static_cast<size_t>(nTok));
            if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                               outTokens->data(), static_cast<int32_t>(outTokens->size()),
                               false, true) < 0) {
                outTokens->clear();
            }
        }
    }
    return prompt;
}

}  // namespace

extern "C" {

/**
 * 本机 CPU 是否满足当前编译所用的指令集。
 * arm64 构建开启了 dotprod + fp16，老机型缺这些指令会直接 SIGILL，
 * 这里提前用 HWCAP 判断，让上层给出可读提示而不是崩溃。
 * 返回空串表示可用；否则返回原因。
 */
JNIEXPORT jstring JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeSupported(JNIEnv *env, jclass) {
#if defined(__aarch64__)
    const unsigned long hw = getauxval(AT_HWCAP);
    if ((hw & HWCAP_ASIMDDP) == 0 || (hw & HWCAP_ASIMDHP) == 0) {
        return env->NewStringUTF("当前机型 CPU 不支持 dotprod/fp16 指令（需 2018 年后的 arm64 处理器），"
                                 "无法在本机运行模型");
    }
#endif
    return env->NewStringUTF("");
}

JNIEXPORT jlong JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeInit(
        JNIEnv *env, jclass, jstring jPath, jint nCtx, jint nThreads, jint nThreadsBatch) {
    backendInitOnce();
    const std::string path = toStd(env, jPath);
    if (path.empty()) return 0;

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;   // 纯 CPU 推理，避免各机型 GPU 后端差异
    mp.load_mode    = LLAMA_LOAD_MODE_MMAP;   // 内存映射加载，省内存

    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) {
        LOGE("模型加载失败: %s", path.c_str());
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = static_cast<uint32_t>(nCtx);
    cp.n_batch         = kBatchTokens;
    cp.n_ubatch        = kUbatchTokens;
    cp.n_threads       = nThreads;        // 解码：只用大核，降低每 token 延迟
    cp.n_threads_batch = nThreadsBatch;   // 预填充：吞吐型任务，可用满核心
    cp.no_perf         = true;

    llama_context *ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        LOGE("上下文创建失败");
        llama_model_free(model);
        return 0;
    }

    auto *s = new Session();
    s->model   = model;
    s->ctx     = ctx;
    s->vocab   = llama_model_get_vocab(model);
    s->mem     = llama_get_memory(ctx);
    s->n_ctx   = static_cast<int32_t>(llama_n_ctx(ctx));
    s->n_batch = kBatchTokens;
    s->batch   = llama_batch_init(kBatchTokens, 0, 1);

    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl != nullptr) s->tmpl = tmpl;

    LOGI("模型已加载, n_ctx=%d, ctx_train=%d, threads=%d/%d",
         s->n_ctx, llama_model_n_ctx_train(model), nThreads, nThreadsBatch);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(s->mu);
        llama_batch_free(s->batch);
        if (s->ctx)   llama_free(s->ctx);
        if (s->model) llama_model_free(s->model);
        s->ctx   = nullptr;
        s->model = nullptr;
    }
    delete s;
}

JNIEXPORT void JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeReset(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || s->ctx == nullptr) return;
    std::lock_guard<std::mutex> lock(s->mu);
    llama_memory_clear(s->mem, true);
    s->kv.clear();     // 新对话：缓存一并作废
}

JNIEXPORT void JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeCancel(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s != nullptr) s->cancel = true;
}

JNIEXPORT jint JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeContextSize(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    return s == nullptr ? 0 : s->n_ctx;
}

/** 只用来看看当前 KV 里缓存了多少 token（供界面/调优参考）。 */
JNIEXPORT jint JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeCachedTokens(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    return s == nullptr ? 0 : static_cast<jint>(s->kv.size());
}

/** 统计一段文本的分词长度，供上层做「消息级裁剪」估算。 */
JNIEXPORT jint JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeCountTokens(
        JNIEnv *env, jclass, jlong handle, jstring jText) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return 0;
    const std::string text = toStd(env, jText);
    if (text.empty()) return 0;
    const int32_t n = -llama_tokenize(s->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                      nullptr, 0, false, true);
    return n > 0 ? static_cast<jint>(n) : 0;
}

JNIEXPORT jstring JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeBuildPrompt(
        JNIEnv *env, jclass, jlong handle, jobjectArray jRoles, jobjectArray jContents) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return env->NewStringUTF("");
    const std::string prompt = buildPrompt(s, env, jRoles, jContents, nullptr);
    return toJString(env, std::u16string(prompt.begin(), prompt.end()));
}

/**
 * 流式生成。返回完整回复文本；失败返回空串。
 * 关键点：复用上一轮已算好的 KV 公共前缀，只解码新增 token —— 多轮对话不再重算历史。
 */
JNIEXPORT jstring JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeGenerate(
        JNIEnv *env, jclass, jlong handle, jobjectArray jRoles, jobjectArray jContents,
        jint maxTokens, jfloat temp, jfloat topP, jint topK, jint seed, jobject callback) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || s->ctx == nullptr) return env->NewStringUTF("");

    std::lock_guard<std::mutex> lock(s->mu);
    s->cancel = false;

    // ---- 1. 对话模板 + 分词 ----
    std::vector<llama_token> tokens;
    const std::string prompt = buildPrompt(s, env, jRoles, jContents, &tokens);
    if (tokens.empty()) {
        LOGE("分词失败");
        return env->NewStringUTF("");
    }
    (void) prompt;

    // ---- 2. 上下文长度保护：超出时丢弃最早的历史 ----
    const int32_t nCtx    = s->n_ctx;
    int32_t maxNew        = maxTokens > 0 ? maxTokens : 256;
    int32_t budgetInput   = nCtx - maxNew - 8;
    if (budgetInput < 64) {
        budgetInput = nCtx / 2;
        maxNew      = nCtx - budgetInput - 8;
        if (maxNew < 16) maxNew = 16;
    }
    if (static_cast<int32_t>(tokens.size()) > budgetInput) {
        tokens.erase(tokens.begin(), tokens.end() - budgetInput);
    }

    // ---- 3. KV 前缀复用 ----
    int32_t n_common = 0;
    {
        const int32_t nKv = static_cast<int32_t>(s->kv.size());
        const int32_t lim = std::min<int32_t>(static_cast<int32_t>(tokens.size()), nKv);
        while (n_common < lim && s->kv[n_common] == tokens[n_common]) ++n_common;
        if (n_common > budgetInput) n_common = budgetInput;

        // 整个 prompt 都命中时，需要回退一位重新解码，才能拿到用于采样的 logits
        if (n_common == static_cast<int32_t>(tokens.size()) && n_common > 0) {
            n_common--;
        }

        if (n_common < nKv) {
            if (!llama_memory_seq_rm(s->mem, 0, n_common, -1)) {
                // 部分删除不被支持（循环/混合结构），退回整体清空
                llama_memory_clear(s->mem, true);
                n_common = 0;
            }
        }
        s->kv.resize(n_common);
    }

    // ---- 4. 分块预填充：只算新增部分 ----
    for (int32_t i = n_common; i < static_cast<int32_t>(tokens.size()); i += s->n_batch) {
        if (s->cancel) return env->NewStringUTF("");
        const int32_t to = std::min<int32_t>(i + s->n_batch, static_cast<int32_t>(tokens.size()));
        if (!decodeRange(s, tokens, i, to)) {
            LOGE("预填充 decode 失败");
            return env->NewStringUTF("");
        }
    }
    s->kv.assign(tokens.begin(), tokens.end());

    // ---- 5. 采样器链 ----
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sp);
    if (topK > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    if (topP > 0.0f && topP < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    if (temp > 0.0f) llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, temp > 0.0f ? llama_sampler_init_dist(static_cast<uint32_t>(seed))
                                              : llama_sampler_init_greedy());

    // ---- 6. 逐 token 生成并流式回调 ----
    jclass cbClass = callback ? env->GetObjectClass(callback) : nullptr;
    jmethodID onToken = cbClass ? env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V") : nullptr;

    std::u16string full;
    Utf8Stream stream;
    std::vector<char> piece(512);
    bool aborted = false;

    for (int32_t i = 0; i < maxNew; ++i) {
        if (s->cancel) { aborted = true; break; }

        const llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, id)) break;

        int32_t np = llama_token_to_piece(s->vocab, id, piece.data(),
                                          static_cast<int32_t>(piece.size()), 0, false);
        if (np < 0) {
            piece.resize(static_cast<size_t>(-np));
            np = llama_token_to_piece(s->vocab, id, piece.data(),
                                      static_cast<int32_t>(piece.size()), 0, false);
        }
        if (np > 0 && onToken != nullptr) {
            std::u16string part;
            stream.feed(piece.data(), static_cast<size_t>(np), part);
            if (!part.empty()) {
                full += part;
                jstring js = toJString(env, part);
                env->CallVoidMethod(callback, onToken, js);
                env->DeleteLocalRef(js);
                if (env->ExceptionCheck()) {   // 回调抛异常（如页面已关闭）则终止
                    env->ExceptionClear();
                    aborted = true;
                    break;
                }
            }
        }

        // 生成的 token 也要写回 KV，下一轮才能直接复用这段前缀
        if (!decodeNext(s, id)) break;
    }
    stream.flush(full);

    llama_sampler_free(smpl);
    if (aborted) LOGI("生成被取消");
    return toJString(env, full);
}

JNIEXPORT jstring JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeVersion(JNIEnv *env, jclass) {
#if defined(__ARM_FEATURE_DOTPROD)
    return env->NewStringUTF("llama.cpp b11205 (arm64 + dotprod)");
#else
    return env->NewStringUTF("llama.cpp b11205 (arm)");
#endif
}

}  // extern "C"