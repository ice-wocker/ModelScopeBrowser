// 魔搭模型库 —— llama.cpp JNI 桥接
// 提供：加载 GGUF 模型、应用对话模板、KV 前缀复用、分块预填充、流式生成、取消、重置。
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#define TAG "MScopeLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

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

/**
 * 把一段「完整 UTF-8 字节序列」整体转成 UTF-16。
 * 原生层返回给 Java 的文本必须走这里：直接按字节加宽（std::u16string(s.begin(), s.end())）
 * 会把中文/emoji 的每个字节都变成独立码元，中文会变成一串乱码，BMP 外的字符还会被 JNI 拒绝。
 */
std::u16string toU16(const std::string &s) {
    std::u16string out;
    out.reserve(s.size());
    Utf8Stream stream;
    stream.feed(s.data(), s.size(), out);
    stream.flush(out);
    return out;
}

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
    /// 只记录「prompt 部分」：生成的 token 也写进了 KV，但它们不属于任何一轮的 prompt，
    /// 下一轮的前缀匹配必须看不见它们，否则命中位置会整体错位（见 nativeGenerate 的说明）。
    std::vector<llama_token> kv;

    std::atomic<bool>  cancel{false};
    std::mutex         mu;
    /// 引用计数：nativeFree 只把会话从 g_sessions 摘掉并 release，最后一个持有者才真正销毁。
    /// 这样「生成线程正在用」时另一个线程调 nativeFree 也不会 use-after-free。
    std::atomic<int>   refs{1};
};

/// 存活会话表。nativeFree 会 delete 会话，而 nativeCancel 可能从别的线程并发调用；
/// 没有这张表就可能「释放后仍写 cancel」→ use-after-free。加锁顺序统一为 g_mu → s->mu。
std::mutex              g_mu;
std::unordered_set<Session *> g_sessions;

/// 释放一次引用；归零时销毁会话。所有持有者都必须走这里，避免二次释放。
void releaseSession(Session *s) {
    if (s == nullptr) return;
    if (s->refs.fetch_sub(1) != 1) return;
    {
        std::lock_guard<std::mutex> lock(s->mu);
        if (s->batch.token != nullptr) llama_batch_free(s->batch);
        if (s->ctx != nullptr) llama_free(s->ctx);
        if (s->model != nullptr) llama_model_free(s->model);
        s->batch.token = nullptr;
        s->ctx = nullptr;
        s->model = nullptr;
    }
    delete s;
}

/// 应用私有 native 库目录。多档指令集后端是独立 .so，必须从这里显式加载。
std::string g_backend_dir;

/// 最近一次加载失败的原因，供上层展示（否则所有失败都只能显示一句笼统提示）。
std::string g_last_error;

/// 取出句柄对应的会话并加锁（同时持有一份引用，调用方必须调 releaseSession）；
/// 句柄已失效时返回 nullptr（此时不会解引用，也不会加引用）。
///
/// g_mu 一直持有到 s->mu 拿到为止：否则「取到指针 → 释放 g_mu → 抢 s->mu」的间隙里，
/// 另一个线程可能已经 nativeFree 掉这个会话，再去 lock 就是操作已释放内存。
Session *acquire(jlong handle, std::unique_lock<std::mutex> &lock) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return nullptr;
    std::lock_guard<std::mutex> guard(g_mu);
    if (g_sessions.count(s) == 0) return nullptr;
    s->refs.fetch_add(1);
    lock = std::unique_lock<std::mutex>(s->mu);
    return s;
}

void backendInitOnce() {
    static std::once_flag once;
    std::call_once(once, [] {
        // arm64 上是多档指令集：各档 CPU 后端以 libggml-cpu-*.so 独立存在，
        // 必须先把它们从应用的 native 库目录加载进来（ggml 内部会按 HWCAP 打分，
        // 只装载分数最高的那一档）。否则 ggml 默认只在可执行文件目录找，Android 上必然落空。
        if (!g_backend_dir.empty()) {
            ggml_backend_load_all_from_path(g_backend_dir.c_str());
            LOGI("后端加载目录: %s, 已注册 %zu 个后端",
                 g_backend_dir.c_str(), ggml_backend_reg_count());
        }
        llama_backend_init();
    });
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
 * 本机是否可运行。改用多档指令集后，arm64 的基线是 armv8-a，
 * 任何 arm64 机型都能加载（具体走哪一档由 ggml 在运行时按 HWCAP 选），
 * 因此这里不再做指令集门槛校验，保留接口返回空串。
 */
JNIEXPORT jstring JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeSupported(JNIEnv *env, jclass) {
    return env->NewStringUTF("");
}

/** 最近一次 nativeInit 失败的原因；无失败时为空串。 */
JNIEXPORT jstring JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeLastError(JNIEnv *env, jclass) {
    return env->NewStringUTF(g_last_error.c_str());
}

/** 当前实际使用的 CPU 后端描述（如 CPU 型号），用于界面展示。 */
JNIEXPORT jstring JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeBackendInfo(JNIEnv *env, jclass) {
    backendInitOnce();
    ggml_backend_dev_t dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (dev == nullptr) return env->NewStringUTF("");
    const char *desc = ggml_backend_dev_description(dev);
    return env->NewStringUTF(desc == nullptr ? "" : desc);
}

JNIEXPORT jlong JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeInit(
        JNIEnv *env, jclass, jstring jPath, jint nCtx, jint nThreads, jint nThreadsBatch,
        jstring jBackendDir) {
    g_backend_dir = toStd(env, jBackendDir);
    backendInitOnce();
    g_last_error.clear();
    const size_t nreg = ggml_backend_reg_count();
    LOGI("后端注册数=%zu, native 库目录=%s", nreg, g_backend_dir.c_str());

    const std::string path = toStd(env, jPath);
    if (path.empty()) {
        g_last_error = "模型路径为空";
        return 0;
    }

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;   // 纯 CPU 推理，避免各机型 GPU 后端差异
    mp.load_mode    = LLAMA_LOAD_MODE_MMAP;   // 内存映射加载，省内存

    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) {
        // 区分「后端没起来」与「文件本身有问题」，便于用户判断是装包问题还是模型问题
        g_last_error = nreg == 0
                ? "本地推理后端未注册（native 库没有释放到 nativeLibraryDir，或该机型指令集不受支持）"
                : "模型文件无法加载（格式不受支持，或文件不完整 / 下载未完成）";
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

    // KV 量化(q8_0) 与 FlashAttention 能显著减少注意力的内存读写，但并非所有模型/后端都支持。
    // 逐个降级尝试，避免因其中一项不被支持就让整个加载失败。
    struct KvProfile {
        ggml_type             k;
        ggml_type             v;
        llama_flash_attn_type fa;
        const char           *name;
    };
    const KvProfile profiles[] = {
        { GGML_TYPE_Q8_0, GGML_TYPE_Q8_0, LLAMA_FLASH_ATTN_TYPE_AUTO,     "q8_0 KV + FA(auto)" },
        { GGML_TYPE_Q8_0, GGML_TYPE_Q8_0, LLAMA_FLASH_ATTN_TYPE_DISABLED, "q8_0 KV + FA(off)"  },
        { GGML_TYPE_F16,  GGML_TYPE_F16,  LLAMA_FLASH_ATTN_TYPE_DISABLED, "f16 KV + FA(off)"   },
    };

    llama_context *ctx = nullptr;
    const char *usedProfile = "";
    for (const KvProfile &p : profiles) {
        cp.type_k          = p.k;
        cp.type_v          = p.v;
        cp.flash_attn_type = p.fa;
        ctx = llama_init_from_model(model, cp);
        if (ctx != nullptr) {
            usedProfile = p.name;
            break;
        }
        LOGE("上下文创建失败，降级重试：%s", p.name);
    }
    if (ctx == nullptr) {
        g_last_error = "上下文创建失败（已依次尝试 KV 量化与 FlashAttention 的组合，通常是可用内存不足）";
        llama_model_free(model);
        return 0;
    }
    LOGI("上下文参数: %s", usedProfile);

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

    {
        std::lock_guard<std::mutex> lock(g_mu);
        g_sessions.insert(s);
    }

    LOGI("模型已加载, n_ctx=%d, ctx_train=%d, threads=%d/%d",
         s->n_ctx, llama_model_n_ctx_train(model), nThreads, nThreadsBatch);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(g_mu);
        if (g_sessions.erase(s) == 0) return;   // 已释放或非法句柄，避免二次释放
    }
    // 不在这里销毁：可能还有其它线程正持着引用（例如正在 nativeGenerate 里）。
    // 先摇取消标志让生成尽快退出，再放掉自己那一份引用。
    s->cancel = true;
    releaseSession(s);
}

JNIEXPORT void JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeReset(JNIEnv *, jclass, jlong handle) {
    std::unique_lock<std::mutex> lock;
    Session *s = acquire(handle, lock);
    if (s == nullptr) return;
    llama_memory_clear(s->mem, true);
    s->kv.clear();     // 新对话：缓存一并作废
}

JNIEXPORT void JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeCancel(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    // 只置原子标志即可，无需 s->mu；但要先确认会话仍存活，否则会写已释放内存。
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_sessions.count(s) != 0) s->cancel = true;
}

JNIEXPORT jint JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeContextSize(JNIEnv *, jclass, jlong handle) {
    std::unique_lock<std::mutex> lock;
    Session *s = acquire(handle, lock);
    if (s == nullptr) return 0;
    const jint n = s->n_ctx;
    lock.unlock();
    releaseSession(s);
    return n;
}

/** 只用来看看当前 KV 里缓存了多少 token（供界面/调优参考）。 */
JNIEXPORT jint JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeCachedTokens(JNIEnv *, jclass, jlong handle) {
    std::unique_lock<std::mutex> lock;
    Session *s = acquire(handle, lock);
    if (s == nullptr) return 0;
    const jint n = static_cast<jint>(s->kv.size());
    lock.unlock();
    releaseSession(s);
    return n;
}

/** 统计一段文本的分词长度，供上层做「消息级裁剪」估算。 */
JNIEXPORT jint JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeCountTokens(
        JNIEnv *env, jclass, jlong handle, jstring jText) {
    std::unique_lock<std::mutex> lock;
    Session *s = acquire(handle, lock);
    if (s == nullptr) return 0;
    const std::string text = toStd(env, jText);
    int32_t n = 0;
    if (!text.empty()) {
        n = -llama_tokenize(s->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                            nullptr, 0, false, true);
    }
    lock.unlock();
    releaseSession(s);
    return n > 0 ? static_cast<jint>(n) : 0;
}

JNIEXPORT jstring JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeBuildPrompt(
        JNIEnv *env, jclass, jlong handle, jobjectArray jRoles, jobjectArray jContents) {
    std::unique_lock<std::mutex> lock;
    Session *s = acquire(handle, lock);
    if (s == nullptr) return env->NewStringUTF("");
    const std::string prompt = buildPrompt(s, env, jRoles, jContents, nullptr);
    lock.unlock();
    releaseSession(s);
    return toJString(env, toU16(prompt));
}

/**
 * 流式生成。返回完整回复文本；失败返回空串。
 * 关键点：复用上一轮已算好的 KV 公共前缀，只解码新增 token —— 多轮对话不再重算历史。
 */
JNIEXPORT jstring JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeGenerate(
        JNIEnv *env, jclass, jlong handle, jobjectArray jRoles, jobjectArray jContents,
        jint maxTokens, jfloat temp, jfloat topP, jint topK, jint seed,
        jint repeatLastN, jfloat repeatPenalty, jobject callback) {
    std::unique_lock<std::mutex> lock;
    Session *s = acquire(handle, lock);
    if (s == nullptr || s->ctx == nullptr) {
        if (s != nullptr) {
            lock.unlock();
            releaseSession(s);
        }
        return env->NewStringUTF("");
    }
    // 统一出口：无论从哪条分支返回，都要放掉本函数持有的引用（会话可能已被 nativeFree 摘掉）。
    auto finish = [&](const std::u16string &text) -> jstring {
        lock.unlock();
        releaseSession(s);
        return toJString(env, text);
    };
    s->cancel = false;

    // ---- 1. 对话模板 + 分词 ----
    std::vector<llama_token> tokens;
    const std::string prompt = buildPrompt(s, env, jRoles, jContents, &tokens);
    if (tokens.empty()) {
        LOGE("分词失败");
        return finish(u"");
    }
    (void) prompt;

    // ---- 2. 上下文长度保护 ----
    const int32_t nCtx  = s->n_ctx;
    int32_t maxNew      = maxTokens > 0 ? maxTokens : 256;
    int32_t budgetInput = nCtx - maxNew - 8;
    if (budgetInput < 64) {
        budgetInput = nCtx / 2;
        maxNew      = nCtx - budgetInput - 8;
        if (maxNew < 16) maxNew = 16;
    }

    // ---- 3. KV 前缀复用 ----
    // 不变式：s->kv 记录的是「上一轮 prompt 的 token」，且 s->kv[i] 真的位于位置 i。
    //
    // 上一轮生成结束后 KV 里还多留着那一轮的输出 token，它们不在 s->kv 里。这正是关键：
    // 前缀只跟「历史 prompt」比，命中长度永远不会超过历史 prompt 的长度，而历史 prompt 是
    // 新 prompt 的真前缀（新 prompt 只是在其后追加了一条 assistant 消息），所以
    // [n_common, 新序列末尾) 这段位置上的旧 KV 要么不存在、要么一定会被覆盖 —— 不会错位。
    //
    // 反过来，如果把生成的 token 也并进 s->kv 一起匹配，命中位置就会落进上一轮的输出里：
    //   · 新 prompt 的 [i] 与上一轮输出 [i] 恰好相同（很长），于是「命中」了本不该命中的区间；
    //   · 随后 llama_memory_seq_rm 删掉的是别的区间；
    //   · 留下的 KV 位置与 s->kv 记录对不上，decode 的位置与真实内容错位。
    // 表现形式就是同一个问题重复调用同一个工具、或者答非所问，且越是「历史整段保留」的
    // 模板（带 function-call 的模型）越容易触发。
    int32_t n_common = 0;
    {
        const int32_t nKv = static_cast<int32_t>(s->kv.size());
        const int32_t lim = std::min<int32_t>(static_cast<int32_t>(tokens.size()), nKv);
        while (n_common < lim && s->kv[n_common] == tokens[n_common]) ++n_common;

        // 整份 prompt 都命中时，必须回退一位重新解码，否则拿不到用于采样的 logits
        if (n_common >= static_cast<int32_t>(tokens.size()) && n_common > 0) n_common--;

        // 命中之后那段 KV 里可能还压着上一轮的输出 token，与本次内容不同：一律删掉重算。
        // 这既保证下一个 token 的 logits 正确，也让 s->kv 与真实 KV 内容保持一一对应。
        if (n_common < nKv) {
            if (!llama_memory_seq_rm(s->mem, 0, n_common, -1)) {
                // 部分删除不被支持（循环/混合结构），退回整体清空
                llama_memory_clear(s->mem, true);
                n_common = 0;
            }
        }
        s->kv.assign(tokens.begin(), tokens.begin() + n_common);
    }

    // 超长历史：native 层只保留末尾预算。与上层「按消息裁剪」互为兜底。
    if (static_cast<int32_t>(tokens.size()) > budgetInput) {
        tokens.erase(tokens.begin(), tokens.end() - budgetInput);
        if (n_common > 0) {
            // 截断后位置 0 对应的 token 变了，前缀不再成立；把这段 KV 一起作废重算。
            llama_memory_clear(s->mem, true);
            s->kv.clear();
            n_common = 0;
        }
    }

    // ---- 4. 分块预填充：只算新增部分 ----
    for (int32_t i = n_common; i < static_cast<int32_t>(tokens.size()); i += s->n_batch) {
        if (s->cancel) return finish(u"");
        const int32_t to = std::min<int32_t>(i + s->n_batch, static_cast<int32_t>(tokens.size()));
        if (!decodeRange(s, tokens, i, to)) {
            LOGE("预填充 decode 失败");
            return finish(u"");
        }
    }
    // 兜底：预填充路径若自己动过 KV（分块删除/清空），把记录重新对齐一次。
    // 这里只记录 prompt 部分 —— 生成的 token 由 decodeNext 追加进 KV，但不进 s->kv。
    s->kv.assign(tokens.begin(), tokens.end());

    // ---- 5. 采样器链 ----
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sp);
    // 顺序：重复惩罚必须排在截断之前。它要看到的是「模型原来的分布」，
    // 放到 top-k/top-p 之后就只能惩罚已经被截断的候选，几乎不起作用。
    if (repeatLastN > 0 && repeatPenalty > 0.0f && std::fabs(repeatPenalty - 1.0f) > 1e-6f) {
        // b11205 起签名多了一个 n_vocab：新的 penalties 实现不再自己查词表，
        // 得把模型词表大小传进去（内部用它分配计数数组）。
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
                llama_vocab_n_tokens(s->vocab), repeatLastN, repeatPenalty, 0.0f, 0.0f));
    }
    if (topK > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    if (topP > 0.0f && topP < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    if (temp > 0.0f) llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, temp > 0.0f ? llama_sampler_init_dist(static_cast<uint32_t>(seed))
                                              : llama_sampler_init_greedy());

    // ---- 6. 逐 token 生成并流式回调 ----
    std::u16string full;
    Utf8Stream stream;
    std::vector<char> piece(512);
    bool aborted = false;

    jclass cbClass = nullptr;
    jmethodID onToken = nullptr;
    if (callback != nullptr) {
        cbClass = env->GetObjectClass(callback);          // GetObjectClass 返回局部引用，必须手工释放
        onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
        if (env->ExceptionCheck()) {                       // 方法找不到会挂起异常，先清掉再继续
            env->ExceptionClear();
            onToken = nullptr;
        }
    }

    for (int32_t i = 0; i < maxNew; ++i) {
        if (s->cancel) { aborted = true; break; }

        const llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, id)) break;

        // special=true：MiniCPM5 这类模型把 <function>/<param> 工具标签做成了「特殊 token」，
        // llama_token_to_piece 传 false 时会直接返回空串把它们丢掉，工具调用就退化成
        // 残缺的 `name="web_search">` 文本，上层再也解析不出来。EOG 已在上面拦掉，这里放开无害。
        constexpr bool kRenderSpecial = true;
        int32_t np = llama_token_to_piece(s->vocab, id, piece.data(),
                                          static_cast<int32_t>(piece.size()), 0, kRenderSpecial);
        if (np < 0) {
            piece.resize(static_cast<size_t>(-np));
            np = llama_token_to_piece(s->vocab, id, piece.data(),
                                      static_cast<int32_t>(piece.size()), 0, kRenderSpecial);
        }
        if (np > 0 && onToken != nullptr) {
            std::u16string part;
            stream.feed(piece.data(), static_cast<size_t>(np), part);
            if (!part.empty()) {
                full += part;
                jstring js = toJString(env, part);
                env->CallVoidMethod(callback, onToken, js);
                env->DeleteLocalRef(js);
                if (env->ExceptionCheck()) {   // 回调抛异常（如页面已关闭 / 被 R8 改名）则终止
                    // 每个 token 都检查：否则异常会一直挂起，后续 GetMethodID/CallVoidMethod
                    // 全被 JNI 直接拒绝，生成看起来「卡住」直到 maxTokens。
                    env->ExceptionClear();
                    LOGE("onToken 回调抛异常，已终止生成");
                    aborted = true;
                    break;
                }
            }
        }

        // 生成的 token 也要写回 KV，下一轮才能直接复用这段前缀
        if (!decodeNext(s, id)) break;
    }
    stream.flush(full);

    llama_sampler_free(smpl);      // 注意：上面所有提前 return 都已改用 finish()，不会漏掉释放
    if (cbClass != nullptr) env->DeleteLocalRef(cbClass);
    if (aborted) LOGI("生成被取消");
    return finish(full);
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