// 魔搭模型库 —— llama.cpp JNI 桥接
// 提供：加载 GGUF 模型、应用对话模板、流式生成、取消、重置上下文。
#include <jni.h>
#include <android/log.h>

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

struct Session {
    llama_model       *model = nullptr;
    llama_context     *ctx   = nullptr;
    const llama_vocab *vocab = nullptr;
    std::string        tmpl;
    int32_t            n_ctx = 2048;
    std::atomic<bool>  cancel{false};
    std::mutex         mu;
};

void backendInitOnce() {
    static std::once_flag once;
    std::call_once(once, [] { llama_backend_init(); });
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeInit(
        JNIEnv *env, jclass, jstring jPath, jint nCtx, jint nThreads) {
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
    cp.n_batch         = 512;
    cp.n_ubatch        = 512;
    cp.n_threads       = nThreads;
    cp.n_threads_batch = nThreads;
    cp.no_perf         = true;

    llama_context *ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        LOGE("上下文创建失败");
        llama_model_free(model);
        return 0;
    }

    auto *s = new Session();
    s->model = model;
    s->ctx   = ctx;
    s->vocab = llama_model_get_vocab(model);
    s->n_ctx = static_cast<int32_t>(llama_n_ctx(ctx));

    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl != nullptr) s->tmpl = tmpl;

    LOGI("模型已加载, n_ctx=%d, ctx_train=%d", s->n_ctx, llama_model_n_ctx_train(model));
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(s->mu);
        if (s->ctx)   llama_free(s->ctx);
        if (s->model) llama_model_free(s->model);
        s->ctx = nullptr;
        s->model = nullptr;
    }
    delete s;
}

JNIEXPORT void JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeReset(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || s->ctx == nullptr) return;
    std::lock_guard<std::mutex> lock(s->mu);
    llama_memory_clear(llama_get_memory(s->ctx), true);
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

JNIEXPORT jstring JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeBuildPrompt(
        JNIEnv *env, jclass, jlong handle, jobjectArray jRoles, jobjectArray jContents) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return env->NewStringUTF("");

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
    for (jsize i = 0; i < n; ++i) {
        chat.push_back({roles[i].c_str(), contents[i].c_str()});
    }

    const char *tmplName = s->tmpl.empty() ? "chatml" : s->tmpl.c_str();
    std::string prompt;
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
        // 模板不可用时退化为朴素拼接
        for (const auto &m : chat) {
            prompt += m.role;
            prompt += ": ";
            prompt += m.content;
            prompt += "\n";
        }
        prompt += "assistant: ";
    }
    return toJString(env, std::u16string(prompt.begin(), prompt.end()));
}

/**
 * 流式生成。返回完整回复文本；失败返回空串。
 * maxTokens 为最大新生成 token 数，temp<=0 时退化为贪婪解码。
 */
JNIEXPORT jstring JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeGenerate(
        JNIEnv *env, jclass, jlong handle, jobjectArray jRoles, jobjectArray jContents,
        jint maxTokens, jfloat temp, jfloat topP, jint topK, jint seed, jobject callback) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || s->ctx == nullptr) return env->NewStringUTF("");

    std::lock_guard<std::mutex> lock(s->mu);
    s->cancel = false;

    // ---- 1. 组装消息并套用对话模板 ----
    const jsize nMsg = env->GetArrayLength(jRoles);
    std::vector<std::string> roles;
    std::vector<std::string> contents;
    std::vector<llama_chat_message> chat;
    roles.reserve(nMsg);
    contents.reserve(nMsg);
    chat.reserve(nMsg);
    for (jsize i = 0; i < nMsg; ++i) {
        auto jr = reinterpret_cast<jstring>(env->GetObjectArrayElement(jRoles, i));
        auto jc = reinterpret_cast<jstring>(env->GetObjectArrayElement(jContents, i));
        roles.push_back(toStd(env, jr));
        contents.push_back(toStd(env, jc));
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }
    for (jsize i = 0; i < nMsg; ++i) chat.push_back({roles[i].c_str(), contents[i].c_str()});

    std::string prompt;
    {
        const char *tmplName = s->tmpl.empty() ? "chatml" : s->tmpl.c_str();
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

    // ---- 2. 分词 ----
    const llama_vocab *vocab = s->vocab;
    int32_t nTok = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                   nullptr, 0, false, true);
    if (nTok <= 0) {
        LOGE("分词失败");
        return env->NewStringUTF("");
    }
    std::vector<llama_token> tokens(static_cast<size_t>(nTok));
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                       tokens.data(), static_cast<int32_t>(tokens.size()), false, true) < 0) {
        LOGE("分词失败(2)");
        return env->NewStringUTF("");
    }

    // ---- 3. 上下文长度保护：超出时丢弃最早的历史 ----
    const int32_t nCtx   = s->n_ctx;
    int32_t maxNew       = maxTokens > 0 ? maxTokens : 256;
    int32_t budgetInput  = nCtx - maxNew - 8;
    if (budgetInput < 64) {
        budgetInput = nCtx / 2;
        maxNew      = nCtx - budgetInput - 8;
        if (maxNew < 16) maxNew = 16;
    }
    if (static_cast<int32_t>(tokens.size()) > budgetInput) {
        tokens.erase(tokens.begin(), tokens.end() - budgetInput);
    }

    llama_memory_clear(llama_get_memory(s->ctx), true);

    // ---- 4. 预填充 ----
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(s->ctx, batch) != 0) {
        LOGE("预填充 decode 失败");
        return env->NewStringUTF("");
    }

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
        if (llama_vocab_is_eog(vocab, id)) break;

        int32_t np = llama_token_to_piece(vocab, id, piece.data(),
                                          static_cast<int32_t>(piece.size()), 0, false);
        if (np < 0) {
            piece.resize(static_cast<size_t>(-np));
            np = llama_token_to_piece(vocab, id, piece.data(),
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

        llama_batch next = llama_batch_get_one(const_cast<llama_token *>(&id), 1);
        if (llama_decode(s->ctx, next) != 0) break;
    }
    stream.flush(full);

    llama_sampler_free(smpl);
    if (aborted) LOGI("生成被取消");
    return toJString(env, full);
}

JNIEXPORT jstring JNICALL
Java_com_mscope_browser_llama_LlamaBridge_nativeVersion(JNIEnv *env, jclass) {
    return env->NewStringUTF("llama.cpp b11205 (arm)");
}

}  // extern "C"