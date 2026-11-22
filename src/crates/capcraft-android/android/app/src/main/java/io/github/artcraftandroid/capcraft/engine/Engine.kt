package io.github.artcraftandroid.capcraft.engine

import android.graphics.Bitmap
import io.github.artcraftandroid.capcraft.core.MobileException
import io.github.artcraftandroid.capcraft.core.Picture
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The threads engine calls run on. Never call the engine from the main thread. */
object Threads {
    /** The phone's cores: what the pools below scale to. */
    val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

    /** Edits run one at a time, in the order the UI issued them. */
    val commands: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** The preview: one picture at a time, the newest wins. */
    val preview: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * Timeline filmstrips, waveforms and covers. These decode in software at thumbnail size (see
     * concat-media's MEDIACODEC_SMALLEST), so they are pure CPU work and scale with the cores —
     * a cooled phone can keep most of them busy while the preview plays. Capped so the decoder
     * queue cannot crowd out the preview or the export.
     */
    val thumbnails: CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(cores.coerceIn(3, 6))

    /** Copying picked files into a project: disk work, never behind an edit. */
    val files: CoroutineDispatcher = Dispatchers.IO
}

/** A message for the user from anything the engine (or the system) threw. */
val Throwable.userMessage: String
    get() = when (this) {
        is MobileException -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
        else -> message?.takeIf { it.isNotBlank() && !it.contains("Exception") } ?: "Something went wrong."
    }

/** A picture's pixels as a new bitmap. */
fun Picture.toBitmap(): Bitmap? = intoBitmap(null)

/** A picture's pixels into [into] when it has the same size (no new allocation), else a new bitmap. */
fun Picture.intoBitmap(into: Bitmap?): Bitmap? {
    val w = width.toInt()
    val h = height.toInt()
    if (w <= 0 || h <= 0 || rgba.size < w * h * 4) return null
    val b = if (into != null && !into.isRecycled && into.isMutable && into.width == w && into.height == h) into
    else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    // ARGB_8888 keeps its bytes in memory as R, G, B, A: the engine's order
    b.copyPixelsFromBuffer(ByteBuffer.wrap(rgba, 0, w * h * 4))
    return b
}

/** Microseconds as the editor's clock shows them: 00:07, or 1:02:03 past an hour. */
fun clock(us: Long): String {
    val total = (us.coerceAtLeast(0) / 1_000_000)
    val h = total / 3600
    val m = (total / 60) % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Seconds with one decimal, as clip labels show durations: 3.2s. */
fun seconds1(us: Long): String = "%.1fs".format(us / 1_000_000.0)
