import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------------------------
// Native dependencies and model weights.
//
// Large binaries are not stored in Git. They are downloaded once into /models, /test-audio and
// app/libs (all git-ignored), verified against pinned SHA-256 and reused offline afterwards.
// models/SHA256SUMS (sha256sum format) is the single source of truth for model checksums:
//   cd models && sha256sum -c SHA256SUMS
// ---------------------------------------------------------------------------------------------

object Sha256 {
    fun of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Downloads [url] to [destination] unless a file with the expected checksum is already there. */
abstract class DownloadVerified : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val destination: RegularFileProperty

    @TaskAction
    fun download() {
        val dst = destination.get().asFile
        val expected = sha256.get().lowercase()
        if (dst.isFile && Sha256.of(dst) == expected) return

        dst.parentFile.mkdirs()
        val part = File(dst.parentFile, "${dst.name}.part")
        logger.lifecycle("Downloading ${url.get()}")
        URI(url.get()).toURL().openStream().use { input ->
            part.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
        val actual = Sha256.of(part)
        if (actual != expected) {
            part.delete()
            throw GradleException("Checksum mismatch for ${dst.name}: expected $expected, got $actual")
        }
        Files.move(part.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Copies files into `<outputDir>/<subdir>/`. With [writeManifest], also writes
 * `manifest.properties` (`name=size:sha256`) that the app uses to verify installed weights.
 * [manifestOnly] lists known but not bundled weights (`name -> sha256`, size `-1`), which the app
 * accepts when they are sideloaded with adb.
 */
abstract class PrepareAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val subdir: Property<String>

    @get:Input
    abstract val writeManifest: Property<Boolean>

    @get:Input
    abstract val manifestOnly: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val dir = root.resolve(subdir.get()).apply { mkdirs() }
        val manifest = StringBuilder()
        sources.files.sortedBy { it.name }.forEach { file ->
            file.copyTo(dir.resolve(file.name), overwrite = true)
            manifest.append("${file.name}=${file.length()}:${Sha256.of(file)}\n")
        }
        manifestOnly.get().toSortedMap().forEach { (name, sha) -> manifest.append("$name=-1:$sha\n") }
        if (writeManifest.get()) dir.resolve("manifest.properties").writeText(manifest.toString())
    }
}

/**
 * Downloads small slices of public Russian speech datasets through the Hugging Face
 * datasets-server API into `test-audio/eval/` plus `clips.tsv` (file, speaker, source, text).
 * Not part of the build; run `./gradlew downloadEvalSet` once. Audio is not redistributed: each
 * row keeps its source (dataset@revision/split/row) so licenses stay traceable.
 */
abstract class DownloadEvalSet : DefaultTask() {
    /** `name|dataset|split|offset|count|speakerMode` where speakerMode is `group` or `clip`. */
    @get:Input
    abstract val slices: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val dir = outputDir.get().asFile.apply { mkdirs() }
        val tsv = StringBuilder()
        for (slice in slices.get()) {
            val (name, dataset, split, offset, count, mode) = slice.split('|')
            val api = "https://datasets-server.huggingface.co/rows?dataset=$dataset&config=default" +
                "&split=$split&offset=$offset&length=$count"
            @Suppress("UNCHECKED_CAST")
            val json = groovy.json.JsonSlurper().parse(URI(api).toURL()) as Map<String, Any?>
            val rows = json["rows"] as List<Map<String, Any?>>
            for (entry in rows) {
                val index = entry["row_idx"] as Int
                val row = entry["row"] as Map<String, Any?>
                val audio = (row["audio"] as List<Map<String, Any?>>).first()
                val text = (row["transcription"] as String).replace(Regex("""\s+"""), " ").trim()
                if (text.isEmpty()) continue
                val file = "$name-%04d.wav".format(index)
                URI(audio["src"] as String).toURL().openStream().use { input ->
                    dir.resolve(file).outputStream().use { input.copyTo(it) }
                }
                val speaker = if (mode == "group") name else "$name-$index"
                tsv.append("$file\t$speaker\t$dataset/$split/$index\t$text\n")
            }
            logger.lifecycle("$name: ${rows.size} clips from $dataset")
        }
        dir.resolve("clips.tsv").writeText(tsv.toString())
    }

    private operator fun <T> List<T>.component6(): T = this[5]
}

tasks.register<DownloadEvalSet>("downloadEvalSet") {
    group = "evaluation"
    description = "Fetches ~70 Russian clips with references for the on-device evaluation."
    slices = listOf(
        // One audiobook chapter: a single human reader who keeps coming back (speaker A).
        "rulib|bond005/rulibrispeech|test|0|16|group",
        // Phone recordings of many people: short real-world turns, one speaker per clip.
        "sova|bond005/sova_rudevices|test|0|24|clip",
        // Far-field smart-speaker requests: reverberation and noise.
        "golosfar|bond005/sberdevices_golos_100h_farfield|test|0|16|clip",
    )
    outputDir = rootProject.layout.projectDirectory.dir("test-audio/eval")
}

val modelChecksums: Map<String, String> =
    providers.fileContents(rootProject.layout.projectDirectory.file("models/SHA256SUMS")).asText.get()
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .associate { line ->
            val (hash, name) = line.split(Regex("\\s+\\*?"), limit = 2)
            name to hash
        }

fun hf(repo: String, commit: String, file: String) =
    "https://huggingface.co/csukuangfj/$repo/resolve/$commit/$file"

val whisperTiny = "65176e2deb88badc814a94058666cadccc29b61c"
val whisperBase = "bb53ee204431c90d314c1cc08d28d23e5b7927cc"
val gigaamCtc = "32a4c7cc81809bd132e2d935ab99e9e6ab47fbec"
val gigaamCtcPunct = "4fb5407ff028a69fec516cdf4c10fac9ddea7c16"
val zipformerRu = "641de8d322c05b9087ad2927ccda4bda3cccc159"

/**
 * ASR models by id (= AsrModel.id in the app): local file name to pinned source URL.
 * One is bundled: `./gradlew assembleRelease -PasrModel=whisper-base`.
 */
val asrModels: Map<String, Map<String, String>> = mapOf(
    "whisper-tiny" to mapOf(
        "whisper-tiny-encoder.int8.onnx" to hf("sherpa-onnx-whisper-tiny", whisperTiny, "tiny-encoder.int8.onnx"),
        "whisper-tiny-decoder.int8.onnx" to hf("sherpa-onnx-whisper-tiny", whisperTiny, "tiny-decoder.int8.onnx"),
        "whisper-tokens.txt" to hf("sherpa-onnx-whisper-tiny", whisperTiny, "tiny-tokens.txt"),
    ),
    "whisper-base" to mapOf(
        "whisper-base-encoder.int8.onnx" to hf("sherpa-onnx-whisper-base", whisperBase, "base-encoder.int8.onnx"),
        "whisper-base-decoder.int8.onnx" to hf("sherpa-onnx-whisper-base", whisperBase, "base-decoder.int8.onnx"),
        "whisper-tokens.txt" to hf("sherpa-onnx-whisper-tiny", whisperTiny, "tiny-tokens.txt"), // identical
    ),
    "gigaam-v3-ctc" to mapOf(
        "gigaam-v3-ctc.int8.onnx" to hf("sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16", gigaamCtc, "model.int8.onnx"),
        "gigaam-v3-ctc-tokens.txt" to hf("sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16", gigaamCtc, "tokens.txt"),
    ),
    "gigaam-v3-ctc-punct" to mapOf(
        "gigaam-v3-ctc-punct.int8.onnx" to
            hf("sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16", gigaamCtcPunct, "model.int8.onnx"),
        "gigaam-v3-ctc-punct-tokens.txt" to
            hf("sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16", gigaamCtcPunct, "tokens.txt"),
    ),
    "zipformer-ru" to listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt").associate {
        "zipformer-ru-$it" to hf("sherpa-onnx-zipformer-ru-int8-2025-04-20", zipformerRu, it)
    },
)

val asrModel: String = providers.gradleProperty("asrModel").getOrElse("gigaam-v3-ctc")
require(asrModel in asrModels) { "asrModel must be one of ${asrModels.keys}, got $asrModel" }

val diarizationModels = mapOf(
    "pyannote-segmentation-3-0-int8.onnx" to
        "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/9403a6902bb58e3d5ae8c7e77c3422de279db2e0/model.int8.onnx",
    "3dspeaker-eres2net-voxceleb-16k.onnx" to
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx",
)

/** Weights bundled into the APK. File name (as in SHA256SUMS) to source URL. */
val llmModels = mapOf("qwen2.5-0.5b-instruct-q4_k_m.gguf" to
    "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf")
val bundledModels = diarizationModels + asrModels.getValue(asrModel) + llmModels

/** Known alternatives that are not bundled, compared by sideloading. */
val alternativeModels = mapOf(
    "3dspeaker-eres2net-base-zh-cn-16k.onnx" to
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
)

/** Every known weight, for comparison runs (`./gradlew downloadAllModels`, then adb push). */
val allModels = diarizationModels + alternativeModels + llmModels +
    asrModels.values.fold(emptyMap<String, String>()) { acc, m -> acc + m }

val sherpaOnnxVersion = "1.13.8"

val downloadSherpaOnnx = tasks.register<DownloadVerified>("downloadSherpaOnnx") {
    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
    sha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
    destination = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")
}

val modelDownloads = allModels.mapValues { (name, sourceUrl) ->
    tasks.register<DownloadVerified>("downloadModel-${name.substringBeforeLast('.')}") {
        val cacheDir = providers.gradleProperty("modelCacheDir").orNull
        val cached = cacheDir?.let { File(it, name) }?.takeIf { it.isFile }
        url = cached?.toURI()?.toString() ?: sourceUrl
        sha256 = modelChecksums[name] ?: throw GradleException("$name is missing from models/SHA256SUMS")
        destination = rootProject.layout.projectDirectory.file("models/$name")
    }
}

tasks.register("downloadAllModels") {
    group = "models"
    description = "Downloads every known model into /models for comparison runs."
    dependsOn(modelDownloads.values)
}

val prepareModelAssets = tasks.register<PrepareAssets>("prepareModelAssets") {
    bundledModels.keys.forEach { name -> sources.from(modelDownloads.getValue(name).flatMap { it.destination }) }
    subdir = "models"
    writeManifest = true
    manifestOnly = modelChecksums - bundledModels.keys
    outputDir = layout.buildDirectory.dir("generated/modelAssets")
}

val downloadTestAudio = tasks.register<DownloadVerified>("downloadTestAudio") {
    // Official sherpa-onnx diarization sample: 4 speakers, the first one speaks again at ~22 s.
    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/0-four-speakers-zh.wav"
    sha256 = "bedf036caed208386c67b4ef4b11f83d74dd0d420b102163a1c33cd09cde7010"
    destination = rootProject.layout.projectDirectory.file("test-audio/0-four-speakers-zh.wav")
}

val prepareTestAudioAssets = tasks.register<PrepareAssets>("prepareTestAudioAssets") {
    dependsOn(downloadTestAudio)
    // Plus the evaluation set and team recordings, when present locally.
    sources.from(
        rootProject.layout.projectDirectory.dir("test-audio").asFileTree.matching {
            include("*.wav") // 4-speaker diarization sample
            include("eval/*.wav", "eval/*.tsv") // downloadEvalSet + scripts/make-tts-turns.ps1
            include("dialogs/*.wav", "dialogs/*.json") // team recordings with export-format references
        },
    )
    providers.gradleProperty("evalFixtureDir").orNull?.let { fixtureDir ->
        sources.from(fileTree(fixtureDir) { include("*_synthetic.wav", "*.reference.json") })
    }
    subdir = "audio"
    writeManifest = false
    manifestOnly = emptyMap()
    outputDir = layout.buildDirectory.dir("generated/testAudioAssets")
}

// ---------------------------------------------------------------------------------------------

android {
    namespace = "com.hex.stenographer"
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.hex.stenographer"
        externalNativeBuild {
            cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared") }
        }
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ASR_MODEL", "\"$asrModel\"")

        // Inference targets arm64 phones; build types add ABIs on top of this.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug {
            // x86_64 lets the emulator run the native pipeline in instrumented tests.
            ndk { abiFilters += "x86_64" }
        }
        release {
            // Installable hackathon build; production signing remains external.
            if (providers.gradleProperty("demoSigning").getOrElse("false").toBoolean()) {
                signingConfig = signingConfigs.getByName("debug")
            }
            // Scoped R8 optimization caused IllegalAccessError in ProcessLifecycleInitializer
            // on Android 13 (Kotlin collection superclass became inaccessible).
            // Native inference is still compiled in CMake Release.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // Stored uncompressed: streamed straight from the APK on first launch.
        noCompress += listOf("onnx", "gguf")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareModelAssets, PrepareAssets::outputDir)
        variant.androidTest?.sources?.assets
            ?.addGeneratedSourceDirectory(prepareTestAudioAssets, PrepareAssets::outputDir)
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation(files(downloadSherpaOnnx.flatMap { it.destination }))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
