package io.github.artcraftandroid.capcraft.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import io.github.artcraftandroid.capcraft.core.filmstrip
import io.github.artcraftandroid.capcraft.core.still
import io.github.artcraftandroid.capcraft.core.waveform
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.toBitmap
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The timeline's pictures and waveforms, made off the main thread and kept in memory — and, for
 * the strips that cover a whole file, on disk too, so reopening a project paints at once.
 *
 * A miss starts the work and returns null; [version] changes when anything arrives, so the
 * timeline redraws. Nothing here allocates while drawing: a covering strip is handed back as a
 * [Cover] that names its own crop, so a hundred clips sharing one file share one bitmap and one
 * draw call each.
 */
object Thumbs {
    val version = mutableIntStateOf(0)
    private val scope = CoroutineScope(SupervisorJob() + Threads.thumbnails)
    // android.util.LruCache is NOT thread-safe, and strips/waves are filled
    // from the thumbnail threads while the draw thread reads: every access
    // goes under this lock.
    private val lock = Any()
    private val strips = object : LruCache<String, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            // keep the covering index in step with what is actually cached
            synchronized(lock) { covering.remove(key) }
        }
    }
    private val waves = LruCache<String, FloatArray>(256)
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** A strip of a file, and the part of it a clip shows: drawn straight from the bitmap. */
    class Cover(val bmp: Bitmap, val x: Int, val width: Int)

    /** The strips that cover a whole file, keyed `path|height`, smallest span first. */
    private val covering = HashMap<String, MutableList<Entry>>()

    private class Entry(val key: String, val fromUs: Long, val toUs: Long, val bmp: Bitmap)

    private fun stripGet(key: String): Bitmap? = synchronized(lock) { strips.get(key) }

    private fun stripPut(key: String, value: Bitmap): Bitmap {
        synchronized(lock) {
            strips.put(key, value)
            // every strip can cover a span, so every one is indexed; the
            // shortest span that covers a clip wins, which keeps tiles sharp
            if (key.startsWith("s|")) {
                val parts = key.split('|')
                if (parts.size == 6) {
                    val path = parts[1]
                    val from = parts[2].toLongOrNull() ?: 0L
                    val to = parts[3].toLongOrNull() ?: 0L
                    val height = parts[5].toIntOrNull() ?: 0
                    val list = covering.getOrPut("$path|$height") { mutableListOf() }
                    list.removeAll { it.key == key }
                    list.add(Entry(key, from, to, value))
                    list.sortBy { it.toUs - it.fromUs }
                }
            }
        }
        return value
    }

    private fun waveGet(key: String): FloatArray? = synchronized(lock) { waves.get(key) }
    private fun wavePut(key: String, value: FloatArray) = synchronized(lock) { waves.put(key, value) }

    // --- the disk cache, for whole-file strips -------------------------------------------

    private var diskDir: File? = null

    /** Told the app's context once, so whole-file strips can outlive the process. */
    fun init(context: Context) {
        diskDir = File(context.cacheDir, "thumbs").also { it.mkdirs() }
    }

    private fun diskFile(path: String, fromUs: Long, toUs: Long, height: Int): File? {
        val dir = diskDir ?: return null
        val file = File(path)
        // the file's size and stamp go into the key: a replaced file is not
        // served yesterday's pictures
        val stamp = "${path}|${file.length()}|${file.lastModified()}|$fromUs|$toUs|$height"
        val digest = MessageDigest.getInstance("SHA-1").digest(stamp.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) } + ".png"
        return File(dir, name)
    }

    private fun diskRead(file: File): Bitmap? = runCatching {
        if (!file.isFile) return null
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    private fun diskWrite(file: File, bmp: Bitmap) {
        runCatching {
            if (file.isFile) return
            file.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            pruneDisk()
        }
    }

    /** Keeps the thumbnail cache under about 192 MB, oldest first. */
    private fun pruneDisk() {
        val dir = diskDir ?: return
        val files = dir.listFiles() ?: return
        val total = files.sumOf { it.length() }
        if (total <= 192L * 1024 * 1024) return
        var left = total
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (left <= 128L * 1024 * 1024) return@forEach
            left -= file.length()
            file.delete()
        }
    }

    /**
     * The part of a cached strip of [path] that shows [fromUs]..[toUs], for a row [height]
     * pixels tall — or null while nothing covering it has arrived yet. No bitmap is made: the
     * caller draws the crop itself.
     */
    fun cover(path: String, fromUs: Long, toUs: Long, height: Int): Cover? = synchronized(lock) {
        val list = covering["$path|$height"] ?: return@synchronized null
        for (entry in list) {
            if (entry.fromUs > fromUs || entry.toUs < toUs) continue
            val span = entry.toUs - entry.fromUs
            if (span <= 0) continue
            val bmp = entry.bmp
            val x0 = ((fromUs - entry.fromUs).toFloat() / span * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val x1 = ((toUs - entry.fromUs).toFloat() / span * bmp.width).toInt().coerceIn(x0 + 1, bmp.width)
            if (x1 - x0 < 2) continue
            return@synchronized Cover(bmp, x0, x1 - x0)
        }
        null
    }

    /**
     * Warms the cache for a freshly opened timeline, in the background: one strip per media file
     * covering everything the timeline shows of it, at each row height it is drawn at. Every clip
     * then paints at once from that strip; tile-level detail arrives per clip only as it is drawn.
     * Disk first, so a project opened before paints without decoding anything.
     */
    fun preload(
        rows: List<io.github.artcraftandroid.capcraft.core.RowView>,
        heights: List<Int>,
        pxPerUs: Float,
    ) {
        val videos = rows.flatMap { it.clips }.filter { it.kind == "video" }
        if (videos.isEmpty()) return
        videos.groupBy { it.path }.forEach { (path, clips) ->
            val from = clips.minOf { it.sourceStartUs }
            val to = clips.maxOf { it.sourceStartUs + (it.durationUs * it.speed).toLong() }
            heights.distinct().forEach { height ->
                val h = height.coerceAtLeast(16)
                if (cover(path, from, to, h) != null) return@forEach
                val key = "s|$path|$from|$to|60|$h"
                if (!pending.add(key)) return@forEach
                scope.launch {
                    val put = runCatching {
                        val cached = diskFile(path, from, to, h)?.let(::diskRead)
                        if (cached != null) {
                            cached
                        } else {
                            val made = filmstrip(path, from, to, 60u, h.toUInt()).toBitmap() ?: return@launch
                            diskFile(path, from, to, h)?.let { file -> diskWrite(file, made) }
                            made
                        }
                    }.getOrNull()
                    if (put != null) stripPut(key, put)
                    pending.remove(key)
                    version.intValue++
                }
            }
        }
    }

    /** A strip of [count] frames of [path] between two source times, [height] pixels tall. */
    fun strip(path: String, fromUs: Long, toUs: Long, count: Int, height: Int): Bitmap? {
        val key = "s|$path|$fromUs|$toUs|$count|$height"
        stripGet(key)?.let { return it }
        if (pending.add(key)) {
            scope.launch {
                runCatching { filmstrip(path, fromUs, toUs, count.toUInt(), height.toUInt()).toBitmap() }
                    .getOrNull()?.let { stripPut(key, it) }
                pending.remove(key)
                version.intValue++
            }
        }
        return null
    }

    /** A still (a photo, or a frame of a video at [atUs]) [width] pixels across. */
    fun still(path: String, atUs: Long, width: Int): Bitmap? {
        val key = "p|$path|$atUs|$width"
        stripGet(key)?.let { return it }
        if (pending.add(key)) {
            scope.launch {
                runCatching { still(path, atUs, width.toUInt()).toBitmap() }.getOrNull()?.let { stripPut(key, it) }
                pending.remove(key)
                version.intValue++
            }
        }
        return null
    }

    /** [buckets] peaks of [path] between two source times, 0..1. */
    fun wave(path: String, project: String, fromUs: Long, toUs: Long, buckets: Int): FloatArray? {
        val key = "w|$path|$fromUs|$toUs|$buckets"
        waveGet(key)?.let { return it }
        if (pending.add(key)) {
            scope.launch {
                runCatching { waveform(path, project, fromUs, toUs, buckets.toUInt()).toFloatArray() }
                    .getOrNull()?.let { wavePut(key, it) }
                pending.remove(key)
                version.intValue++
            }
        }
        return null
    }
}