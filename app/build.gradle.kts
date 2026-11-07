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
 */
abstract class PrepareAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val subdir: Property<String>

    @get:Input
    abstract val writeManifest: Property<Boolean>

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
        if (writeManifest.get()) dir.resolve("manifest.properties").writeText(manifest.toString())
    }
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

/** Weights bundled into the APK. File name (as in SHA256SUMS) to source URL. */
val bundledModels = mapOf(
    "pyannote-segmentation-3-0-int8.onnx" to
        "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/9403a6902bb58e3d5ae8c7e77c3422de279db2e0/model.int8.onnx",
    "3dspeaker-eres2net-voxceleb-16k.onnx" to
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx",
)

val sherpaOnnxVersion = "1.13.8"

val downloadSherpaOnnx = tasks.register<DownloadVerified>("downloadSherpaOnnx") {
    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
    sha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
    destination = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")
}

val modelDownloads = bundledModels.map { (name, sourceUrl) ->
    tasks.register<DownloadVerified>("downloadModel-${name.substringBeforeLast('.')}") {
        url = sourceUrl
        sha256 = modelChecksums[name] ?: throw GradleException("$name is missing from models/SHA256SUMS")
        destination = rootProject.layout.projectDirectory.file("models/$name")
    }
}

val prepareModelAssets = tasks.register<PrepareAssets>("prepareModelAssets") {
    modelDownloads.forEach { task -> sources.from(task.flatMap { it.destination }) }
    subdir = "models"
    writeManifest = true
    outputDir = layout.buildDirectory.dir("generated/modelAssets")
}

val downloadTestAudio = tasks.register<DownloadVerified>("downloadTestAudio") {
    // Official sherpa-onnx diarization sample: 4 speakers, the first one speaks again at ~22 s.
    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/0-four-speakers-zh.wav"
    sha256 = "bedf036caed208386c67b4ef4b11f83d74dd0d420b102163a1c33cd09cde7010"
    destination = rootProject.layout.projectDirectory.file("test-audio/0-four-speakers-zh.wav")
}

val prepareTestAudioAssets = tasks.register<PrepareAssets>("prepareTestAudioAssets") {
    sources.from(downloadTestAudio.flatMap { it.destination })
    subdir = "audio"
    writeManifest = false
    outputDir = layout.buildDirectory.dir("generated/testAudioAssets")
}

// ---------------------------------------------------------------------------------------------

android {
    namespace = "com.hex.stenographer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.hex.stenographer"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inference targets arm64 phones; build types add ABIs on top of this.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug {
            // x86_64 lets the emulator run the native pipeline in instrumented tests.
            ndk { abiFilters += "x86_64" }
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
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
