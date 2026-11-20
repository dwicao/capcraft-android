package io.github.artcraftandroid.capcraft.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import io.github.artcraftandroid.capcraft.core.ExportListener
import io.github.artcraftandroid.capcraft.core.ExportSettings
import io.github.artcraftandroid.capcraft.core.MediaInfo
import io.github.artcraftandroid.capcraft.core.MobileSession
import io.github.artcraftandroid.capcraft.core.Motion
import io.github.artcraftandroid.capcraft.core.ProjectSummary
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.core.cancelExport
import io.github.artcraftandroid.capcraft.core.createProject
import io.github.artcraftandroid.capcraft.core.exportSize
import io.github.artcraftandroid.capcraft.core.listProjects
import io.github.artcraftandroid.capcraft.core.packages
import io.github.artcraftandroid.capcraft.core.probeMedia
import io.github.artcraftandroid.capcraft.core.startExport
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * CapCraft's automation server: a loopback-socket JSON-RPC API for AI agents
 * (DSH-go's agent, scripts, editor plugins on the same machine).
 *
 * Mirrors desktop Concat's Settings → Automation socket and speaks the same
 * wire shape the Concat MCP bridge (`concat_bridge.py`) already drives: one
 * TCP connection per call, a first `auth` line carrying the token, then one
 * request line `{"method": …, …fields}`, one reply line per frame, and the
 * connection closes. Requests use the bare JSON shape `{"method": …, …}` and
 * the reply carries `"id": null`.
 *
 * Methods (all synchronous but export, which returns a job polled by status):
 *
 * - `version`, `capcraft_status`
 * - `project.list`, `project.create`, `project.open`, `project.get`,
 *   `project.document`, `project.save`, `project.close`, `project.setVideo`
 * - `media.probe`, `media.import`, `media.importSound`
 * - `edit.apply`, `edit.undo`, `edit.redo`, plus one method per timeline
 *   operation (`session.addToMain`, `session.addOverlay`, `session.addAudio`,
 *   `session.addSticker`, `session.addText`, `session.delete`, `session.split`,
 *   `session.trim`, `session.moveClip`, `session.reorderMain`, `session.update`)
 * - `catalogue.list` (effect/filter/audio/transition packages)
 * - `export.start`, `export.status`, `export.cancel`
 * - `preview.frame`
 *
 * Times cross as whole microseconds; the API's edit commands are the same
 * op-tagged JSON the app itself sends (`{"op": "splitClips", …}`), so one
 * `edit.apply` is one undo step, exactly as in the UI. Sessions are shared
 * with the editor through [SessionHub] — one object per project, so the
 * agent's edits appear live in the open editor, and the editor is told each
 * new view as it lands. Undo history is shared too: one timeline, one
 * history, whoever edits it.
 *
 * Security: loopback only (127.0.0.1), token on the first line within
 * [AUTH_TIMEOUT_MS], wrong tokens throttled in [throttle]. A server started
 * without a token mints 128 bits from the OS randomness, written to
 * `automation.json` beside the address so agents find it the way they find
 * desktop Concat, and the app's Automation screen shows it.
 */
object AgentServer {
    private const val TAG = "CapCraftAgent"
    private const val LISTING_NAME = "automation.json"
    private const val LISTING_VERSION = 1
    private const val AUTH_TIMEOUT_MS = 10_000L
    private const val CALL_TIMEOUT_MS = 30_000L
    private const val MAX_LINE = 4 * 1024 * 1024
    private const val MAX_FRAME_PX = 1280

    private val running = AtomicBoolean(false)
    private var socket: ServerSocket? = null
    private var token: String = ""
    private var listingFile: java.io.File? = null
    private var threads = Executors.newCachedThreadPool()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lane = Executors.newFixedThreadPool(4)

    /** Open editor sessions — one shared object per project, with the UI (see [SessionHub]). */
    private val hub: SessionHub get() = SessionHub

    /** The server's own reference per project it has touched (one each). */
    private val held = ConcurrentHashMap<String, MobileSession>()

    /** An export job: holds its own hub reference (see [startExportJob]). */
    private data class Job(
        val path: String,
        val session: MobileSession,
        val output: String,
        @Volatile var done: String? = null,
        @Volatile var error: String? = null,
    )

    private val jobs = ConcurrentHashMap<String, Job>()
    private var jobSeq = 0

    /** The app context, set once from [CapCraftApplication]. */
    @Volatile private var app: Context? = null

    /** Wrong-token attempts per address, for throttling. */
    private val failures = ConcurrentHashMap<String, Int>()

    fun address(): String {
        val s = socket ?: return ""
        return "127.0.0.1:${s.localPort}"
    }

    fun isRunning(): Boolean = running.get()

    /** Starts the server (idempotent). Reads token/address from [listingFile]. */
    fun start(context: Context): String {
        app = context.applicationContext
        if (running.getAndSet(true)) return address()
        listingFile = java.io.File(context.filesDir, LISTING_NAME)
        token = readListingToken() ?: mintToken()
        threads = Executors.newCachedThreadPool()
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        socket = server
        writeListing()
        scope.launch {
            while (running.get()) {
                try {
                    val client = server.accept()
                    threads.execute { handle(client) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
        Log.i(TAG, "agent server on ${address()}")
        return address()
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { socket?.close() }
        socket = null
        threads.shutdownNow()
        lane.shutdownNow()
        threads = Executors.newCachedThreadPool()
        try { listingFile?.delete() } catch (_: Exception) { }
    }

    private fun readListingToken(): String? = try {
        val obj = JSONObject(listingFile!!.readText())
        if (obj.optInt("version") == LISTING_VERSION) obj.optString("token").takeIf { it.length >= 20 } else null
    } catch (_: Exception) {
        null
    }

    private fun mintToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun writeListing() {
        try {
            listingFile!!.writeText(
                JSONObject()
                    .put("version", LISTING_VERSION)
                    .put("address", address())
                    .put("token", token)
                    .put("capcraft", "android")
                    .toString(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not write automation listing", e)
        }
    }

    /** One connection: an `auth` line, then one request line, one reply, close. */
    private fun handle(client: Socket) {
        val peer = client.inetAddress?.hostAddress ?: "?"
        try {
            client.soTimeout = AUTH_TIMEOUT_MS.toInt()
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(client.getOutputStream(), true)
            val authLine = readLine(reader) ?: return
            val auth = runCatching { JSONObject(authLine) }.getOrNull()
            val presented = auth?.optJSONObject("params")?.optString("token") ?: auth?.optString("token")
            if (presented != token) {
                throttle(peer)
                writer.println(JSONObject().put("id", 1).put("error", JSONObject().put("message", "bad token")).toString())
                return
            }
            failures.remove(peer)
            writer.println(JSONObject().put("id", 1).put("result", JSONObject()).toString())
            client.soTimeout = CALL_TIMEOUT_MS.toInt()
            val requestLine = readLine(reader) ?: return
            val request = runCatching { JSONObject(requestLine) }.getOrNull()
            val reply = if (request == null) {
                error(null, "not a JSON request")
            } else {
                runCatching { dispatch(request) }.getOrElse { error(request.opt("id"), it.userMessage) }
            }
            writer.println(reply.toString())
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readLine(reader: BufferedReader): String? {
        val sb = StringBuilder()
        while (sb.length <= MAX_LINE) {
            val c = reader.read()
            if (c < 0) return sb.ifEmpty { null }?.toString()
            if (c == '\n'.code) break
            sb.append(c.toChar())
        }
        return sb.toString().ifEmpty { null }
    }

    private fun throttle(peer: String) {
        val n = (failures[peer] ?: 0) + 1
        failures[peer] = n
        try {
            Thread.sleep(minOf(200L * n, 2000L))
        } catch (_: InterruptedException) {
        }
    }

    private fun error(id: Any?, message: String): JSONObject =
        JSONObject().put("id", id ?: JSONObject.NULL).put("error", JSONObject().put("message", message))

    private fun ok(id: Any?, result: Any): JSONObject =
        JSONObject().put("id", id ?: JSONObject.NULL).put("result", result)

    /** All methods. Throws with a user-facing message on refusal. */
    private fun dispatch(request: JSONObject): JSONObject {
        val id = request.opt("id")
        val method = request.optString("method")
        val result: Any = when (method) {
            "version" -> JSONObject()
                .put("api", "capcraft/1")
                .put("build", "android")
                .put("capabilities", JSONArray(listOf("projects", "edits", "catalogue", "export", "preview")))
            "capcraft_status" -> status()
            "project.list" -> JSONArray(runCommands { listProjects().map(::summarize) })
            "project.create" -> summarize(runCommands {
                createProject(
                    request.getString("name"),
                    request.optInt("width", 1080).toUInt(),
                    request.optInt("height", 1920).toUInt(),
                    request.optInt("fps", 30).toUInt(),
                )
            })
            "project.open" -> viewOf(runCommands { openSession(request.getString("path")).view() })
            "project.get" -> viewOf(runCommands { openSession(request.getString("path")).view() })
            "project.document" -> JSONObject(openSession(request.getString("path")).document())
            "project.save" -> {
                val path = request.getString("path")
                val s = openSession(path)
                runCommands { s.save() }
                JSONObject().put("saved", true)
            }
            "project.close" -> {
                val path = request.getString("path")
                if (request.optBoolean("save", false)) {
                    held[path]?.let { s -> runCommands { s.save() } }
                }
                held.remove(path)?.let { hub.release(path, it) }
                JSONObject().put("closed", true)
            }
            "project.setVideo" -> viewOf(edited(request.getString("path")) {
                val v = request.getJSONObject("video")
                apply(
                    JSONObject().put("op", "setTimelineVideo")
                        .put("width", v.getInt("width"))
                        .put("height", v.getInt("height"))
                        .put("rateNum", v.optInt("rateNum", 30))
                        .put("rateDen", v.optInt("rateDen", 1)).toString(),
                    null,
                )
            })
            "media.probe" -> runCommands {
                val probe = probeMedia(request.getString("path"))
                JSONObject()
                    .put("path", probe.path)
                    .put("durationUs", probe.durationUs)
                    .put("durationSeconds", probe.durationUs / 1_000_000.0)
                    .put("width", probe.width.toLong()).put("height", probe.height.toLong())
                    .put("fpsNum", probe.fpsNum).put("fpsDen", probe.fpsDen)
                    .put("hasAudio", probe.hasAudio)
                    .put("audioStreams", JSONArray(probe.audioStreams))
                    .put("videoCodec", probe.videoCodec).put("audioCodec", probe.audioCodec)
            }
            "media.import" -> summarizeMedia(runCommands {
                val s = openSession(request.getString("path"))
                s.import(request.getString("file"))
            })
            "media.importSound" -> summarizeMedia(runCommands {
                val s = openSession(request.getString("path"))
                s.importSound(request.getString("file"))
            })
            "edit.apply" -> viewOf(edited(request.getString("path")) {
                val command = request.get("command")
                val json = if (command is String) command else command.toString()
                val gesture = request.optString("gesture").takeIf { it.isNotEmpty() }
                apply(json, gesture)
            })
            // one session method each, so agents need no op tables: mirrors the
            // bridge's edit.* tools one to one.
            "session.addToMain" -> viewOf(edited(request.getString("path")) {
                addToMain(request.getJSONArray("mediaIds").toStringList(), request.optLong("atUs", 0L))
            })
            "session.addOverlay" -> viewOf(edited(request.getString("path")) {
                addOverlay(request.getString("mediaId"), request.getLong("atUs"))
            })
            "session.addAudio" -> viewOf(edited(request.getString("path")) {
                addAudio(request.getString("mediaId"), request.getLong("atUs"))
            })
            "session.addSticker" -> viewOf(edited(request.getString("path")) {
                addSticker(request.getString("mediaId"), request.getLong("atUs"), request.getLong("durationUs"))
            })
            "session.addText" -> viewOf(edited(request.getString("path")) {
                val style = request.get("style")
                addText(request.getLong("atUs"), request.getLong("durationUs"), if (style is String) style else style.toString())
            })
            "session.delete" -> viewOf(edited(request.getString("path")) {
                delete(request.getJSONArray("clipIds").toStringList())
            })
            "session.split" -> viewOf(edited(request.getString("path")) {
                split(request.getJSONArray("clipIds").toStringList(), request.getLong("atUs"))
            })
            "session.trim" -> viewOf(edited(request.getString("path")) {
                trim(request.getString("clipId"), request.getBoolean("head"), request.getLong("deltaUs"))
            })
            "session.moveClip" -> viewOf(edited(request.getString("path")) {
                moveClip(request.getString("clipId"), request.optString("trackId"), request.getLong("startUs"))
            })
            "session.reorderMain" -> viewOf(edited(request.getString("path")) {
                reorderMain(request.getString("clipId"), request.getInt("index").toUInt())
            })
            "session.update" -> viewOf(edited(request.getString("path")) {
                val patch = request.get("patch")
                apply(
                    JSONObject().put("op", "updateClip").put("clipId", request.getString("clipId"))
                        .put("patch", if (patch is String) JSONObject(patch) else patch).toString(),
                    null,
                )
            })
            "edit.undo" -> viewOf(edited(request.getString("path")) { undo() })
            "edit.redo" -> viewOf(edited(request.getString("path")) { redo() })
            "catalogue.list" -> JSONArray(runCommands {
                val list: List<io.github.artcraftandroid.capcraft.core.PackageInfo> =
                    packages(request.optString("kind").takeIf { it.isNotEmpty() } ?: "effect")
                list
                    .map { p ->
                        JSONObject()
                            .put("id", p.id).put("name", p.name).put("kind", p.kind)
                            .put("category", p.category).put("shelves", JSONArray(p.shelves))
                            .put("intensity", p.intensity)
                            .put("params", JSONArray(p.params.map { q ->
                                JSONObject().put("key", q.key).put("label", q.label)
                                    .put("min", q.min).put("max", q.max)
                                    .put("default", q.default).put("step", q.step).put("unit", q.unit)
                            }))
                    }
            })
            "export.start" -> startExportJob(request)
            "export.status" -> exportStatus(request.getString("job"))
            "export.cancel" -> {
                JSONObject().put("cancelled", cancelExportJob(request.getString("job")))
            }
            "preview.frame" -> previewFrame(request)
            else -> throw IllegalArgumentException("Unknown method: $method")
        }
        return ok(id, result)
    }

    /**
     * Engine work on the server's own pool (4 threads). Sessions lock
     * internally and the UI serializes on its own dispatcher, so concurrent
     * agent calls are safe; the pool (not one lane) means a slow first open
     * — the engine warms scrub copies — never wedges version/status behind
     * it. Each call gives up after the socket timeout instead of hanging.
     */
    private fun <T> runCommands(block: () -> T): T {
        val future = lane.submit<T> { block() }
        return try {
            future.get(CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            throw IllegalStateException("CapCraft is still working on that; try again in a bit.")
        }
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

    private fun openSession(path: String): MobileSession =
        held.getOrPut(path) {
            hub.get(path) ?: hub.publishNew(path, runCommands { MobileSession.open(path) })
        }

    /**
     * Runs a mutating edit on the shared session, then hands the fresh
     * view to the open editor (if any), which swaps it in live.
     */
    private fun edited(path: String, op: MobileSession.() -> TimelineView): TimelineView {
        val view = runCommands { openSession(path).op() }
        hub.publish(path, view)
        return view
    }

    private fun viewOf(view: TimelineView): JSONObject =
        JSONObject()
            .put("revision", view.revision.toLong())
            .put("name", view.name)
            .put("width", view.width.toLong())
            .put("height", view.height.toLong())
            .put("fpsNum", view.fpsNum)
            .put("fpsDen", view.fpsDen)
            .put("durationUs", view.durationUs)
            .put("durationSeconds", view.durationUs / 1_000_000.0)
            .put("canUndo", view.canUndo)
            .put("canRedo", view.canRedo)
            .put("createdId", view.createdId)
            .put("rows", JSONArray(view.rows.map { row ->
                JSONObject()
                    .put("trackId", row.trackId)
                    .put("kind", row.kind.name.lowercase())
                    .put("muted", row.muted)
                    .put("visible", row.visible)
                    .put("clips", JSONArray(row.clips.map { clip ->
                        JSONObject()
                            .put("id", clip.id).put("kind", clip.kind).put("name", clip.name)
                            .put("mediaId", clip.mediaId)
                            .put("startUs", clip.startUs).put("durationUs", clip.durationUs)
                            .put("start", clip.startUs / 1_000_000.0)
                            .put("duration", clip.durationUs / 1_000_000.0)
                            .put("sourceStartUs", clip.sourceStartUs)
                            .put("speed", clip.speed).put("volume", clip.volume)
                            .put("audible", clip.audible).put("muted", clip.muted)
                            .put("text", clip.text)
                            .put("effectId", clip.effectId)
                            .put("transitionId", clip.transitionId)
                    }))
            }))

    private fun summarize(p: ProjectSummary): JSONObject =
        JSONObject()
            .put("path", p.path).put("name", p.name)
            .put("width", p.width.toLong()).put("height", p.height.toLong())
            .put("modifiedMs", p.modifiedMs).put("sizeBytes", p.sizeBytes)

    private fun summarizeMedia(m: MediaInfo): JSONObject =
        JSONObject()
            .put("id", m.id).put("kind", m.kind)
            .put("durationUs", m.durationUs).put("durationSeconds", m.durationUs / 1_000_000.0)
            .put("width", m.width.toLong()).put("height", m.height.toLong())
            .put("hasAudio", m.hasAudio)

    private fun status(): JSONObject = JSONObject()
        .put("running", true)
        .put("address", address())
        .put("sessions", hub.size())
        .put("jobs", jobs.size)

    private fun startExportJob(request: JSONObject): JSONObject {
        val ctx = app ?: throw IllegalStateException("The app is not ready.")
        val path = request.getString("path")
        val session = openSession(path)
        val view = runCommands { session.view() }
        if (view.durationUs <= 0) throw IllegalArgumentException("Add media first.")
        // the job keeps the session alive past project.close; freed at its end.
        hub.publishNew(path, session)
        val out = request.getString("output")
        val short = request.optInt("resolution", view.width.coerceAtMost(view.height).toInt().coerceAtLeast(480))
        val fps = request.optInt("fps", 30)
        val wh = runCommands { exportSize(view.width, view.height, short.toUInt()) }
        val jobId = "job${++jobSeq}"
        val job = Job(path, session, out)
        jobs[jobId] = job
        runCommands {
            startExport(
                session,
                ExportSettings(
                    short.toUInt(),
                    fps.toUInt(),
                    request.optInt("bitrateKbps", 0).toUInt(),
                    request.optString("codec") == "h265",
                    out,
                ),
                object : ExportListener {
                    override fun onProgress(fraction: Float) = Unit
                    override fun onDone(path: String) {
                        job.done = path
                    }

                    override fun onFailed(message: String, cancelled: Boolean) {
                        job.error = if (cancelled) "cancelled" else message
                    }
                },
            )
        }
        return JSONObject().put("job", jobId).put("output", out)
            .put("width", wh.getOrNull(0) ?: view.width.toLong())
            .put("height", wh.getOrNull(1) ?: view.height.toLong())
    }

    private fun exportStatus(jobId: String): JSONObject {
        val job = jobs[jobId] ?: throw IllegalArgumentException("Unknown job: $jobId")
        val done = job.done
        val error = job.error
        return when {
            error != null -> {
                // terminal: a second poll would say "unknown job", so answer fully here.
                jobs.remove(jobId)
                hub.release(job.path, job.session)
                JSONObject().put("job", jobId).put("state", if (error == "cancelled") "cancelled" else "failed").put("error", error)
            }
            done != null -> {
                jobs.remove(jobId)
                hub.release(job.path, job.session)
                JSONObject().put("job", jobId).put("state", "finished").put("output", done)
            }
            else -> JSONObject().put("job", jobId).put("state", "running").put("output", job.output)
        }
    }

    private fun cancelExportJob(jobId: String): Boolean {
        val job = jobs[jobId] ?: return false
        runCommands { runCatching { cancelExport() } }
        job.error = job.error ?: "cancelled"
        // the next export.status reports (and frees) the terminal state.
        return true
    }

    private fun previewFrame(request: JSONObject): JSONObject {
        val session = openSession(request.getString("path"))
        val view = runCommands { session.view() }
        val atUs = ((request.optDouble("time", 0.0)) * 1_000_000).toLong().coerceIn(0L, maxOf(view.durationUs - 1, 0L))
        val width = request.optInt("width", 0)
        val height = request.optInt("height", 0)
        val (w, h) = if (width > 0 && height > 0) {
            width to height
        } else {
            val scale = minOf(MAX_FRAME_PX.toDouble() / maxOf(view.width.toDouble(), 1.0), 1.0)
            maxOf((view.width.toDouble() * scale).toInt(), 2) to maxOf((view.height.toDouble() * scale).toInt(), 2)
        }
        val picture = runCommands { session.frame(atUs, w.toUInt(), h.toUInt(), Motion.STILL) }
        val bitmap: Bitmap = picture.intoBitmap(null) ?: throw IllegalStateException("Could not render that frame.")
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        val file = request.optString("output").takeIf { it.isNotEmpty() }
        if (file != null) {
            try {
                java.io.File(file).writeBytes(out.toByteArray())
            } catch (e: Exception) {
                throw IllegalArgumentException("Could not write $file: ${e.message}")
            }
            return JSONObject().put("output", file).put("width", w).put("height", h).put("time", request.optDouble("time", 0.0))
        }
        return JSONObject()
            .put("png", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            .put("width", w).put("height", h).put("time", request.optDouble("time", 0.0))
    }
}
