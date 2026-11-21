package io.github.artcraftandroid.capcraft.ui.editor

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import io.github.artcraftandroid.capcraft.core.filmstrip
import io.github.artcraftandroid.capcraft.core.still
import io.github.artcraftandroid.capcraft.core.waveform
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.toBitmap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The timeline's pictures and waveforms, made off the main thread and kept in memory. A miss starts
 * the work and returns null; [version] changes when anything arrives, so the timeline redraws.
 */
object Thumbs {
    val version = mutableIntStateOf(0)
    private val scope = CoroutineScope(SupervisorJob() + Threads.thumbnails)
    // android.util.LruCache is NOT thread-safe, and strips/waves are filled
    // from two thumbnail threads while the draw thread reads: every access
    // goes under these locks. An unguarded stampede (an agent splitting one
    // clip into a hundred) corrupted the map and bodies stayed blank.
    private val stripLock = Any()
    private val waveLock = Any()
    private val strips = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val waves = LruCache<String, FloatArray>(256)
    private val pending = ConcurrentHashMap.newKeySet<String>()

    private fun stripGet(key: String): Bitmap? = synchronized(stripLock) { strips.get(key) }
    private fun stripPut(key: String, value: Bitmap) = synchronized(stripLock) { strips.put(key, value) }
    private fun waveGet(key: String): FloatArray? = synchronized(waveLock) { waves.get(key) }
    private fun wavePut(key: String, value: FloatArray) = synchronized(waveLock) { waves.put(key, value) }

    /**
     * A cached strip of [path] whose time range covers [fromUs]..[toUs],
     * cropped to it — so the children of a split reuse the parent's already
     * decoded frames instead of decoding the same footage again. Only the
     * height must match: tiles slice their own range evenly, so any covering
     * strip sub-crops cleanly by time fraction.
     */
    fun stripCovering(path: String, fromUs: Long, toUs: Long, height: Int): Bitmap? =
        synchronized(stripLock) {
            val prefix = "s|$path|"
            var src: Bitmap? = null
            var srcFrom = 0L
            var bestSpan = Long.MAX_VALUE
            for ((key, bmp) in strips.snapshot()) {
                if (!key.startsWith(prefix)) continue
                val rest = key.removePrefix(prefix).split('|')
                if (rest.size != 4) continue
                val h = rest[3].toIntOrNull() ?: continue
                if (h != height) continue
                val f = rest[0].toLongOrNull() ?: continue
                val t = rest[1].toLongOrNull() ?: continue
                if (f > fromUs || t < toUs) continue
                val span = t - f
                if (span > 0 && span < bestSpan) {
                    bestSpan = span
                    src = bmp
                    srcFrom = f
                }
            }
            val bmp = src ?: return@synchronized null
            val x0 = ((fromUs - srcFrom).toFloat() / bestSpan * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val x1 = ((toUs - srcFrom).toFloat() / bestSpan * bmp.width).toInt().coerceIn(x0 + 1, bmp.width)
            if (x1 - x0 < 2) return@synchronized null
            runCatching { Bitmap.createBitmap(bmp, x0, 0, x1 - x0, bmp.height) }.getOrNull()
        }

    /**
     * Warms the cache for a freshly opened timeline: one full-span strip per
     * media file first (so every clip paints instantly via [stripCovering]),
     * then each clip's own strip for full tile detail. Keys match what
     * drawing requests, so nothing decodes twice. Runs on the thumbnail
     * threads; drawing picks results up as they land.
     */
    fun preload(rows: List<io.github.artcraftandroid.capcraft.core.RowView>, tileH: Int, pxPerUs: Float) {
        val videos = rows.flatMap { it.clips }.filter { it.kind == "video" }
        if (videos.isEmpty()) return
        val height = (tileH * 0.8f).roundToInt().coerceAtLeast(16)
        // full-span strips first: one decode per file covers every clip in it
        videos.groupBy { it.path }.forEach { (path, clips) ->
            val from = clips.minOf { it.sourceStartUs }
            val to = clips.maxOf { it.sourceStartUs + (it.durationUs * it.speed).toLong() }
            strip(path, from, to, 60, height)
        }
        // then each clip's own strip, for per-tile detail
        videos.forEach { clip ->
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
            strip(clip.path, from, to, count, height)
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
