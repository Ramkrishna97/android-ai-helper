# Local AI Helper

Android app for local GGUF chat and screen text assistance. The native inference
engine is [llama.cpp](https://github.com/ggml-org/llama.cpp), pinned as a Git
submodule. Clone with `git clone --recurse-submodules`, or run
`git submodule update --init --recursive` before building.

Build with `gradlew.bat :app:assembleDebug`. The APK supports ARM64 Android
devices and x86_64 emulators. Load a GGUF text model in the app before starting
the helper. The floating Chat tab keeps its own conversation. Screen captures a
frame with Android MediaProjection, extracts visible text using Google ML Kit,
and uses that text only in the Screen conversation. Screen assistance requires
capturable text; ML Kit OCR does not interpret images or diagrams.

The settings expose CPU threads, context length, and prompt batch size. Context
length and batch size affect working memory, but the model file and device
determine the base memory requirement. Replies stream as tokens are generated;
prompt reading progress appears first. To run the optional on-device GGUF test,
place a small file named `ai-helper-test-model.gguf` in the app's private
`files` directory before running `connectedDebugAndroidTest`.


What it does : 
-- An android app that has a transparent Ai helper on screen
-- Loads a GGUF model from local , and use that as a personal AI-Helper
-- Can take screenshot at any moment, give summary and analysis of what is on screen( currently textx only ) , 
    User can chat with the model for given context.
-- Chat mode is only to chat with the model , it doesn't take context of the content on the screen.


What is Required to run this :
-- atleast 8gb (or more ) of RAM
-- Download any GGUF model , go to huggingface for choosing model, and easy download.