#include <jni.h>
#include "llama.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

struct Engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    std::atomic<bool> cancelled{false};
    std::vector<llama_token> prompt_tokens;
    std::chrono::steady_clock::time_point deadline;
    ~Engine() { if (context) llama_free(context); if (model) llama_model_free(model); }
};
static std::once_flag backend_once;
static std::string bytes(JNIEnv *env, jbyteArray value) {
    const auto size = env->GetArrayLength(value);
    std::string result(size, '\0');
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
static jbyteArray output(JNIEnv *env, const std::string &text) {
    auto result = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()),
                           reinterpret_cast<const jbyte *>(text.data()));
    return result;
}
static bool should_abort(void *data) {
    auto *engine = static_cast<Engine *>(data);
    return engine->cancelled.load() || std::chrono::steady_clock::now() > engine->deadline;
}
static const char *json_grammar = R"grammar(
root ::= ws "{" ws "\"answer\"" ws ":" ws string ws "," ws "\"supported\"" ws ":" ws boolean ws "," ws "\"needs_online\"" ws ":" ws boolean ws "," ws "\"evidence\"" ws ":" ws "[" ws (string (ws "," ws string)*)? ws "]" ws "}" ws
string ::= "\"" char* "\""
char ::= [^"\\\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F])
boolean ::= "true" | "false"
ws ::= [ \t\n\r]*
)grammar";

extern "C" JNIEXPORT jlong JNICALL
Java_de_beckerrobotics_serviceroboter_app_llm_NativeLlmBridge_load(
        JNIEnv *env, jobject, jbyteArray path, jint context_size, jint threads) {
    try {
        std::call_once(backend_once, [] { llama_backend_init(); });
        auto engine = std::make_unique<Engine>();
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
        engine->model = llama_model_load_from_file(bytes(env, path).c_str(), model_params);
        if (!engine->model) throw std::runtime_error("Das GGUF-Modell konnte nicht geladen werden.");
        auto params = llama_context_default_params();
        params.n_ctx = static_cast<uint32_t>(context_size);
        params.n_batch = 256;
        params.n_ubatch = 128;
        params.n_threads = threads;
        params.n_threads_batch = threads;
        engine->context = llama_init_from_model(engine->model, params);
        if (!engine->context) throw std::runtime_error("Nicht genügend Speicher für das Sprachmodell.");
        llama_set_abort_callback(engine->context, should_abort, engine.get());
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception &error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return 0;
    }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_de_beckerrobotics_serviceroboter_app_llm_NativeLlmBridge_generate(
        JNIEnv *env, jobject, jlong handle, jbyteArray system, jbyteArray user, jint max_tokens) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    try {
        if (!engine) throw std::runtime_error("Sprachmodell ist nicht geladen.");
        engine->deadline = std::chrono::steady_clock::now() + std::chrono::seconds(90);
        std::string system_text = bytes(env, system);
        std::string user_text = bytes(env, user);
        llama_chat_message messages[] = {{"system", system_text.c_str()}, {"user", user_text.c_str()}};
        const char *model_template = llama_model_chat_template(engine->model, nullptr);
        int length = llama_chat_apply_template(model_template, messages, 2, true, nullptr, 0);
        if (length <= 0) throw std::runtime_error("Chatvorlage des GGUF-Modells wird nicht unterstützt.");
        std::vector<char> formatted(length + 1);
        llama_chat_apply_template(model_template, messages, 2, true, formatted.data(), formatted.size());
        std::string prompt(formatted.data(), length);
        const auto *vocab = llama_model_get_vocab(engine->model);
        int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (count <= 0 || count + max_tokens > static_cast<int>(llama_n_ctx(engine->context)))
            throw std::runtime_error("Die Frage mit Dokumentauszügen überschreitet das Kontextfenster.");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true) < 0)
            throw std::runtime_error("Tokenisierung fehlgeschlagen.");
        // Reuse only the exact token prefix; discard all previous answers and differing inputs.
        // This saves re-evaluating the fixed system prompt for every question.
        int common = 0;
        while (common < count - 1 && common < static_cast<int>(engine->prompt_tokens.size()) &&
               tokens[common] == engine->prompt_tokens[common]) ++common;
        if (common == 0 || !llama_memory_seq_rm(llama_get_memory(engine->context), 0, common, -1)) {
            llama_memory_clear(llama_get_memory(engine->context), true);
            common = 0;
        }
        engine->prompt_tokens.clear();
        for (int offset = common; offset < count; offset += 256) {
            if (should_abort(engine)) return output(env, "");
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(256, count - offset));
            if (llama_decode(engine->context, batch) != 0) return output(env, "");
        }
        engine->prompt_tokens = tokens;
        auto *sampler_raw = llama_sampler_chain_init(llama_sampler_chain_default_params());
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(sampler_raw, llama_sampler_free);
        auto *grammar = llama_sampler_init_grammar(vocab, json_grammar, "root");
        if (!grammar) throw std::runtime_error("JSON-Ausgabeformat konnte nicht geladen werden.");
        llama_sampler_chain_add(sampler.get(), grammar);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.8f, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.2f));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(42));
        std::string result;
        for (int i = 0; i < max_tokens; ++i) {
            if (should_abort(engine)) return output(env, "");
            auto token = llama_sampler_sample(sampler.get(), engine->context, -1);
            if (llama_vocab_is_eog(vocab, token)) return output(env, result);
            char buffer[256];
            int size = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, false);
            if (size < 0) {
                std::vector<char> bigger(-size);
                size = llama_token_to_piece(vocab, token, bigger.data(), bigger.size(), 0, false);
                if (size < 0) throw std::runtime_error("Ungültiges Ausgabetoken.");
                result.append(bigger.data(), size);
            } else result.append(buffer, size);
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(engine->context, batch) != 0) return output(env, "");
        }
        return output(env, result); // Kotlin rejects incomplete or malformed JSON.
    } catch (const std::exception &error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}
extern "C" JNIEXPORT void JNICALL
Java_de_beckerrobotics_serviceroboter_app_llm_NativeLlmBridge_prepare(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Engine *>(handle)->cancelled.store(false);
}
extern "C" JNIEXPORT void JNICALL
Java_de_beckerrobotics_serviceroboter_app_llm_NativeLlmBridge_cancel(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Engine *>(handle)->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_de_beckerrobotics_serviceroboter_app_llm_NativeLlmBridge_close(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Engine *>(handle);
}
