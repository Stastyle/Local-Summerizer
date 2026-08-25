# Local Summarizer — סיכום ישיבות אופליין

Fully offline Android app that transcribes meeting recordings with **whisper.cpp**
and summarizes them in Hebrew with **llama.cpp** (Qwen 2.5 Instruct GGUF).
No network access is needed at runtime — everything runs on-device.

אפליקציית אנדרואיד שמתמללת הקלטות ישיבות ומסכמת אותן בעברית — הכול מקומית על המכשיר, ללא אינטרנט.

## Status

🚧 Under active development. Build phases:

- [x] Phase 1 — Project skeleton, Compose UI shell (Main / Settings / History), CI APK builds
- [ ] Phase 2 — Audio decoding (MediaCodec → 16kHz mono PCM) + whisper.cpp JNI
- [ ] Phase 3 — llama.cpp JNI, Qwen ChatML pipeline, foreground service
- [ ] Phase 4 — Export/share, history, polish

## Download APK

Every push builds APKs in GitHub Actions and publishes them to the rolling
[`apk-latest` release](../../releases/tag/apk-latest). Install `app-release.apk`.

## Recommended models

| Purpose | Model | Size | Notes |
|---|---|---|---|
| Hebrew transcription | [ivrit-ai whisper-large-v3-turbo (GGML)](https://huggingface.co/ivrit-ai/whisper-large-v3-turbo-ggml) | ~1.6GB | Best Hebrew accuracy |
| Summarization (best) | [Qwen2.5-7B-Instruct GGUF Q4_K_M](https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF) | ~4.7GB | Needs a 12GB-RAM phone |
| Summarization (fast) | [Qwen2.5-3B-Instruct GGUF Q4_K_M](https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF) | ~2GB | Good quality, much faster |

Download the model files to your phone, then pick them in the app's Settings screen.

## Tech

Kotlin · Jetpack Compose · Material 3 · MVVM · Coroutines/Flow · DataStore ·
C++ NDK · whisper.cpp · llama.cpp (ARM NEON `-O3 -march=armv8.4-a+dotprod`) ·
arm64-v8a only.

The release APK is signed with a committed throwaway keystore intended for
personal sideloading only.
