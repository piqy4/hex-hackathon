# hex-hackathon
Offline Android transcriber with speaker diarization and timestamps. Whisper + pyannote via sherpa-onnx, punctuation restored by an on-device LLM (Qwen2.5 + llama.cpp). JSON export, no network required. Nuclear IT Hack 2026.

## Local LLM
See [integration and model setup](docs/INTEGRATION.md). Run tools/setup_llama.ps1 and python tools/prepare_model.py --assets from the repository root.
