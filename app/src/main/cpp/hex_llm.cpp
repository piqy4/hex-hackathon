#include <jni.h>
#include "llama.h"
#include <algorithm>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

using Clock = std::chrono::steady_clock;
struct Deadline { Clock::time_point end; };
static bool expired(void *p) { return Clock::now() >= static_cast<Deadline *>(p)->end; }
static bool loading(float, void *p) { return !expired(p); }
struct Session {
    llama_model *model;
    int threads;
    ~Session() { llama_model_free(model); }
};
static std::string bytes(JNIEnv *env, jbyteArray arr) {
    std::string out(env->GetArrayLength(arr), '\0');
    env->GetByteArrayRegion(arr, 0, out.size(), reinterpret_cast<jbyte *>(out.data()));
    return out;
}
static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_hex_llm_LlamaSession_nativeOpen(JNIEnv *env, jobject, jbyteArray path, jint threads) {
    try {
        static std::once_flag init;
        std::call_once(init, [] { llama_backend_init(); });
        Deadline deadline{Clock::now() + std::chrono::seconds(60)};
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.load_mode = LLAMA_LOAD_MODE_MMAP;
        params.progress_callback = loading;
        params.progress_callback_user_data = &deadline;
        auto model = llama_model_load_from_file(bytes(env, path).c_str(), params);
        if (!model) throw std::runtime_error("Cannot load GGUF (format, memory or timeout)");
        auto session = std::make_unique<Session>();
        session->model = model;
        session->threads = std::clamp<int>(threads, 1, 8);
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception &e) { fail(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_hex_llm_LlamaSession_nativeComplete(JNIEnv *env, jobject, jlong handle,
        jbyteArray systemBytes, jbyteArray inputBytes, jint maxTokens, jlong timeoutMs) {
    try {
        if (!handle || maxTokens < 1 || maxTokens > 512 || timeoutMs < 1 || timeoutMs > 120000)
            throw std::runtime_error("Invalid generation settings");
        auto &s = *reinterpret_cast<Session *>(handle);
        Deadline deadline{Clock::now() + std::chrono::milliseconds(timeoutMs)};
        const auto system = bytes(env, systemBytes), input = bytes(env, inputBytes);
        llama_chat_message messages[] = {{"system", system.c_str()}, {"user", input.c_str()}};
        const char *tmpl = llama_model_chat_template(s.model, nullptr);
        if (!tmpl) throw std::runtime_error("Missing chat template");
        std::vector<char> formatted(16384);
        int len = llama_chat_apply_template(tmpl, messages, 2, true, formatted.data(), formatted.size());
        if (len < 0 || len > static_cast<int>(formatted.size())) throw std::runtime_error("Invalid or long prompt");
        const auto *vocab = llama_model_get_vocab(s.model);
        int n = -llama_tokenize(vocab, formatted.data(), len, nullptr, 0, true, true);
        if (n <= 0 || n + maxTokens > 2048) throw std::runtime_error("Prompt exceeds context budget");
        std::vector<llama_token> tokens(n);
        if (llama_tokenize(vocab, formatted.data(), len, tokens.data(), n, true, true) != n)
            throw std::runtime_error("Tokenization failed");
        auto params = llama_context_default_params();
        params.n_ctx = 2048;
        params.n_batch = 256;
        params.n_ubatch = 128;
        params.n_threads = s.threads;
        params.n_threads_batch = s.threads;
        params.abort_callback = expired;
        params.abort_callback_data = &deadline;
        auto ctx = std::unique_ptr<llama_context, decltype(&llama_free)>(llama_init_from_model(s.model, params), llama_free);
        if (!ctx) throw std::runtime_error("Cannot allocate context");
        auto batch = std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)>(llama_batch_ext_init(ctx.get()), llama_batch_ext_free);
        auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        if (!batch || !sampler) throw std::runtime_error("Cannot allocate decoder");
        const char *grammar = R"GRAMMAR(
root ::= "[" ws item ("," ws item){0,2} "]" ws
item ::= "{" ws "\"id\"" ws ":" ws string "," ws "\"text\"" ws ":" ws string "}" ws
string ::= "\"" ([^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}))* "\"" ws
ws ::= [ \t\n\r]{0,8}
)GRAMMAR";
        auto constrained = llama_sampler_init_grammar(vocab, grammar, "root");
        if (!constrained) throw std::runtime_error("Invalid JSON grammar");
        llama_sampler_chain_add(sampler.get(), constrained);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        auto decode = [&](const llama_token *ids, int count, int start) {
            if (expired(&deadline)) throw std::runtime_error("LLM timeout");
            llama_batch_ext_clear(batch.get());
            for (int i = 0; i < count; ++i) {
                int idx = llama_batch_ext_add_token(batch.get(), 0, ids[i]);
                llama_pos pos = start + i;
                llama_batch_ext_set_pos(batch.get(), idx, &pos);
            }
            llama_batch_ext_set_output_logits(batch.get(), count - 1, true);
            if (llama_process(ctx.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0)
                throw std::runtime_error("Decode failed or timed out");
        };
        for (int pos = 0; pos < n; pos += 256) decode(tokens.data() + pos, std::min(256, n - pos), pos);
        std::string output;
        bool finished = false;
        for (int i = 0; i < maxTokens; ++i) {
            if (expired(&deadline)) throw std::runtime_error("LLM timeout");
            auto token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { finished = true; break; }
            std::vector<char> piece(256);
            int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (size < 0) { piece.resize(-size); size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
            if (size < 0) throw std::runtime_error("Invalid token");
            output.append(piece.data(), size);
            if (output.size() > 16384) throw std::runtime_error("Output too long");
            decode(&token, 1, n + i);
        }
        if (!finished) throw std::runtime_error("Generation token budget exhausted");
        auto result = env->NewByteArray(output.size());
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception &e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hex_llm_LlamaSession_nativeClose(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Session *>(handle);
}
