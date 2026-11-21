package io.github.artcraftandroid.capcraft.ui.home

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Checking for, downloading and handing over a new build.
 *
 * The app has no store behind it, so `version.json` in the source tree is the catalogue: the
 * version it names, the APK to fetch, its size and digest, and what changed. Everything is plain
 * HTTP on the app's own thread pool; nothing is uploaded and nothing is installed without the
 * user tapping Install and the system's own installer agreeing.
 */
object Updates {
    /** Where the catalogue lives: the repository's own `version.json`, over HTTPS. */
    const val MANIFEST_URL = "https://raw.githubusercontent.com/dwicao/capcraft-android/main/version.json"

    /** One published build, as `version.json` describes it. */
    data class Info(
        val version: String,
        val versionCode: Long,
        val apkUrl: String,
        val sizeBytes: Long,
        val sha256: String,
        val releasedAt: String,
        val notes: List<String>,
    ) {
        /** Whether this build is newer than the one running. */
        fun isNewerThan(code: Long): Boolean = versionCode > code
    }

    /**
     * The code `version.json` publishes (`capcraft.versionCode`) from the code the package carries:
     * the build adds the ABI digit, so the packaged code is `base * 10 + abi` and the base is the
     * installed code without its last digit.
     */
    fun baseCode(packagedCode: Long): Long = packagedCode / 10

    /** Reads the catalogue. A network or parse failure comes back as a failed [Result]. */
    suspend fun check(): Result<Info> = withContext(Dispatchers.IO) {
        runCatching {
            val text = get(MANIFEST_URL)
            val json = JSONObject(text)
            val notes = json.optJSONArray("notes")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            Info(
                version = json.getString("version"),
                versionCode = json.getLong("versionCode"),
                apkUrl = json.getString("apkUrl"),
                sizeBytes = json.optLong("sizeBytes", 0L),
                sha256 = json.optString("sha256"),
                releasedAt = json.optString("releasedAt"),
                notes = notes,
            )
        }
    }

    /** Where a download lands, under the app's cache (never the user's storage). */
    fun downloadDir(context: Context): File = File(context.cacheDir, "updates").also { it.mkdirs() }

    /** The file already downloaded for [info], when it is there and its digest matches. */
    fun downloaded(context: Context, info: Info): File? {
        val file = File(downloadDir(context), "capcraft-${info.version}.apk")
        if (!file.isFile || file.length() == 0L) return null
        if (info.sizeBytes > 0 && file.length() != info.sizeBytes) return null
        if (info.sha256.isNotBlank() && !matches(file, info.sha256)) return null
        return file
    }

    /**
     * Fetches [info]'s APK, reporting `(done, total)` bytes as it goes. [cancelled] is asked
     * before every chunk, and a cancelled download leaves nothing behind. The finished file is
     * checked against the catalogue's size and digest before it is handed back.
     */
    suspend fun download(
        context: Context,
        info: Info,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            downloaded(context, info)?.let { onProgress(it.length(), it.length()); return@runCatching it }
            val target = File(downloadDir(context), "capcraft-${info.version}.apk")
            val part = File(target.parentFile, target.name + ".part")
            part.delete()
            var total = info.sizeBytes
            try {
                stream(info.apkUrl) { input: java.io.InputStream, length: Long ->
                    if (length > 0) total = length
                    var done = 0L
                    var last = 0L
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) throw Cancelled()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            done += read
                            val now = System.currentTimeMillis()
                            if (now - last >= 120 || done == total) {
                                last = now
                                onProgress(done, total)
                            }
                        }
                    }
                }
            } catch (e: Cancelled) {
                part.delete()
                throw e
            }
            if (total > 0 && part.length() != total) {
                part.delete()
                throw IllegalStateException("The download stopped at ${part.length()} of $total bytes.")
            }
            if (info.sha256.isNotBlank() && !matches(part, info.sha256)) {
                part.delete()
                throw IllegalStateException("The downloaded file does not match the published one.")
            }
            part.renameTo(target)
            onProgress(target.length(), target.length().coerceAtLeast(total))
            target
        }
    }

    /** Thrown into a download that the user cancelled; never shown to them as an error. */
    class Cancelled : RuntimeException("Cancelled")

    /** Hands [file] to the system's installer. False when the phone will not take it. */
    fun install(context: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    /** Whether the app may ask the system to install a package at all on this phone. */
    fun canInstall(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(true)

    // --- plumbing -------------------------------------------------------------------

    private fun get(url: String): String = stream(url) { input, _ ->
        input.readBytes().toString(Charsets.UTF_8)
    }

    /**
     * Opens [url], following redirects (GitHub's release assets redirect to their CDN), and hands
     * the body to [read]. Returns whatever [read] returns.
     */
    private fun <T> stream(url: String, headers: Map<String, String>? = null, read: (java.io.InputStream, Long) -> T): T {
        var address = url
        repeat(6) {
            val connection = (URL(address).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "CapCraft-updater")
                headers?.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val next = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (next.isNullOrBlank()) throw IllegalStateException("The server sent no new address.")
                    address = if (next.startsWith("http")) next else URL(URL(address), next).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IllegalStateException("The server answered $code.")
                val length = connection.contentLengthLong.takeIf { it > 0 } ?: connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                return connection.inputStream.use { read(it, length) }
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Too many redirects.")
    }

    private fun matches(file: File, expected: String): Boolean = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }.equals(expected.trim(), ignoreCase = true)
    }.getOrDefault(false)
}