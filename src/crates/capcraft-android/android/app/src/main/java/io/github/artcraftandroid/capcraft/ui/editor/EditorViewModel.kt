package io.github.artcraftandroid.capcraft.ui.editor

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.capcraft.core.ClipView
import io.github.artcraftandroid.capcraft.core.MobileSession
import io.github.artcraftandroid.capcraft.core.RowKind
import io.github.artcraftandroid.capcraft.core.RowView
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.core.clearPlayback
import io.github.artcraftandroid.capcraft.core.isPlaying
import io.github.artcraftandroid.capcraft.core.pause
import io.github.artcraftandroid.capcraft.core.play
import io.github.artcraftandroid.capcraft.core.positionUs
import io.github.artcraftandroid.capcraft.core.seek
import io.github.artcraftandroid.capcraft.core.still
import io.github.artcraftandroid.capcraft.engine.Media
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.toBitmap
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.editor.panels.autoAdjustments
import io.github.artcraftandroid.capcraft.ui.editor.panels.layCollage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Which tools the bottom bar shows. */
sealed interface DockLevel {
    /** Nothing selected: the top level. */
    data object Root : DockLevel
    /** A group of the top level opened (Audio, Text, Effects...). */
    data class Group(val id: String) : DockLevel
}

/** A tool panel over the timeline. */
sealed interface Panel {
    /** Normal speed, or the speed curves. */
    data class Speed(val curve: Boolean = false) : Panel
    data object Volume : Panel
    data object Opacity : Panel
    data object Ratio : Panel
    /** The canvas background: 0 colour, 1 picture, 2 blur. */
    data class Background(val tab: Int = 0) : Panel
    data class Packages(val kind: String, val target: Target) : Panel
    data object Adjust : Panel
    /** The text panel on [clipId] (a new title when null), opening on tab [tab] (3, Styles, by default). */
    data class Text(val clipId: String?, val tab: Int = 3) : Panel
    data class Transition(val clipId: String) : Panel
    data object Fade : Panel
    data object Animation : Panel
    data object Crop : Panel
    data object Stickers : Panel
    data object Voiceover : Panel
    data object SoundFx : Panel
    data object Transcript : Panel
    data object Draw : Panel
    data object Tracking : Panel
    data object Enhance : Panel
    data object Quality : Panel
    data object Captions : Panel
    data class Speech(val clipId: String?) : Panel
    data object RemoveBg : Panel
    data object Chroma : Panel
    data object Blend : Panel
    data object Mask : Panel
    data object Basic : Panel
    data object Sounds : Panel
    data object CameraTracking : Panel
    data object MotionBlur : Panel
    data object BodyEffects : Panel
    data object IsolateVoice : Panel
    data object AutoReframe : Panel
    data object Retouch : Panel
    data object CustomCutout : Panel
    data object ShapeStyle : Panel
    data object CaptionTemplates : Panel
    data object Collage : Panel
    data object Relight : Panel
    data object Lyrics : Panel
    data object Graphs : Panel
    data object Layer : Panel
    /** What an effect on the timeline treats: the main track alone, or every picture. */
    data object EffectObject : Panel
    /** An effect's own settings on the timeline. */
    data object LayerAdjust : Panel
    /** A script typed in, a caption a line, read aloud or not. */
    data object EnterCaptions : Panel
    data object BatchEdit : Panel
    data object Beats : Panel
    data object Stabilize : Panel

    /** What a package picker applies to. */
    enum class Target { Clip, Layer }
}

/** Where the timeline keeps beat marks, by media id. */
private const val BEATS = "capcraftBeats"

/** The app's settings file, and the linkage switch in it. */
private const val SETTINGS = "capcraft-settings"
private const val LINKAGE_KEY = "linkage"

/** Where the timeline keeps its linkage note (the bridge's `LINKAGE`). */
private const val LINKAGE_EXTRA = "capcraftLinkage"

/** The file in a project's folder that marks it a photo project. */
const val PHOTO_MARK = "photo.txt"

class EditorViewModel(app: Application, val path: String) : AndroidViewModel(app) {
    var session: MobileSession? by mutableStateOf(null)
        private set
    var timeline: TimelineView? by mutableStateOf(null)
        private set
    /** The time under the playhead, in microseconds. */
    var playhead by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
        private set
    var selected: String? by mutableStateOf(null)
    var dock: List<DockLevel> by mutableStateOf(listOf(DockLevel.Root))
    /** The second level a selected clip's tool opened in the bottom bar ("speed", "transform"), or none. */
    var clipLevel: String? by mutableStateOf(null)

    /**
     * Linkage: what sits over a main clip follows it along the main track. The switch is the same for
     * every project and kept on the phone; the engine is told at once.
     */
    var linkageOn by mutableStateOf(
        app.getSharedPreferences(SETTINGS, android.content.Context.MODE_PRIVATE).getBoolean(LINKAGE_KEY, true)
            .also { runCatching { io.github.artcraftandroid.capcraft.core.setLinkage(it) } },
    )
        private set

    fun toggleLinkage() {
        val on = !linkageOn
        linkageOn = on
        getApplication<Application>().getSharedPreferences(SETTINGS, android.content.Context.MODE_PRIVATE).edit().putBoolean(LINKAGE_KEY, on).apply()
        runCatching { io.github.artcraftandroid.capcraft.core.setLinkage(on) }
        message = if (on) "Elements follow the main track" else "Elements stay where they are"
    }
    var panel: Panel? by mutableStateOf(null)
    var message: String? by mutableStateOf(null)
    /** A long operation in progress, shown over the editor ("Importing"). */
    var busy: String? by mutableStateOf(null)
    var fullscreen by mutableStateOf(false)
    /** The cover editor is open. */
    var editingCover by mutableStateOf(false)
    /** The time the cover was chosen at (the first frame until one is), kept beside the project. */
    var coverAt by mutableLongStateOf(
        runCatching { java.io.File(path, "cover-time.txt").readText().trim().toLong() }.getOrDefault(0L),
    )
    /** How tall the timeline is drawn: panels cover it and the bottom bar. */
    var timelineHeight by mutableStateOf(EditorMetrics.TimelineHeight)
    /** The timeline's share of the preview+timeline area, 0.2 (mostly preview)
     *  to 0.8 (mostly timeline). Drag the divider between them to resize. */
    var timelineFraction by mutableStateOf(0.4f)
    /** The height the preview, the play bar and the timeline share, under the top bar. */
    var areaHeight by mutableStateOf(androidx.compose.ui.unit.Dp(0f))
    /** While set, a tap on the preview picks a colour (RGB) for it instead of selecting. */
    var pickColor: ((Int) -> Unit)? by mutableStateOf(null)
    /** True while a text field holds focus (typing, search): DeX shortcuts that
     *  clash with typing (Ctrl+C/V/X/Z/Y, arrows, Space, Delete) go to the field. */
    var textEditing by mutableStateOf(false)
    /** The last frame the preview showed, for picking colours from. */
    @Volatile var lastFrame: android.graphics.Bitmap? = null

    private var saveJob: Job? = null

    /** Applies a view the agent API published for this project: the same
     *  session object the editor holds, so the edit is already in. */
    private val agentView: (TimelineView) -> Unit = { v ->
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            if (session == null) return@launch
            val before = timeline
            timeline = v
            if (selected != null && clip(selected!!) == null) selected = null
            message = describeAgentEdit(before, v)
        }
    }

    /** One line on what the agent did, from the clips that came, went or moved. */
    private fun describeAgentEdit(before: TimelineView?, after: TimelineView): String {
        val was = before?.rows?.flatMap { it.clips }.orEmpty().associateBy { it.id }
        val now = after.rows.flatMap { it.clips }.associateBy { it.id }
        val added = (now.keys - was.keys).size
        val removed = (was.keys - now.keys).size
        val moved = now.keys.intersect(was.keys).count { id ->
            val a = was.getValue(id)
            val b = now.getValue(id)
            a.startUs != b.startUs || a.durationUs != b.durationUs
        }
        return when {
            added > 0 && removed == 0 && moved == 0 -> "Agent added ${if (added == 1) "a clip" else "$added clips"}"
            removed > 0 && added == 0 && moved == 0 -> "Agent deleted ${if (removed == 1) "a clip" else "$removed clips"}"
            moved > 0 && added == 0 && removed == 0 -> "Agent edited the timeline"
            else -> "Agent updated the timeline"
        }
    }

    fun open(incoming: List<Uri>, then: String? = null) {
        if (session != null) return
        viewModelScope.launch {
            val opened = withContext(Threads.commands) {
                runCatching {
                    io.github.artcraftandroid.capcraft.engine.SessionHub.get(path)
                        ?: io.github.artcraftandroid.capcraft.engine.SessionHub.publishNew(
                            path, MobileSession.open(path),
                        )
                }
            }
            opened.onFailure { fail(it); return@launch }
            val s = opened.getOrThrow()
            session = s
            timeline = withContext(Threads.commands) { s.view() }
            io.github.artcraftandroid.capcraft.engine.SessionHub.subscribe(path, agentView)
            // a photo project opened again exports a picture, as it did when it was made
            if (java.io.File(path, PHOTO_MARK).exists()) {
                photoTab = true
                exportPhoto = true
                exportGif = false
            }
            when {
                incoming.isEmpty() -> {}
                then == "collage" -> startCollage(incoming)
                then == "autocut" -> startAutoCut(incoming)
                else -> addToMain(incoming, atEnd = true) { openTool(then) }
            }
        }
    }

    /**
     * A collage of [uris] (the first nine): the frame square, the first on the main track and the rest
     * over it from the start, each cropped into a cell of the first layout, with Layout open. All
     * photos, it exports a photo.
     */
    private fun startCollage(uris: List<Uri>) {
        val s = session ?: return
        viewModelScope.launch {
            busy = "Importing"
            val result = withContext(Threads.commands) {
                runCatching {
                    val media = uris.take(9).map { uri -> s.import(Media.importUri(getApplication(), path, uri).absolutePath) }
                    var view = s.addToMain(listOf(media.first().id), 0L)
                    val ids = mutableListOf(view.rows.first { it.kind == RowKind.MAIN }.clips.first().id)
                    // square once the first is in: an empty project takes its first clip's shape
                    view = s.setCanvas(1080u, 1080u)
                    media.drop(1).forEach { m ->
                        view = s.addOverlay(m.id, 0L)
                        ids += view.createdId
                    }
                    s.layCollage(view, ids, 0)
                }
            }
            busy = null
            result.onSuccess { v ->
                timeline = v
                scheduleSave()
                if (v.rows.flatMap { it.clips }.all { it.kind == "image" }) {
                    markPhoto()
                    exportPhoto = true
                    exportGif = false
                }
                panel = Panel.Collage
            }.onFailure { fail(it) }
        }
    }

    /** Marks this project a photo project, for the Projects list's Photo shelf and its icon. */
    private fun markPhoto() {
        photoTab = true
        runCatching { java.io.File(path, PHOTO_MARK).writeText("photo") }
    }

    /**
     * AutoCut: [uris] (the first twenty) cut into a short video - each video's middle two seconds,
     * or a second and a half of a photo, one after another on the main track, a short transition
     * of a different kind into each.
     */
    private fun startAutoCut(uris: List<Uri>) {
        val s = session ?: return
        viewModelScope.launch {
            busy = "Making your video"
            val result = withContext(Threads.commands) {
                runCatching {
                    val ids = uris.take(20).map { uri -> s.import(Media.importUri(getApplication(), path, uri).absolutePath).id }
                    var view = s.addToMain(ids, 0L)
                    val order = view.rows.first { it.kind == RowKind.MAIN }.clips.map { it.id }
                    order.forEachIndexed { i, id ->
                        val clip = view.rows.flatMap { it.clips }.firstOrNull { it.id == id } ?: return@forEachIndexed
                        // a photo's moment, or a video's middle, the lengths alternating so the cuts do not tick
                        val wanted = if (clip.kind == "image") 1_500_000L else if (i % 2 == 0) 2_000_000L else 1_600_000L
                        val spare = clip.durationUs - wanted
                        if (spare > 100_000L) {
                            if (clip.kind != "image") view = s.trim(id, true, spare / 2)
                            val now = view.rows.flatMap { it.clips }.firstOrNull { it.id == id } ?: return@forEachIndexed
                            view = s.trim(id, false, -(now.durationUs - wanted))
                        }
                    }
                    val looks = listOf("concat.dissolve", "concat.zoom-transition", "capcraft.fade-white", "concat.slide", "concat.blur-dissolve", "concat.push")
                    val batch = org.json.JSONArray()
                    view.rows.first { it.kind == RowKind.MAIN }.clips.drop(1).forEachIndexed { i, c ->
                        batch.put(
                            JSONObject().put("op", "updateClip").put("clipId", c.id)
                                .put("patch", JSONObject().put("transitionIn", JSONObject().put("id", looks[i % looks.size]).put("duration", 0.4))),
                        )
                    }
                    if (batch.length() > 0) view = s.applyBatch(batch.toString())
                    view
                }
            }
            busy = null
            result.onSuccess { v ->
                timeline = v
                scheduleSave()
                message = "Your video is ready - change anything you like"
            }.onFailure { fail(it) }
        }
    }

    /** The frame shaped to the photo at the playhead, the longer side at 1920, and the export a picture. */
    private fun photoFrame() {
        markPhoto()
        exportPhoto = true
        exportGif = false
        mainClipAtPlayhead()?.let { c ->
            val w = c.mediaWidth.toInt()
            val h = c.mediaHeight.toInt()
            if (w > 0 && h > 0) {
                // the longer side at 1920, both even, as the engine's frames are
                val k = 1920.0 / maxOf(w, h)
                val fw = ((w * k).toInt() / 2 * 2).coerceAtLeast(2)
                val fh = ((h * k).toInt() / 2 * 2).coerceAtLeast(2)
                edit { setCanvas(fw.toUInt(), fh.toUInt()) }
            }
        }
    }

    /**
     * Smart lighting: the photo's light worked out from it as Auto adjust works it out - its
     * exposure, contrast and ends - and set on its Adjust, its colour left as it is.
     */
    private fun lightUp() {
        val c = mainClipAtPlayhead() ?: return
        selected = c.id
        viewModelScope.launch {
            val found = withContext(Threads.thumbnails) {
                runCatching { still(c.path, c.sourceStartUs, 160u).toBitmap()?.let(::autoAdjustments) }.getOrNull()
            }
            if (found == null) {
                message = "Could not read the photo"
                return@launch
            }
            val light = JSONObject()
            listOf("brightness", "contrast", "highlights", "shadows").forEach { k -> if (found.has(k)) light.put(k, found.get(k)) }
            if (light.length() == 0) {
                message = "The photo is already well lit"
                return@launch
            }
            val chain = clipJson(c.id)?.optJSONArray("videoEffects") ?: org.json.JSONArray()
            val next = org.json.JSONArray()
            var merged = false
            for (i in 0 until chain.length()) {
                val link = JSONObject(chain.getJSONObject(i).toString())
                if (!merged && link.optString("id") == "concat.adjust") {
                    val p = link.optJSONObject("params") ?: JSONObject()
                    light.keys().forEach { p.put(it, light.get(it)) }
                    link.put("params", p)
                    merged = true
                }
                next.put(link)
            }
            if (!merged) next.put(JSONObject().put("id", "concat.adjust").put("params", light))
            patch(c.id, JSONObject().put("videoEffects", next))
            message = "Lighting fixed"
        }
    }

    /** Runs an edit on the engine's thread and shows the result. */
    fun edit(select: ((TimelineView) -> String?)? = null, block: MobileSession.() -> TimelineView) {
        val s = session ?: return
        viewModelScope.launch {
            val result = withContext(Threads.commands) { runCatching { s.block() } }
            result.onSuccess { v ->
                timeline = v
                if (select != null) selected = select(v)
                if (selected != null && clip(selected!!) == null) selected = null
                scheduleSave()
            }.onFailure { fail(it) }
        }
    }

    /** Undoes one step, or redoes one ([back] false), and says which, as the reference does ("Undo: Split"). */
    fun step(back: Boolean) {
        val s = session ?: return
        viewModelScope.launch {
            val result = withContext(Threads.commands) {
                runCatching {
                    val name = if (back) s.undoName() else s.redoName()
                    name to if (back) s.undo() else s.redo()
                }
            }
            result.onSuccess { (name, v) ->
                timeline = v
                if (selected != null && clip(selected!!) == null) selected = null
                scheduleSave()
                message = if (name.isNotEmpty()) (if (back) "Undo: " else "Redo: ") + name
                else if (back) "Nothing to undo" else "Nothing to redo"
            }.onFailure { fail(it) }
        }
    }

    /** A `.cube` table picked on the phone made a filter of the user's own, named after the file; [then] gets its id. */
    fun importLook(uri: Uri, then: (String) -> Unit) {
        viewModelScope.launch {
            busy = "Importing"
            val result = withContext(Threads.commands) {
                runCatching {
                    val app = getApplication<Application>()
                    val name = Media.displayName(app.contentResolver, uri)
                    if (!name.endsWith(".cube", ignoreCase = true)) throw IllegalArgumentException("Choose a .cube file")
                    // copied under its own name, which the look takes; the engine keeps its own copy
                    val file = java.io.File(java.io.File(app.cacheDir, "looks").apply { mkdirs() }, Media.safeName(name))
                    app.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                        ?: throw IllegalStateException("Could not read the file.")
                    try {
                        io.github.artcraftandroid.capcraft.core.importLook(file.absolutePath)
                    } finally {
                        file.delete()
                    }
                }
            }
            busy = null
            result.onSuccess(then).onFailure { fail(it) }
        }
    }

    /** Removes the imported look [id]; clips that use it draw without it. */
    fun removeLook(id: String, then: () -> Unit) {
        viewModelScope.launch {
            val result = withContext(Threads.commands) { runCatching { io.github.artcraftandroid.capcraft.core.removeLook(id) } }
            result.onSuccess { then() }.onFailure { fail(it) }
        }
    }

    /** Copies a font file into the project and adds the families it carries; [then] gets the first. */
    fun importFont(uri: Uri, then: (String) -> Unit) {
        val s = session ?: return
        viewModelScope.launch {
            val result = withContext(Threads.commands) {
                runCatching {
                    val file = Media.importUri(getApplication(), path, uri)
                    s.addFont(file.absolutePath).first().family to s.view()
                }
            }
            result.onSuccess { (family, v) ->
                timeline = v
                scheduleSave()
                then(family)
            }.onFailure { fail(it) }
        }
    }

    /** A shape of the engine's own ([kind] as it names them) at the playhead for three seconds, selected. */
    fun addShape(kind: String, name: String) {
        val s = session ?: return
        val style = org.json.JSONObject().put("kind", kind).put("size", 0.25).put("fill", "#ffffff")
        if (kind == "line" || kind == "arrow") style.put("fill", "").put("stroke", "#ffffff").put("strokeWidth", 0.01)
        edit(select = { it.createdId.ifEmpty { null } }) { addShape(playhead, 3_000_000, style.toString(), name) }
    }

    /** Copies picked files into the project and puts them on the main track. */
    fun addToMain(uris: List<Uri>, atEnd: Boolean = false, after: () -> Unit = {}) {
        val s = session ?: return
        viewModelScope.launch {
            busy = "Importing"
            val result = withContext(Threads.commands) {
                runCatching {
                    val ids = uris.map { uri ->
                        val file = Media.importUri(getApplication(), path, uri)
                        s.import(file.absolutePath).id
                    }
                    val at = if (atEnd) (timeline?.durationUs ?: 0L) else playhead
                    s.addToMain(ids, at)
                }
            }
            busy = null
            result.onSuccess { timeline = it; scheduleSave(); after() }.onFailure { fail(it) }
        }
    }

    /** Opens a tool the home screen's shortcut asked for. */
    private fun openTool(then: String?) {
        when (then) {
            "captions" -> panel = Panel.Captions
            "removebg" -> mainClipAtPlayhead()?.let { selected = it.id; panel = Panel.RemoveBg }
            "speed" -> mainClipAtPlayhead()?.let { selected = it.id; panel = Panel.Speed(curve = false) }
            "stabilize" -> mainClipAtPlayhead()?.let { selected = it.id; panel = Panel.Stabilize }
            "cover" -> editingCover = true
            "ratio" -> panel = Panel.Ratio
            "reverse" -> mainClipAtPlayhead()?.let { selected = it.id; reverse(it) }
            "noise" -> mainClipAtPlayhead()?.let { selected = it.id; toggleCleaner(it, "concat.denoise", "Noise reduction") }
            "enhancevoice" -> mainClipAtPlayhead()?.let { selected = it.id; toggleCleaner(it, "concat.enhance-voice", "Voice enhancement") }
            "extract" -> mainClipAtPlayhead()?.let { extractAudio(it) }
            "enhance" -> mainClipAtPlayhead()?.let { selected = it.id; panel = Panel.Quality }
            "transcript" -> panel = Panel.Transcript
            "soundfx" -> panel = Panel.SoundFx
            "retouch" -> mainClipAtPlayhead()?.let { selected = it.id; panel = Panel.Retouch }
            // a photo to edit: the frame its own shape, and the export a picture
            "photo" -> photoFrame()
            "lighting" -> {
                photoFrame()
                lightUp()
            }
            // a still from a video: the export a picture of the frame under the playhead
            "framecapture" -> {
                photoTab = true
                exportPhoto = true
                exportGif = false
                message = "Stop on the frame you want, then Export"
            }
            "record" -> panel = Panel.Voiceover
        }
    }

    /** Copies a picked file into the project and adds it as an overlay or as sound (a video's sound alone). */
    fun addFile(uri: Uri, asAudio: Boolean) {
        val s = session ?: return
        viewModelScope.launch {
            busy = "Importing"
            val result = withContext(Threads.commands) {
                runCatching {
                    val file = Media.importUri(getApplication(), path, uri)
                    if (asAudio) s.addAudio(s.importSound(file.absolutePath).id, playhead)
                    else s.addOverlay(s.import(file.absolutePath).id, playhead)
                }
            }
            busy = null
            result.onSuccess { v ->
                timeline = v
                selected = v.createdId.ifEmpty { null }
                scheduleSave()
            }.onFailure { fail(it) }
        }
    }

    fun clip(id: String): ClipView? = timeline?.rows?.firstNotNullOfOrNull { row -> row.clips.firstOrNull { it.id == id } }

    fun rowOf(id: String): RowView? = timeline?.rows?.firstOrNull { row -> row.clips.any { it.id == id } }

    val selectedClip: ClipView? get() = selected?.let { clip(it) }

    val selectedRow: RowKind? get() = selected?.let { rowOf(it)?.kind }

    /** The main-track clip under the playhead. */
    fun mainClipAtPlayhead(): ClipView? {
        val main = timeline?.rows?.firstOrNull { it.kind == RowKind.MAIN } ?: return null
        val t = playhead
        return main.clips.firstOrNull { t >= it.startUs && t < it.startUs + it.durationUs }
            ?: main.clips.lastOrNull { t >= it.startUs + it.durationUs }
    }

    /** The clips chosen together in multi-select, which taps on the timeline add and take away; null when not choosing several. */
    var chosen: Set<String>? by mutableStateOf(null)

    fun select(id: String?) {
        if (id != selected) clipLevel = null
        selected = id
        panel = null
        if (id == null && dock.lastOrNull() !is DockLevel.Group) dock = listOf(DockLevel.Root)
    }

    fun togglePlay() {
        if (playing) stop() else start()
    }

    fun start() {
        val end = timeline?.durationUs ?: 0L
        if (end <= 0) return
        val from = if (playhead >= end - 30_000) 0L else playhead
        viewModelScope.launch {
            withContext(Threads.commands) { runCatching { play(from) } }
            playhead = from
            playing = true
        }
    }

    fun stop() {
        playing = false
        viewModelScope.launch(Threads.commands) { runCatching { pause() } }
    }

    /** Follows the player's clock while playing; stops at the end. Called once a frame. */
    fun tick() {
        if (!playing) return
        val t = positionUs()
        val end = timeline?.durationUs ?: 0L
        if (t >= end && end > 0) {
            playhead = end
            stop()
        } else {
            playhead = t
        }
        if (!isPlaying() && playing) {
            // the player stopped by itself (end of sound, an error)
            playing = false
        }
    }

    /** When the playhead was last scrubbed ([android.os.SystemClock.uptimeMillis]): the monitor reads the light scrub copies until it rests. */
    var scrubbedAt = 0L
        private set

    /** Moves the playhead (scrubbing): playback stops, the player follows. */
    fun scrub(to: Long) {
        scrubbedAt = android.os.SystemClock.uptimeMillis()
        if (playing) stop()
        val end = timeline?.durationUs ?: 0L
        playhead = to.coerceIn(0L, end.coerceAtLeast(0L))
        viewModelScope.launch(Threads.commands) { runCatching { seek(playhead) } }
    }

    /** The timeline's zoom level (DeX: Ctrl+=, Ctrl+−, Ctrl+0); watched by the timeline. */
    var timelineZoom by mutableStateOf(1f)

    /** The right-click menu over a clip, or null; the menu that is open clears this on dismiss. */
    var clipMenu by mutableStateOf<ClipMenu?>(null)

    /** The keyboard shortcuts cheat sheet is open over the editor. */
    var showShortcuts by mutableStateOf(false)

    /** Multiplies the timeline zoom by [k], from the DeX shortcuts. */
    fun zoomTimeline(k: Float) {
        timelineZoom = (timelineZoom * k).coerceIn(0.1f, 20f)
    }

    /** Back to the timeline zoom of 1, from the DeX shortcuts. */
    fun resetZoom() {
        timelineZoom = 1f
    }

    /** Moves the playhead by [deltaUs] (a jump: no scrubbing trail), stopping at the ends. */
    fun jumpBy(deltaUs: Long) {
        if (playing) stop()
        val end = timeline?.durationUs ?: 0L
        playhead = (playhead + deltaUs).coerceIn(0L, end.coerceAtLeast(0L))
        scrubbedAt = android.os.SystemClock.uptimeMillis()
        val at = playhead
        viewModelScope.launch(Threads.commands) { runCatching { seek(at) } }
    }

    /** Moves the playhead to [at] (a jump), stopping at the ends. */
    fun jumpTo(at: Long) = jumpBy(at - playhead)

    /** One frame at 30fps, in microseconds. */
    fun frameUs(): Long {
        val num = timeline?.fpsNum ?: 30L
        val den = timeline?.fpsDen ?: 1L
        return if (num > 0) 1_000_000L * den / num else 33_333L
    }

    /** The previous ([direction] < 0) or next ([direction] > 0) cut at the playhead. */
    fun gotoCut(direction: Int) {
        val cuts = timeline?.rows?.flatMap { r ->
            r.clips.flatMap { listOf(it.startUs, it.startUs + it.durationUs) }
        }.orEmpty().distinct().sorted()
        if (cuts.isEmpty()) return
        val next = if (direction < 0) cuts.lastOrNull { it < playhead - 1_000L } else cuts.firstOrNull { it > playhead + 1_000L }
        jumpTo(next ?: if (direction < 0) 0L else (timeline?.durationUs ?: 0L))
    }

    /** Splits the selected clip (or the main clip) at the playhead. */
    fun splitAtPlayhead() {
        val clip = selected?.let(::clip) ?: mainClipAtPlayhead() ?: run {
            message = "Add a clip first"
            return
        }
        val inside = playhead > clip.startUs + 30_000 && playhead < clip.startUs + clip.durationUs - 30_000
        if (inside) edit { split(listOf(clip.id), playhead) } else message = "Move the playhead inside the clip to split it"
    }

    /** Deletes the selected clip. */
    fun deleteSelected() {
        val id = selected ?: run {
            message = "Select a clip first"
            return
        }
        edit { delete(listOf(id)) }
        select(null)
    }

    /** Duplicates the selected clip. */
    fun duplicateSelected() {
        val id = selected ?: run {
            message = "Select a clip first"
            return
        }
        edit(select = { it.createdId.ifEmpty { null } }) { duplicate(id) }
    }

    /** What it takes to re-make a copied clip without its id: text and shapes
     *  carry their whole look, stickers their file; media clips re-resolve by id. */
    private data class CopiedClip(
        val kind: String,
        val durationUs: Long,
        val styleJson: String,
        val mediaId: String,
        val name: String,
        /** Whether the copied clip lived on a text row (a sticker, not footage). */
        val wasTextRow: Boolean,
    )

    private var copied: CopiedClip? = null

    /** Whether the dock can offer Paste right now. */
    val canPaste: Boolean get() = copied != null

    /** Copies the selected clip for pasting: a snapshot, not an id, so later
     *  edits (including agent edits) cannot invalidate it. */
    fun copySelected() {
        val id = selected ?: run {
            message = "Select a clip first"
            return
        }
        val c = clip(id) ?: run {
            message = "That clip is gone"
            return
        }
        val style = clipJson(id)?.optJSONObject("text")?.toString().orEmpty()
        copied = CopiedClip(
            c.kind, c.durationUs, style, c.mediaId, c.text.ifBlank { c.name },
            wasTextRow = rowOf(id)?.kind == io.github.artcraftandroid.capcraft.core.RowKind.TEXT,
        )
        message = "Copied"
    }

    /** Pastes the copied clip at the playhead and selects the new clip. */
    fun pasteCopied() {
        val pasted = copied ?: run {
            message = "Nothing to paste"
            return
        }
        val s = session ?: run {
            message = "Open a project first"
            return
        }
        val at = playhead
        viewModelScope.launch {
            val result = withContext(Threads.commands) {
                runCatching {
                    when (pasted.kind) {
                        // text/shapes carry everything they need: make them fresh
                        "text", "shape" -> s.addText(at, pasted.durationUs, pasted.styleJson)
                        else -> {
                            // stickers re-place their picture; other media duplicates by id,
                            // then the copy is moved to the playhead — one UI action, one message
                            if (pasted.kind == "image" && pasted.wasTextRow) {
                                val mediaId = pasted.mediaId.ifEmpty {
                                    throw IllegalStateException("That sticker's picture is gone")
                                }
                                s.addSticker(mediaId, at, pasted.durationUs)
                            } else {
                                val sourceId = findMediaSourceId(pasted)
                                    ?: throw IllegalStateException("That clip is gone")
                                val duped = s.duplicate(sourceId)
                                val id = duped.createdId.ifEmpty {
                                    throw IllegalStateException("The copy failed")
                                }
                                // place at the playhead: main track reorders, lanes move freely
                                val now = duped.rows.flatMap { it.clips }.firstOrNull { it.id == id }
                                    ?: throw IllegalStateException("The copy vanished")
                                val mainId = duped.rows.firstOrNull { it.kind == io.github.artcraftandroid.capcraft.core.RowKind.MAIN }?.trackId
                                if (now.trackId == mainId) {
                                    val ordered = duped.rows.first { it.kind == io.github.artcraftandroid.capcraft.core.RowKind.MAIN }.clips.sortedBy { it.startUs }
                                    val from = ordered.indexOfFirst { it.id == id }
                                    if (from < 0) throw IllegalStateException("The copy vanished")
                                    var target = ordered.count { it.startUs + it.durationUs / 2 < at }
                                    if (target > from) target -= 1
                                    if (target != from) s.reorderMain(id, target.toUInt()) else duped
                                } else {
                                    s.moveClip(id, "", at)
                                }
                            }
                        }
                    }
                }
            }
            result.onSuccess { v ->
                timeline = v
                // the new clip, not the stale original: what "nothing happened" reports were about
                selected = v.createdId.ifEmpty { selected }
                scheduleSave()
                message = "Pasted"
            }.onFailure {
                fail(it)
            }
        }
    }

    /** A live clip id carrying the copied media (same media, same kind), or null if it's gone. */
    private fun findMediaSourceId(pasted: CopiedClip): String? {
        val rows = timeline?.rows.orEmpty()
        return rows.flatMap { it.clips }.firstOrNull { c ->
            c.kind == pasted.kind && c.mediaId.isNotEmpty() && c.mediaId == pasted.mediaId
        }?.id
    }

    /** Moves the selected clip along the timeline by [deltaUs], as one undo step. */
    fun nudgeSelected(deltaUs: Long) {
        val clip = selected?.let(::clip) ?: run {
            message = "Select a clip first"
            return
        }
        val main = rowOf(clip.id)?.kind == io.github.artcraftandroid.capcraft.core.RowKind.MAIN
        if (!main) {
            edit(select = { clip.id }) { moveClip(clip.id, "", (clip.startUs + deltaUs).coerceAtLeast(0)) }
            return
        }
        // the main track stays in order: a nudge swaps the clip with its neighbour
        val ordered = timeline?.rows?.firstOrNull { it.kind == io.github.artcraftandroid.capcraft.core.RowKind.MAIN }?.clips?.sortedBy { it.startUs }.orEmpty()
        val from = ordered.indexOfFirst { it.id == clip.id }
        val to = from + if (deltaUs > 0) 1 else -1
        if (from < 0 || ordered.getOrNull(to) == null) {
            message = "Nothing to move past"
            return
        }
        edit(select = { clip.id }) { reorderMain(clip.id, to.toUInt()) }
    }

    /** Saves the project now, in the background. */
    fun saveNowAsync() {
        viewModelScope.launch {
            busy = "Saving"
            runCatching { saveNow() }
            busy = null
            message = "Saved"
        }
    }

    /** The export settings (the top bar's chip shows the resolution). */
    var exportResolution: Int? by mutableStateOf(null)
    var exportFps by mutableStateOf(30)
    /** The bitrate chosen, megabits a second, or null for the one suggested for the video. */
    var exportMbps: Int? by mutableStateOf(null)
    /** The export settings sheet is down. */
    var exportSheet by mutableStateOf(false)
    /** The export makes a GIF rather than a video. */
    var exportGif by mutableStateOf(false)
    /** The export a still of the frame under the playhead, as a picture, rather than a video or GIF. */
    var exportPhoto by mutableStateOf(false)
    /** The export sheet offers Photo: in a photo project or one opened to capture a frame, as the reference's video projects offer only video and GIF. */
    var photoTab by mutableStateOf(false)
    /** The sound clip the Sounds page's choice takes the place of, when it was opened by Replace. */
    var replacingSound by mutableStateOf<String?>(null)
    /** A GIF's short side in pixels. */
    var gifSize by mutableStateOf(320)
    /** A GIF's pictures per second. */
    var gifFps by mutableStateOf(10)

    /** Applies one engine command; with [gesture] named, moves under it fold into one undo step. */
    fun command(json: JSONObject, gesture: String? = null) {
        edit { apply(json.toString(), gesture) }
    }

    /** Ends a slider drag: the next move is a new undo step. */
    fun endGesture() {
        val s = session ?: return
        viewModelScope.launch(Threads.commands) { runCatching { s.endGesture() } }
    }

    /** Changes clip properties: [patch] holds the engine's field names (volume, opacity, flipH...). */
    fun patch(clipId: String, patch: JSONObject, gesture: String? = null) {
        command(JSONObject().put("op", "updateClip").put("clipId", clipId).put("patch", patch), gesture)
    }

    private var beatsRead: Pair<ULong?, Map<String, List<Double>>> = null to emptyMap()

    /** Beat marks by media id, as times in the file in seconds; kept on the timeline, so undo covers them. */
    val beats: Map<String, List<Double>>
        get() {
            val revision = timeline?.revision
            if (beatsRead.first == revision && revision != null) return beatsRead.second
            val read = runCatching {
                val json = session?.extra(BEATS)?.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
                json.keys().asSequence().associateWith { id ->
                    json.optJSONArray(id)?.let { a -> (0 until a.length()).map { a.optDouble(it) } }.orEmpty()
                }
            }.getOrDefault(emptyMap())
            beatsRead = revision to read
            return read
        }

    private var captionsRead: Pair<ULong?, Set<String>> = null to emptySet()

    /** The titles that are the timeline's captions, by id: made by Auto captions, Enter captions or a caption file. */
    val captions: Set<String>
        get() {
            val revision = timeline?.revision
            if (captionsRead.first == revision && revision != null) return captionsRead.second
            val read = runCatching {
                val ids = session?.extra("capcraftCaptions")?.takeIf { it.isNotBlank() }?.let { JSONArray(it) } ?: JSONArray()
                (0 until ids.length()).map { ids.optString(it) }.toSet()
            }.getOrDefault(emptySet<String>())
            captionsRead = revision to read
            return read
        }

    /** Where [clip]'s beats fall on the timeline, those inside the clip. */
    fun beatTimes(clip: ClipView): List<Long> {
        val marks = beats[clip.mediaId] ?: return emptyList()
        val speed = clip.speed.takeIf { it > 0 } ?: 1.0
        return marks.map { clip.startUs + ((it * 1e6 - clip.sourceStartUs) / speed).toLong() }
            .filter { it >= clip.startUs && it <= clip.startUs + clip.durationUs }
    }

    /** Every beat on the timeline, for edges to snap to. */
    fun allBeatTimes(): List<Long> = timeline?.rows.orEmpty().flatMap { r -> r.clips.flatMap { beatTimes(it) } }

    /** Sets the beats of the file behind a clip (null or empty takes them off), as one undo step. */
    fun setBeats(mediaId: String, marks: List<Double>?) {
        val all = JSONObject()
        beats.forEach { (id, list) -> if (id != mediaId) all.put(id, org.json.JSONArray(list)) }
        if (!marks.isNullOrEmpty()) all.put(mediaId, org.json.JSONArray(marks.sorted()))
        command(JSONObject().put("op", "setTimelineExtra").put("key", BEATS).put("value", if (all.length() == 0) JSONObject.NULL else all))
    }

    /** Puts the sound package [id] on [clip]'s sound, or takes it off, saying which. */
    fun toggleCleaner(clip: ClipView, id: String, name: String) {
        val j = clipJson(clip.id) ?: return
        val old = j.optJSONArray("filters") ?: org.json.JSONArray()
        val next = org.json.JSONArray()
        var had = false
        for (i in 0 until old.length()) {
            val link = old.getJSONObject(i)
            if (link.optString("id") == id) had = true else next.put(link)
        }
        if (!had) {
            val all = org.json.JSONArray().put(JSONObject().put("id", id).put("params", JSONObject()))
            for (i in 0 until next.length()) all.put(next.getJSONObject(i))
            patch(clip.id, JSONObject().put("filters", all))
        } else {
            patch(clip.id, JSONObject().put("filters", next))
        }
        message = if (had) "$name off" else "$name on"
    }

    /** The clip as the engine holds it. */
    fun clipJson(id: String): JSONObject? = clip(id)?.json?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun toggleMainSound() {
        val main = timeline?.rows?.firstOrNull { it.kind == RowKind.MAIN } ?: return
        command(
            JSONObject().put("op", "setTrackFlag").put("trackId", main.trackId).put("flag", "muted").put("value", !main.muted),
        )
        message = if (main.muted) "Clip audio on" else "Clip audio muted"
    }

    fun switchToOverlay(clip: ClipView) = edit(select = { clip.id }) { toOverlay(clip.id) }

    fun switchToMain(clip: ClipView) = edit(select = { clip.id }) { toMain(clip.id) }

    fun extractAudio(clip: ClipView) = edit(select = { it.createdId.ifEmpty { null } }) { extractAudio(clip.id) }

    /** Whether [clip]'s sound was pulled out onto a track of its own and is still there. */
    fun extracted(clip: ClipView): Boolean {
        val mark = "\"detachedFrom\":\"${clip.id}\""
        return timeline?.rows?.any { row -> row.clips.any { it.json.contains(mark) } } == true
    }

    /** Takes [clip]'s extracted sound back into it, the sound's track clip gone. */
    fun recoverAudio(clip: ClipView) = command(JSONObject().put("op", "reattachAudio").put("clipId", clip.id))

    /** The project's linkage note: the main clips whose followers stay put. */
    private fun linkageNote(): JSONObject =
        runCatching { session?.extra(LINKAGE_EXTRA)?.takeIf { it.isNotBlank() }?.let(::JSONObject) }.getOrNull() ?: JSONObject()

    /** Whether what sits over main clip [clip] stays put when it moves. */
    fun unlinked(clip: ClipView): Boolean {
        val ids = linkageNote().optJSONArray("unlinked") ?: return false
        return (0 until ids.length()).any { ids.optString(it) == clip.id }
    }

    /** Frees what sits over main clip [clip] from it, or ties it back, as one undo step. */
    fun toggleUnlinked(clip: ClipView) {
        val note = linkageNote()
        val ids = note.optJSONArray("unlinked") ?: org.json.JSONArray()
        val next = org.json.JSONArray()
        var had = false
        for (i in 0 until ids.length()) ids.optString(i).let { if (it == clip.id) had = true else next.put(it) }
        if (!had) next.put(clip.id)
        if (next.length() == 0) note.remove("unlinked") else note.put("unlinked", next)
        command(JSONObject().put("op", "setTimelineExtra").put("key", LINKAGE_EXTRA).put("value", if (note.length() == 0) JSONObject.NULL else note))
        message = if (had) "Linked to this clip" else "Unlinked from this clip"
    }

    fun freeze(clip: ClipView) = edit(select = { it.createdId.ifEmpty { clip.id } }) { freeze(clip.id, playhead) }

    fun mirror(clip: ClipView) {
        val flipped = clipJson(clip.id)?.optBoolean("flipH", false) ?: false
        patch(clip.id, JSONObject().put("flipH", !flipped))
    }

    fun rotate(clip: ClipView) {
        val now = clipJson(clip.id)?.optDouble("rotation", 0.0) ?: 0.0
        var next = now + 90.0
        if (next > 180.0) next -= 360.0
        command(JSONObject().put("op", "setClipTransform").put("clipId", clip.id).put("rotation", next))
    }

    fun replace(clip: ClipView, uri: Uri) {
        val s = session ?: return
        viewModelScope.launch {
            busy = "Importing"
            val result = withContext(Threads.commands) {
                runCatching {
                    val file = Media.importUri(getApplication(), path, uri)
                    s.replaceMedia(clip.id, file.absolutePath)
                }
            }
            busy = null
            result.onSuccess { timeline = it; scheduleSave() }.onFailure { fail(it) }
        }
    }

    fun reverse(clip: ClipView) {
        val s = session ?: return
        viewModelScope.launch {
            busy = "Reversing"
            val result = withContext(Threads.commands) { runCatching { s.reverse(clip.id) } }
            busy = null
            result.onSuccess { timeline = it; scheduleSave() }.onFailure { fail(it) }
        }
    }

    /** Adds a title at the playhead, selects it and hands its id to [onCreated]. */
    fun addText(style: JSONObject, durationUs: Long = 3_000_000, onCreated: (String) -> Unit = {}) {
        edit(select = { v -> v.createdId.ifEmpty { null }?.also(onCreated) }) { addText(playhead, durationUs, style.toString()) }
    }

    /** Adds a filter or effect layer at the playhead and selects it. */
    fun addLayer(effectId: String, name: String, durationUs: Long = 3_000_000) {
        edit(select = { it.createdId.ifEmpty { null } }) { addLayer(effectId, name, playhead, durationUs) }
    }

    /** A picked picture copied into the project and used as [clipId]'s background (the whole project's when null). */
    fun setBackgroundImage(uri: Uri, clipId: String?) {
        val s = session ?: return
        viewModelScope.launch {
            val result = withContext(Threads.commands) {
                runCatching {
                    val file = Media.importUri(getApplication(), path, uri)
                    val json = JSONObject().put("kind", "image").put("path", file.absolutePath).toString()
                    if (clipId != null) s.setClipBackground(clipId, json) else s.setBackground(json)
                }
            }
            result.onSuccess { timeline = it; scheduleSave() }.onFailure { fail(it) }
        }
    }

    /** A sticker picture (already in the project's media folder) placed at the playhead and selected. */
    fun addSticker(file: java.io.File) {
        val s = session ?: return
        viewModelScope.launch {
            val result = withContext(Threads.commands) {
                runCatching { s.addSticker(s.import(file.absolutePath).id, playhead, 3_000_000) }
            }
            result.onSuccess { v -> timeline = v; selected = v.createdId.ifEmpty { null }; scheduleSave() }
                .onFailure { message = it.userMessage }
        }
    }

    /** A drawing (a frame-sized picture) placed over the video at the playhead, where it was drawn. */
    fun addDrawing(file: java.io.File) {
        val s = session ?: return
        viewModelScope.launch {
            val result = withContext(Threads.commands) {
                runCatching { s.addOverlay(s.import(file.absolutePath).id, playhead) }
            }
            result.onSuccess { v -> timeline = v; selected = v.createdId.ifEmpty { null }; scheduleSave() }
                .onFailure { message = it.userMessage }
        }
    }

    /** A picture of the user's own, copied into the project and placed as a sticker. */
    fun addStickerFrom(uri: Uri) {
        viewModelScope.launch {
            val file = withContext(Threads.commands) { runCatching { Media.importUri(getApplication(), path, uri) }.getOrNull() }
            if (file == null) message = "Could not add that picture" else addSticker(file)
        }
    }

    /**
     * A finished voice-over recording (or any sound file, its sound alone) placed on an audio row at
     * [atUs]; [then] gets the new clip's id once it is on the timeline.
     */
    fun addRecording(file: java.io.File, atUs: Long, then: ((String) -> Unit)? = null) {
        val s = session ?: return
        viewModelScope.launch {
            val result = withContext(Threads.commands) {
                runCatching { s.addAudio(s.importSound(file.absolutePath).id, atUs) }
            }
            result.onSuccess { v ->
                timeline = v
                scheduleSave()
                v.createdId.takeIf { it.isNotEmpty() }?.let { then?.invoke(it) }
            }.onFailure { fail(it) }
        }
    }

    /** The teleprompter's script, shown over the preview while a voice-over records. */
    var prompter by mutableStateOf("")

    /** The tool finder is open over the editor. */
    var toolSearch by mutableStateOf(false)

    /** A voice-over is being recorded. */
    var recording by mutableStateOf(false)

    /** The transcript heard for this project, kept while it is open; null until one is made. */
    var transcript: List<io.github.artcraftandroid.capcraft.core.TranscriptLine>? by mutableStateOf(null)

    /** Silences the timeline's sound (while recording a voice-over). */
    fun setMuted(muted: Boolean) {
        viewModelScope.launch(Threads.commands) { runCatching { io.github.artcraftandroid.capcraft.core.setMuted(muted) } }
    }

    /** The first main-track picture's frame, 1080 on its short side: the "original" ratio. */
    val originalSize: Pair<Int, Int>?
        get() {
            val first = timeline?.rows?.firstOrNull { it.kind == RowKind.MAIN }?.clips?.firstOrNull() ?: return null
            val w = first.mediaWidth.toInt()
            val h = first.mediaHeight.toInt()
            if (w <= 0 || h <= 0) return null
            val short = minOf(w, h)
            val scale = if (short > 1080) 1080.0 / short else 1.0
            return ((w * scale).toInt() / 2 * 2) to ((h * scale).toInt() / 2 * 2)
        }

    /** Every picture on the main track scaled to fill the frame (cropping), or back to fitting it. */
    fun fillAll(fill: Boolean) {
        val main = timeline?.rows?.firstOrNull { it.kind == RowKind.MAIN } ?: return
        val batch = org.json.JSONArray()
        main.clips.forEach { c -> batch.put(JSONObject().put("op", "setClipTransform").put("clipId", c.id).put("scale", if (fill) 1.0 else 1.0)) }
        edit { applyBatch(batch.toString()) }
    }

    // the captions as SubRip, between being written and the user saying where they go
    private var captionsOut: String? = null

    /**
     * The timeline's captions written as SubRip; [save] then asks where, from a name made as an
     * export's is. A timeline with no captions says so instead.
     */
    fun exportCaptions(save: (String) -> Unit) {
        val s = session ?: return
        viewModelScope.launch {
            val result = withContext(Threads.commands) { runCatching { s.captionsSrt() } }
            result.onSuccess { text ->
                captionsOut = text
                save("CapCraft_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".srt")
            }.onFailure { fail(it) }
        }
    }

    /** Writes the captions [exportCaptions] made to the file the user chose. */
    fun saveCaptions(uri: Uri) {
        val text = captionsOut ?: return
        captionsOut = null
        viewModelScope.launch {
            val result = withContext(Threads.commands) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("Could not write the file.")
                }
            }
            result.onSuccess { message = "Captions saved" }.onFailure { fail(it) }
        }
    }

    /** Reads a SubRip or WebVTT file and places its captions on a text row. */
    fun importCaptions(uri: Uri) {
        val s = session ?: return
        viewModelScope.launch {
            val result = withContext(Threads.commands) {
                runCatching {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not read the file.")
                    // caption files are small; anything big is not one
                    if (bytes.size > 4 shl 20) error("That file is too big to be captions.")
                    s.importCaptions(String(bytes, Charsets.UTF_8))
                }
            }
            result.onSuccess { v ->
                applyView(v)
                message = "Captions added"
            }.onFailure { fail(it) }
        }
    }

    /** Takes a view an engine job (captions, a reading) finished with. */
    fun applyView(view: TimelineView, select: String? = null) {
        timeline = view
        if (select != null) selected = select
        scheduleSave()
    }

    /** Shows a failure and logs it. */
    private fun fail(error: Throwable) {
        android.util.Log.w("CapCraft", "edit failed", error)
        message = error.userMessage
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            withContext(Threads.commands) { runCatching { session?.save() } }
        }
    }

    /** Writes the project now. */
    suspend fun saveNow() {
        saveJob?.cancel()
        withContext(Threads.commands) { runCatching { session?.save() } }
    }

    /** Saves now and lets the player go; a project left with nothing on it is deleted. */
    suspend fun close() {
        saveJob?.cancel()
        val empty = timeline?.rows?.all { it.clips.isEmpty() } ?: false
        val s = session
        session = null
        if (s != null) io.github.artcraftandroid.capcraft.engine.SessionHub.unsubscribe(path, agentView)
        withContext(Threads.commands) {
            runCatching { pause() }
            runCatching { clearPlayback() }
            if (empty) {
                runCatching { io.github.artcraftandroid.capcraft.core.deleteProject(path) }
            } else {
                runCatching { s?.save() }
            }
            if (s != null) io.github.artcraftandroid.capcraft.engine.SessionHub.release(path, s)
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { session?.save() }
        runCatching { clearPlayback() }
        val s = session
        session = null
        if (s != null) {
            io.github.artcraftandroid.capcraft.engine.SessionHub.unsubscribe(path, agentView)
            io.github.artcraftandroid.capcraft.engine.SessionHub.release(path, s)
        }
        s?.destroy()
    }

    class Factory(private val app: Application, private val path: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(app, path) as T
    }
}
