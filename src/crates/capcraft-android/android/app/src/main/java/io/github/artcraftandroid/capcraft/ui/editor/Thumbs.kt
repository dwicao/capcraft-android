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

    /**
     * A strip of a file that covers [fromUs]..[toUs] — the span of the clip asking — and the
     * pixels in it that cover that span ([x] and [width]); [stripFromUs]..[stripToUs] say what
     * the whole strip covers, so a tile can find its own slice instead of stretching one frame.
     */
    class Cover(
        val bmp: Bitmap,
        val x: Int,
        val width: Int,
        val fromUs: Long,
        val toUs: Long,
        val stripFromUs: Long,
        val stripToUs: Long,
        /** The frames the whole strip holds, so a tile can pick the one nearest its time. */
        val frames: Int,
    )

    /** The strips that cover a whole file, keyed `path|height`, smallest span first. */
    private val covering = HashMap<String, MutableList<Entry>>()

    private class Entry(val key: String, val fromUs: Long, val toUs: Long, val bmp: Bitmap, val frames: Int)

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
                    val frames = parts[4].toIntOrNull() ?: 1
                    val height = parts[5].toIntOrNull() ?: 0
                    val list = covering.getOrPut("$path|$height") { mutableListOf() }
                    list.removeAll { it.key == key }
                    list.add(Entry(key, from, to, value, frames.coerceAtLeast(1)))
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
            return@synchronized Cover(bmp, x0, x1 - x0, fromUs, toUs, entry.fromUs, entry.toUs, entry.frames)
        }
        null
    }

    /**
     * Warms the cache for a freshly opened timeline, in the background, in two passes:
     *
     * 1. one strip per file covering everything the timeline shows of it, at each row height —
     *    the instant placeholder every clip paints from (disk first, so a project opened before
     *    paints without decoding anything);
     * 2. each clip's own sharp strip, nearest the playhead first, computed exactly as the drawer
     *    asks for it — so tiles come out one frame each and nothing is decoded twice.
     */
    fun preload(
        rows: List<io.github.artcraftandroid.capcraft.core.RowView>,
        rowTileH: Int,
        mainTileH: Int,
        pxPerUs: Float,
        playheadUs: Long,
    ) {
        val videos = rows.flatMap { row -> row.clips.map { it to row.kind } }.filter { it.first.kind == "video" }
        if (videos.isEmpty()) return
        // pass 1: the covering placeholder, per file and per height
        videos.map { it.first }.groupBy { it.path }.forEach { (path, clips) ->
            val from = clips.minOf { it.sourceStartUs }
            val to = clips.maxOf { it.sourceStartUs + (it.durationUs * it.speed).toLong() }
            listOf(rowTileH, mainTileH).distinct().forEach { tileH ->
                val h = (tileH * 0.8f).roundToInt().coerceAtLeast(16)
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
        // pass 2: each clip's own strip, the one under the playhead first
        videos.sortedBy { kotlin.math.abs(it.first.startUs - playheadUs) }.forEach { (clip, kind) ->
            val tileH = (if (kind == io.github.artcraftandroid.capcraft.core.RowKind.MAIN) mainTileH else rowTileH).coerceAtLeast(16)
            val stripH = (tileH * 0.8f).roundToInt().coerceAtLeast(16)
            val from = clip.sourceStartUs
            val to = clip.sourceStartUs + (clip.durationUs * clip.speed).toLong()
            val widthPx = ((to - from) * pxPerUs / 1_000_000f).coerceAtLeast(tileH.toFloat())
            val needed = kotlin.math.ceil(widthPx / tileH).toInt().coerceAtLeast(1)
            val count = when {
                needed <= 4 -> needed
                needed <= 8 -> 8
                needed <= 16 -> 16
                needed <= 32 -> 32
                else -> 60
            }
            strip(clip.path, from, to, count, stripH)
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