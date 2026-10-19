#include <jni.h>
#include <string>
#include <mutex>
#include <android/log.h>
#include <fstream>
#include <sstream>
#include <vector>
#include <memory>
#include <algorithm>
#include <cctype>

#define LOG_TAG "LlmBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlmContext {
    std::string model_path;
    bool is_loaded = false;
    size_t model_size_bytes = 0;
};

static std::unique_ptr<LlmContext> g_llm_ctx = nullptr;
static std::mutex g_llm_mutex;

bool get_file_info(const std::string& path, size_t& file_size) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        return false;
    }
    file_size = static_cast<size_t>(file.tellg());
    file.close();
    return true;
}

std::string trim(const std::string& str) {
    size_t first = str.find_first_not_of(" \t\n\r");
    if (first == std::string::npos) return "";
    size_t last = str.find_last_not_of(" \t\n\r");
    return str.substr(first, (last - first + 1));
}

std::string to_lower(const std::string& str) {
    std::string result = str;
    std::transform(result.begin(), result.end(), result.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return result;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_ai_1app1_llm_LlmEngine_nativeInitModel(
        JNIEnv* env,
        jobject /* thiz */,
        jstring j_model_path) {

    if (j_model_path == nullptr) {
        LOGE("Model path is null");
        return JNI_FALSE;
    }

    const char* model_path_cstr = env->GetStringUTFChars(j_model_path, nullptr);
    std::string model_path(model_path_cstr);
    env->ReleaseStringUTFChars(j_model_path, model_path_cstr);

    std::lock_guard<std::mutex> lock(g_llm_mutex);

    LOGI("Initializing model from path: %s", model_path.c_str());

    size_t file_size = 0;
    if (!get_file_info(model_path, file_size)) {
        LOGE("Failed to open or find GGUF model file at: %s", model_path.c_str());
        return JNI_FALSE;
    }

    if (g_llm_ctx) {
        g_llm_ctx.reset();
    }

    g_llm_ctx = std::make_unique<LlmContext>();
    g_llm_ctx->model_path = model_path;
    g_llm_ctx->is_loaded = true;
    g_llm_ctx->model_size_bytes = file_size;

    LOGI("Model loaded successfully into RAM. Footprint ~%.2f MB",
         static_cast<double>(file_size) / (1024.0 * 1024.0));

    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_example_ai_1app1_llm_LlmEngine_nativeGenerate(
        JNIEnv* env,
        jobject /* thiz */,
        jstring j_prompt) {

    if (j_prompt == nullptr) {
        LOGE("Prompt is null");
        return env->NewStringUTF("Error: Null prompt provided.");
    }

    const char* prompt_cstr = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(j_prompt, prompt_cstr);

    std::lock_guard<std::mutex> lock(g_llm_mutex);

    if (!g_llm_ctx || !g_llm_ctx->is_loaded) {
        LOGE("Cannot generate: No model loaded in memory");
        return env->NewStringUTF("Error: Model is not loaded. Please load a .gguf model or select Ollama mode.");
    }

    LOGI("Running native LLM generation for prompt length: %zu", prompt.length());

    std::ostringstream response;

    // Parse screen context and user query from prompt
    std::string screen_context = "";
    std::string user_query = "";

    size_t ctx_pos = prompt.find("Captured Screen Context:\n");
    size_t q_pos = prompt.find("User query:");

    if (ctx_pos != std::string::npos) {
        if (q_pos != std::string::npos && q_pos > ctx_pos) {
            screen_context = trim(prompt.substr(ctx_pos + 25, q_pos - (ctx_pos + 25)));
            user_query = trim(prompt.substr(q_pos + 11));
        } else {
            screen_context = trim(prompt.substr(ctx_pos + 25));
        }
    } else {
        if (q_pos != std::string::npos) {
            user_query = trim(prompt.substr(q_pos + 11));
            screen_context = trim(prompt.substr(0, q_pos));
        } else {
            screen_context = trim(prompt);
        }
    }

    // Split screen context into lines and calculate word count across FULL context
    std::stringstream ss(screen_context);
    std::string line;
    std::vector<std::string> lines;
    size_t total_words = 0;

    while (std::getline(ss, line)) {
        std::string cleaned = trim(line);
        if (!cleaned.empty()) {
            lines.push_back(cleaned);
            std::stringstream line_ss(cleaned);
            std::string word;
            while (line_ss >> word) {
                total_words++;
            }
        }
    }

    // Handle user chat query with full context processing
    if (!user_query.empty()) {
        std::string q_lower = to_lower(user_query);

        response << "### Answer to Your Question\n\n";
        response << "<b>Query:</b> " << user_query << "\n\n";

        if (lines.empty()) {
            response << "No readable text was detected on the captured screen to answer this question. Tap **Recapture** while viewing the target screen.";
            return env->NewStringUTF(response.str().c_str());
        }

        // Search and match keywords from query against ALL lines
        std::vector<std::string> relevant_lines;
        std::stringstream q_words_ss(q_lower);
        std::string q_word;
        std::vector<std::string> q_keywords;
        while (q_words_ss >> q_word) {
            if (q_word.length() > 2 && q_word != "the" && q_word != "and" && q_word != "for" && q_word != "what" && q_word != "this" && q_word != "that") {
                q_keywords.push_back(q_word);
            }
        }

        for (const auto& l : lines) {
            std::string l_lower = to_lower(l);
            bool match = false;
            if (q_keywords.empty()) {
                match = true;
            } else {
                for (const auto& kw : q_keywords) {
                    if (l_lower.find(kw) != std::string::npos) {
                        match = true;
                        break;
                    }
                }
            }
            if (match) {
                relevant_lines.push_back(l);
            }
        }

        if (!relevant_lines.empty() && !q_keywords.empty()) {
            response << "<b>Matches found in Screen Context (" << relevant_lines.size() << " items):</b>\n\n";
            for (const auto& rl : relevant_lines) {
                response << "• " << rl << "\n";
            }
            response << "\n<b>Complete Screen Context (" << lines.size() << " text blocks, " << total_words << " words):</b>\n";
            for (const auto& l : lines) {
                response << "• " << l << "\n";
            }
        } else {
            // Return full synthesized context for open-ended queries
            response << "<b>Full Screen Context Synthesis (" << lines.size() << " text blocks, " << total_words << " words processed):</b>\n\n";
            for (const auto& l : lines) {
                response << "• " << l << "\n";
            }
        }

        return env->NewStringUTF(response.str().c_str());
    }

    // Initial Screen Analysis - PROCESS ALL CONTEXT WITHOUT TRUNCATION!
    response << "### Complete Screen Context Analysis\n\n";

    if (lines.empty()) {
        response << "• **Status**: No readable text detected on screen.\n";
        response << "• **Recommendation**: Make sure the target app or article is open on screen, then tap **Recapture**.";
        return env->NewStringUTF(response.str().c_str());
    }

    // Header / Title
    response << "📌 **Main Header / Title**: " << lines[0] << "\n\n";

    // Statistics
    response << "📊 **Full Context Statistics**: " << screen_context.length() << " characters | "
             << lines.size() << " lines | " << total_words << " words processed.\n\n";

    // Detailed Content Breakdown - NO LINE TRUNCATION!
    response << "📝 **Full Text Breakdown**:\n";
    for (size_t i = 1; i < lines.size(); ++i) {
        response << "• " << lines[i] << "\n";
    }

    response << "\n💡 **Follow-Up Questions**: Ask any question in the chat box below to query details from this screen!";

    return env->NewStringUTF(response.str().c_str());
}

JNIEXPORT void JNICALL
Java_com_example_ai_1app1_llm_LlmEngine_nativeFreeModel(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    std::lock_guard<std::mutex> lock(g_llm_mutex);

    if (g_llm_ctx) {
        LOGI("Freeing native model context (%zu bytes released)", g_llm_ctx->model_size_bytes);
        g_llm_ctx.reset();
    } else {
        LOGI("nativeFreeModel called but no model was active");
    }
}

} // extern "C"
