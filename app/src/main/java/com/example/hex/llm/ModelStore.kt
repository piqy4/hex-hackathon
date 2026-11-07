package com.example.hex.llm

import android.content.Context
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Local, streaming, verified installation. Never downloads from the network. */
class ModelStore(private val context: Context) {
    companion object {
        const val NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        const val SIZE = 491400032L
        const val SHA256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
    }
    private val directory = File(context.noBackupFilesDir, "models").apply { mkdirs() }
    val file = File(directory, NAME)
    fun prepare(): File {
        if (file.isFile) { file.inputStream().use { copyVerified(it, null) }; return file }
        val input = try { context.assets.open("models/$NAME") }
        catch (e: java.io.IOException) { throw IllegalStateException("Веса отсутствуют. Выберите GGUF или соберите APK с assets.", e) }
        return input.use { install(it) }
    }
    fun import(uri: Uri): File = requireNotNull(context.contentResolver.openInputStream(uri)).use { install(it) }
    private fun copyVerified(input: InputStream, output: java.io.OutputStream?) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        var count = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            count += n
            require(count <= SIZE) { "Неверный размер GGUF" }
            digest.update(buffer, 0, n)
            output?.write(buffer, 0, n)
        }
        require(count == SIZE && digest.digest().joinToString("") { "%02x".format(it) } == SHA256) {
            "GGUF повреждён или выбрана другая модель: SHA-256 не совпадает"
        }
    }
    private fun install(input: InputStream): File {
        require(StatFs(directory.path).availableBytes >= SIZE + 64L * 1024 * 1024) {
            "Недостаточно места: требуется ещё около 555 МБ во внутренней памяти"
        }
        val part = File(directory, "$NAME.part")
        try {
            part.outputStream().use { copyVerified(input, it); it.fd.sync() }
            check(part.renameTo(file)) { "Не удалось сохранить модель" }
            return file
        } finally { part.delete() }
    }
}
