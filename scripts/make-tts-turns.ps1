<#
.SYNOPSIS
  Generates turns of one synthetic Russian speaker for the composed evaluation dialogs.

.DESCRIPTION
  Uses the Windows "Microsoft Irina" voice (ru-RU, installed with the Russian language pack) to
  write test-audio/eval/irina-NN.wav (PCM16, 16 kHz, mono) and irina.tsv (file, speaker, source,
  text; UTF-8) next to the clips from `./gradlew downloadEvalSet`. EvaluationTest uses them as the
  consistent "speaker B" of scenario A and as short replies in scenario B.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/make-tts-turns.ps1
#>
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech

$turns = @(
    'Добрый день, коллеги.',
    'Сегодня мы обсуждаем план работ на следующую неделю.',
    'Первый вопрос касается сроков поставки оборудования на станцию.',
    'Поставщик подтвердил, что груз прибудет в среду утром.',
    'Второй вопрос связан с проверкой системы охлаждения.',
    'Инженеры закончили осмотр и не нашли серьёзных замечаний.',
    'Тем не менее нужно повторить испытания после замены датчиков.',
    'Предлагаю назначить ответственного и зафиксировать решение в протоколе.',
    'Если вопросов нет, давайте перейдём к следующему пункту повестки.',
    # Short replies for fast turn-taking.
    'Да.', 'Согласен.', 'Хорошо, записал.', 'Нет, это не так.', 'Подождите, уточню.',
    'А когда будут результаты?', 'Понятно, спасибо.', 'Давайте обсудим это позже.'
)

$root = Split-Path -Parent $PSScriptRoot
$evalDir = Join-Path $root 'test-audio\eval'
New-Item -ItemType Directory -Force -Path $evalDir | Out-Null

$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$tsv = New-Object System.Text.StringBuilder
try {
    $voice = $synth.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -eq 'ru-RU' } | Select-Object -First 1
    if ($null -eq $voice) { throw 'No ru-RU voice installed (Settings, Time and language, Speech).' }
    $synth.SelectVoice($voice.VoiceInfo.Name)
    $format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(
        16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)

    for ($i = 0; $i -lt $turns.Count; $i++) {
        $name = 'irina-{0:D2}.wav' -f $i
        $synth.SetOutputToWaveFile((Join-Path $evalDir $name), $format)
        $synth.Speak($turns[$i])
        $synth.SetOutputToNull()
        [void]$tsv.Append("$name`tirina`twindows-tts/irina/$i`t$($turns[$i])`n")
    }
} finally {
    $synth.Dispose()
}
[System.IO.File]::WriteAllText((Join-Path $evalDir 'irina.tsv'), $tsv.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Output "Wrote $($turns.Count) turns to $evalDir"
