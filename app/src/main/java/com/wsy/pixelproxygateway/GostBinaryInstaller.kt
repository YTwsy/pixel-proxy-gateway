package com.wsy.pixelproxygateway

import android.content.Context
import java.io.File
import java.security.MessageDigest

class GostBinaryInstaller(
    private val context: Context,
    private val logStore: LogStore,
) {
    private val shaAssetPath = "gost/android-arm64/gost.sha256"
    private val tagAssetPath = "gost/android-arm64/gost.tag"
    private val commitAssetPath = "gost/android-arm64/gost.commit"
    private val nativeLibraryName = "libgost.so"

    fun ensureInstalled(): File {
        val target = File(context.applicationInfo.nativeLibraryDir, nativeLibraryName)
        if (!target.exists()) {
            error("Missing native GOST binary ${target.absolutePath}. Rebuild the APK with tools/build-gost-android-arm64.sh.")
        }
        val expectedSha = readExpectedSha()
        if (expectedSha.isNotBlank()) {
            val actual = sha256(target)
            if (actual != expectedSha) {
                error("GOST binary SHA256 mismatch expected=$expectedSha actual=$actual")
            }
        }
        if (!target.canExecute()) {
            error("Native GOST binary is not executable: ${target.absolutePath}")
        }
        return target
    }

    fun version(binary: File): String {
        return runCatching {
            val process = ProcessBuilder(binary.absolutePath, "-V")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.lineSequence().firstOrNull().orEmpty()
        }.getOrElse { "unknown: ${it.message}" }
    }

    fun assetInfo(): GostAssetInfo {
        return GostAssetInfo(
            tag = readTextAsset(tagAssetPath),
            commit = readTextAsset(commitAssetPath),
            sha256 = readExpectedSha(),
        )
    }

    private fun readExpectedSha(): String {
        return readTextAsset(shaAssetPath)
                .lineSequence()
                .firstOrNull()
                ?.split(Regex("\\s+"))
                ?.firstOrNull()
                .orEmpty()
    }

    private fun readTextAsset(path: String): String {
        return runCatching {
            context.assets.open(path).bufferedReader().use { it.readText() }.trim()
        }.getOrElse { "" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class GostAssetInfo(
    val tag: String,
    val commit: String,
    val sha256: String,
)
