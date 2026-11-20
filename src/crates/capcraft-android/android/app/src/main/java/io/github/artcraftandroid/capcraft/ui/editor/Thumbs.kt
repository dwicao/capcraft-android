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
