package com.wsy.pixelproxygateway

import android.content.Context
import java.io.File

class LogStore(context: Context) {
    private val dir = File(context.filesDir, "logs").apply { mkdirs() }
    private val maxBytes = 512L * 1024L
    private val keepFiles = 4

    @Synchronized
    fun append(name: String, message: String) {
        val clean = message
            .replace(Regex("(?i)(password|proxy-authorization|authorization)=([^\\s]+)"), "$1=<redacted>")
            .take(4_000)
        val file = File(dir, "$name.log")
        rotateIfNeeded(file)
        file.appendText("${TimeUtil.now()} $clean\n")
    }

    @Synchronized
    fun tail(name: String, maxLines: Int = 80): String {
        val file = File(dir, "$name.log")
        if (!file.exists()) return ""
        return file.readLines().takeLast(maxLines).joinToString("\n")
    }

    @Synchronized
    fun tailAll(maxLines: Int = 120): String {
        val lines = mutableListOf<String>()
        listOf("app", "gost").forEach { name ->
            val file = File(dir, "$name.log")
            if (file.exists()) {
                file.readLines().takeLast(maxLines / 2).forEach { lines += "[$name] $it" }
            }
        }
        return lines.takeLast(maxLines).joinToString("\n")
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < maxBytes) return
        val oldest = File(dir, "${file.name}.$keepFiles")
        if (oldest.exists()) oldest.delete()
        for (i in keepFiles - 1 downTo 1) {
            val src = File(dir, "${file.name}.$i")
            val dst = File(dir, "${file.name}.${i + 1}")
            if (dst.exists()) dst.delete()
            if (src.exists()) src.renameTo(dst)
        }
        file.renameTo(File(dir, "${file.name}.1"))
    }
}
