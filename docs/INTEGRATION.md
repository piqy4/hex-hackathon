# Модуль участника №3

Локальная LLM подключена к общему приложению после распознавания. Текущие команды сборки и приёмки — в [ROLE3.md](ROLE3.md). Для автономной проверки используйте RealLlmTest.

## Подключение к конвейеру

Пакет `com.example.hex.llm` использует через typealias единый DTO `com.hex.stenographer.transcript.Utterance`. Дублирующего класса реплики нет.

```kotlin
// Worker/coroutine, NEVER the UI thread.
val weights = ModelStore(context).prepare()
// Release native diarization and ASR sessions before opening LLM.
val ordered = speechEngine.run(audio).sortedBy { it.startMs }
TranscriptJson.validate(ordered, audio.durationMs)
val events = mutableListOf<PolishEvent>()
val finalItems = SafeTextPolisher(
    openSession = { LlamaSession(weights) },
    onEvent = { events += it }
).polish(ordered)
val json = TranscriptJson.encode(finalItems, audio.durationMs)
// Render finalItems and write THIS json through CreateDocument.
// Keep events in diagnostics, never add them to transcript JSON.
```

`SpeechEngine` и `PcmAudio` здесь обозначают контракт участника №2, это пример подключения, а не ещё одна реализация распознавания.

## Native и сборка

- ABI arm64-v8a, minSdk 26, compile/target SDK 37 (параметры исходного проекта).
- AGP 9.5.1, Gradle 9.6.0, Compose plugin 2.2.10, JDK 25.
- NDK 28.2.13676358; CMake 3.22.1; Gson 2.11.0.
- llama.cpp commit `cf659f240b232e15700b1721bbb43c1d973c52e9` лежит рядом с `hex_app`.
- CPU, mmap, 4 потока, контекст 2048, batch 256, microbatch 128, до 512 выходных токенов.
- JNI возвращает UTF-8 byte[], не `NewStringUTF`: русский текст и символы не ломаются на modified UTF-8.
- JSON grammar ограничивает форму вывода; независимый Kotlin-валидатор проверяет смысловые инварианты.
- R8 сохраняет `LlamaSession` через `app/src/main/keepRules/llm.keep`.

## Защита текста

Пакет по умолчанию состоит из одной реплики; можно 1-3, общий лимит 600 символов. На пользовательском тесте пакет из двух реплик не дал принятых правок, поэтому используется одиночный запрос. Длинные и пустые строки сохраняются без LLM. ID вида `u_0` — только внутри запроса. Выход применяется по ID, не по позиции. Дубликаты полей и ID, неизвестные поля, пропуски, неверные типы, Markdown, хвост после JSON, изменение слов или чисел отклоняют весь пакет. Событие отклонения содержит причину проверки.

Сравниваются токены букв, диакритики, цифр и символов после lowercase(Locale.ROOT). Пунктуация разделяет токены. `ё` и `е` здесь различаются; `15` не равно `пятнадцать`; `не верно` не равно `неверно`. Таймкоды и speakerId берутся только из исходного DTO. Защита не гарантирует правильный смысл пунктуации: например, запятая может быть спорной. Исходный ASR-текст не исправляется по словам.

На пакет отведено 30 секунд, на обработку списка 120 секунд; проверяется монотонное время. Native прерывает декодирование через abort callback и проверку между токенами. Загрузка GGUF имеет отдельный предел 60 секунд и входит в общий бюджет; отдельные операции ОС не имеют жёсткой гарантии моментального прерывания. Первый пакет включает холодную загрузку. При ошибке/бюджете сохраняется оригинал и пишется fallback-событие. Ошибки установки/контрольной суммы модели показываются пользователю отдельно, до вызова polisher.

## Веса и память

Запустить `python tools/prepare_model.py --assets` на компьютере с сетью до сборки. Скрипт скачивает фиксированную ревизию официального GGUF и проверяет размер и SHA-256. APK не запрашивает INTERNET. Первый запуск потоково переносит веса в noBackupFilesDir; повторные запуски проверяют SHA-256. Для импорта отдельного GGUF экран команды может вызвать ModelStore.import(uri).

Размер GGUF: 491 400 032 байта, примерно 468.6 MiB. Понадобится место для APK и распакованной копии; для установки комплекта планировать не менее 1.5 ГБ свободной внутренней памяти, окончательное требование подтвердить на телефоне. Проверка копирования оставляет запас 64 MiB. Замена файла атомарная, неполный `.part` удаляется. Веса исключены из резервного копирования. Модель закрывается в finally; контекст и sampler освобождаются после каждого запроса. На OnePlus PJA110 измерен максимум выборки PSS около 675 MiB; это не гарантированный абсолютный пик. Замер сделан в отдельном APK демонстрации модуля.

## Проверка на телефоне

1. Подключить телефон с отладкой USB и проверить, что `adb devices -l` показывает состояние device.
2. Подготовить веса в assets, включить авиарежим и отключить Wi-Fi.
3. Запустить проверку: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.hex.llm.RealLlmTest`.
4. Извлечь результаты через `adb exec-out run-as com.hex.stenographer cat files/llm-evidence.json` и аналогично для `files/llm-test-export.json`.

SpeechEngine и LLM подключены к экрану. Сквозной прогон проверяет IntegratedPipelineTest, а системный выбор файла и экспорт проверяются отдельно через UI.
