# hex-hackathon
Offline Android transcriber with speaker diarization and timestamps. Whisper + pyannote via sherpa-onnx, punctuation restored by an on-device LLM (Qwen2.5 + llama.cpp). JSON export, no network required. Nuclear IT Hack 2026.

## Сборка

Нужны Android Studio (JBR 21), Android SDK 37, сеть при первой сборке.

```sh
./gradlew :app:assembleDebug        # arm64-v8a + x86_64 (эмулятор)
./gradlew :app:assembleRelease      # только arm64-v8a, ASR = GigaAM v3 CTC
./gradlew :app:assembleRelease -PasrModel=zipformer-ru   # другая модель ASR в APK
./gradlew :app:testDebugUnitTest    # JVM-тесты: WAV, ресемплер, таймлайн, нарезка, WER, метрики
./gradlew :app:connectedDebugAndroidTest  # диаризация и оценка на устройстве/эмуляторе
```

`-PasrModel`: `gigaam-v3-ctc` (по умолчанию), `gigaam-v3-ctc-punct`, `zipformer-ru`, `whisper-base`,
`whisper-tiny`. Релизный APK с GigaAM — 273 МБ.

Большие бинарники в Git не хранятся. При первой сборке Gradle скачивает их и сверяет SHA-256,
дальше сборка работает офлайн:

| Файл | Источник (коммиты HF закреплены) | Лицензия |
|---|---|---|
| `sherpa-onnx-1.13.8.aar` → `app/libs/` | GitHub Releases k2-fsa/sherpa-onnx | Apache-2.0 |
| `pyannote-segmentation-3-0-int8.onnx` | HF csukuangfj/sherpa-onnx-pyannote-segmentation-3-0 | MIT |
| `3dspeaker-eres2net-voxceleb-16k.onnx` | sherpa-onnx speaker-recongition-models (3D-Speaker) | Apache-2.0 |
| `gigaam-v3-ctc.int8.onnx` + tokens | HF csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16 (GigaAM, SberDevices) | см. LICENSE в репозитории модели |
| остальные ASR (`gigaam-v3-ctc-punct`, `zipformer-ru`, `whisper-*`) | HF csukuangfj/sherpa-onnx-* | см. карточки моделей |

Все суммы — в `models/SHA256SUMS` (`cd models && sha256sum -c SHA256SUMS`). Скачиваются только веса,
попадающие в APK; `./gradlew downloadAllModels` скачивает все известные (для сравнений).

## Веса на устройстве

Веса лежат в APK несжатыми. При первой обработке `ModelStore` потоково копирует их в
`filesDir/models`, проверяя размер, SHA-256 и свободное место (с учётом очищаемого кэша);
повторные запуски только сверяют маркер. Веса, которых нет в APK (другая модель ASR или
альтернативные эмбеддинги), можно положить вручную — приложение примет их после проверки хеша:

```sh
adb push models/zipformer-ru-* /sdcard/Android/data/com.hex.stenographer/files/models/
```

Папка `Android/data` удаляется вместе с приложением, поэтому `connectedDebugAndroidTest` (который
удаляет APK после прогона) для сравнений не подходит — см. «Оценка качества».

## Пайплайн

1. **Импорт и PCM.** SAF → WAV (свой парсер RIFF) или MediaExtractor/MediaCodec → моно float
   16 кГц, шкала времени от начала файла. **Нормализация громкости**: уровень речи — медиана
   энергии 30 % самых громких кадров по 20 мс, приводится к −23 dBFS (усиление −12…+30 дБ).
   Пиковая нормализация не годилась: один щелчок рядом с тихой речью её отключал.
2. **Диаризация.** sherpa-onnx: pyannote segmentation 3.0 int8 + 3D-Speaker ERes2Net (VoxCeleb),
   автоматическое число кластеров, порог 0.85. Постобработка (`SpeakerTimeline`):
   - при наложении голосов остаётся один активный говорящий — более длинный исходный интервал,
     при равенстве — более ранний; **второй голос в зоне перекрытия теряется**;
   - соседние интервалы одного голоса склеиваются через паузу ≤ 300 мс;
   - фрагменты < 100 мс отбрасываются;
   - ID `speaker_1…N` по первому появлению, стабильны в пределах записи.
3. **Распознавание.** GigaAM v3 CTC int8 (русская модель SberDevices) через sherpa-onnx
   `OfflineRecognizer`. `ChunkPlanner` режет реплики длиннее 25 с у паузы, ближайшей к лимиту,
   без паузы — по лимиту; куски < 250 мс не распознаются. `TranscriptText` убирает теги шумов,
   зацикливания и типичные галлюцинации Whisper. Границы реплики — интервал диаризации,
   округлённый до мс и ограниченный длиной записи. GigaAM пишет строчными без пунктуации —
   регистр и знаки восстанавливает следующий этап (LLM).

## Оценка качества

Протокол из раздела 07 ТЗ реализован в `EvaluationTest` (метрики — `TranscriptScorer`):
WER с одинаковой нормализацией (нижний регистр, `ё`→`е`, без пунктуации, числа как написаны),
DER на сетке 10 мс, сопоставление ID говорящих один раз на запись по максимальному перекрытию,
ошибки начала/конца сопоставленных реплик, пропущенные и лишние реплики, ошибки голоса, RTF.

Данные (в Git не попадают):

```sh
./gradlew downloadEvalSet                                        # 56 клипов → test-audio/eval/
powershell -ExecutionPolicy Bypass -File scripts/make-tts-turns.ps1   # 17 реплик голоса Irina
```

| Набор | Что это | Роль |
|---|---|---|
| RuLibriSpeech test, 16 клипов | одна глава аудиокниги, один чтец | возвращающийся говорящий A |
| SOVA RuDevices test, 24 клипа | записи с телефонов, разные люди | короткие живые реплики |
| Golos farfield test, 16 клипов | умная колонка, дальний микрофон | шум и реверберация |
| Windows TTS «Irina», 17 реплик | синтетический, один голос | говорящий B, короткие ответы |

Из клипов на устройстве детерминированно собираются диалоги (все клипы выравниваются к −23 dBFS,
как запись одним телефоном): **A** — чтец и Irina попеременно, паузы 0,5–0,8 с; **B** — 14 разных
людей и короткие ответы, паузы 0,15–0,35 с (стресс-тест: 15 голосов); **C** — дальний микрофон и
чтец, паузы 1,5–4 с, тишина по краям, разброс громкости −10…0 дБ и шум −45 dBFS.

**Свои записи команды** кладутся в `test-audio/dialogs/<имя>.wav` + `<имя>.json` в формате
экспорта (`{"transcript":[{"speaker_id","start_ms","end_ms","text"}]}`) и оцениваются тем же тестом.

Запуск одной конфигурации (APK ставится один раз, альтернативные веса подложены):

```sh
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -e class com.hex.stenographer.eval.EvaluationTest \
  -e asrModel zipformer-ru -e threshold 0.85 -e suite all \
  com.hex.stenographer.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s Eval:I                       # подробности по каждой реплике
adb shell cat /sdcard/Android/data/com.hex.stenographer/files/eval/report.tsv   # строки отчёта
```

Аргументы: `asrModel`, `asrLanguage` (Whisper), `threshold`, `embedding`, `trim`/`lead`/`tail`,
`suite` = `clips` | `dialogs` | `all`.

### Результаты (эмулятор x86_64, 4 потока)

ASR на 73 клипах целиком, без диаризации (293,8 с аудио, 581 слово):

| Модель | Размер | WER всего | Golos far | SOVA | RuLibri | Irina | RTF ASR |
|---|---|---|---|---|---|---|---|
| **GigaAM v3 CTC** | 225 МБ | **5,3 %** | 5,7 % | 11,8 % | 1,4 % | 1,1 % | 0,044 |
| GigaAM v3 CTC punct | 225 МБ | 6,0 % | 6,9 % | 13,4 % | 1,8 % | 0,0 % | 0,047 |
| zipformer-ru int8 | 73 МБ | 6,9 % | 5,7 % | 8,6 % | 8,2 % | 1,1 % | 0,022 |
| Whisper base int8 | 160 МБ | 32,2 % | 69,0 % | 29,4 % | 31,5 % | 3,4 % | 0,098 |
| Whisper tiny int8 | 104 МБ | 43,0 % | 72,4 % | 41,2 % | 46,1 % | 10,2 % | 0,066 |

Полный пайплайн на собранных диалогах (GigaAM v3 CTC, VoxCeleb ERes2Net, порог 0.85):

| Диалог | Длит. | WER | DER | Говорящих | Пропущено / лишних | Ошибки голоса | Ошибка начала / конца | RTF |
|---|---|---|---|---|---|---|---|---|
| A чистый A–B–A | 77 с | 1,5 % | 0,14 | 2 из 2 | 0 / 0 | 2 из 12 | 203 / 401 мс | 0,12 |
| B быстрые смены | 65 с | 17,3 % | 0,59 | 8 из 15 | 0 / 0 | 12 из 21 | 323 / 516 мс | 0,11 |
| C паузы и шум | 80 с | 2,2 % | 0,12 | 7 из 9 | 0 / 0 | 2 из 12 | 46 / 103 мс | 0,09 |

Что дали изменения (тот же набор):
- Whisper base → GigaAM v3: WER клипов 32,2 % → 5,3 %, ASR быстрее в 2 раза.
- Пиковая → громкостная нормализация: в C пропущенных реплик 7 → 0, WER 46,2 % → 2,2 %.
- Порог 0.5 (пример SDK) → 0.85: в A говорящих 5 → 2, DER 0,38 → 0,14. Эмбеддинги zh-cn
  ERes2Net хуже VoxCeleb на всех порогах 0.5–1.0.
- Сужение границ по таймстемпам токенов GigaAM ни при каких отступах не улучшило ошибки границ —
  выключено (`BoundaryConfig.trimToTokens`).

Ограничения оценки: эмулятор на x86 хоста быстрее телефона — RTF и потребление памяти нужно
снять на проверочном arm64-устройстве; клипы взяты из тестовых частей датасетов, но модели
SberDevices могли видеть похожие данные (Golos — датасет Сбера); B с 15 голосами сложнее
реального диалога 2–4 человек. Окончательные настройки стоит подтвердить на записях команды.
