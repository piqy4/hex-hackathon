# hex-hackathon
Offline Android transcriber with speaker diarization and timestamps. Whisper + pyannote via sherpa-onnx, punctuation restored by an on-device LLM (Qwen2.5 + llama.cpp). JSON export, no network required. Nuclear IT Hack 2026.

## Сборка

Нужны Android Studio (JBR 21), Android SDK 37, сеть при первой сборке.

```sh
./gradlew :app:assembleDebug        # arm64-v8a + x86_64 (эмулятор)
./gradlew :app:assembleRelease      # только arm64-v8a
./gradlew :app:testDebugUnitTest    # JVM-тесты: WAV, ресемплер, таймлайн говорящих
./gradlew :app:connectedDebugAndroidTest  # диаризация на устройстве/эмуляторе
```

Большие бинарники в Git не хранятся. При первой сборке Gradle скачивает их и сверяет SHA-256,
дальше сборка работает офлайн:

| Файл | Куда | Источник |
|---|---|---|
| `sherpa-onnx-1.13.8.aar` | `app/libs/` | GitHub Releases k2-fsa/sherpa-onnx |
| `pyannote-segmentation-3-0-int8.onnx` | `models/` | HF csukuangfj/sherpa-onnx-pyannote-segmentation-3-0 (коммит закреплён) |
| `3dspeaker-eres2net-voxceleb-16k.onnx` | `models/` | sherpa-onnx speaker-recongition-models |
| `0-four-speakers-zh.wav` (только тест) | `test-audio/` | sherpa-onnx speaker-segmentation-models |

Контрольные суммы моделей — `models/SHA256SUMS` (`cd models && sha256sum -c SHA256SUMS`).

## Веса на устройстве

Веса лежат в APK несжатыми. При первой обработке `ModelStore` потоково копирует их в
`filesDir/models`, проверяя размер, SHA-256 и свободное место; повторные запуски только сверяют
маркер. Если APK собран без весов, их можно положить вручную, приложение проверит хеш:

```sh
adb push models/*.onnx /sdcard/Android/data/com.hex.stenographer/files/models/
```

## Пайплайн

1. **Импорт и PCM.** SAF → WAV (свой парсер RIFF) или MediaExtractor/MediaCodec → моно float
   16 кГц, шкала времени от начала файла. Пиковая нормализация (только усиление).
2. **Диаризация.** sherpa-onnx: pyannote segmentation 3.0 int8 + 3D-Speaker ERes2Net (VoxCeleb),
   автоматическое число кластеров, порог 0.8. Постобработка (`SpeakerTimeline`):
   - при наложении голосов остаётся один активный говорящий — более длинный исходный интервал,
     при равенстве — более ранний; **второй голос в зоне перекрытия теряется**;
   - соседние интервалы одного голоса склеиваются через паузу ≤ 300 мс;
   - фрагменты < 100 мс отбрасываются;
   - ID `speaker_1…N` по первому появлению, стабильны в пределах записи.

Замер на эмуляторе (x86_64, 4 потока): запись 56,9 с — декодирование 58 мс, диаризация 5,1 с
(RTF 0,09), 4 говорящих из 4.
