package io.github.artcraftandroid.capcraft.ui.editor

import android.view.KeyEvent as AndroidKey
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type

/**
 * Samsung DeX (and any hardware keyboard + mouse) support for the editor.
 *
 * - [dexShortcuts] handles physical-keyboard shortcuts while the editor has focus. It sits on the
 *   bubble phase ([onKeyEvent]), so a focused text field keeps what it handles (typing, arrows,
 *   Ctrl+C inside it) and only the rest reaches the editor.
 * - A right mouse button press on a clip selects it and opens [ClipMenu], rendered by
 *   [TimelineClipMenu] over the timeline or [PreviewClipMenu] over the preview.
 * - [ShortcutsSheet] lists every shortcut; the top bar's keyboard button opens it, as does Ctrl+/.
 */

/** Keys a focused text field owns: plain typing, arrows, Space, Delete… */
private val TYPING_KEYS = setOf(
    AndroidKey.KEYCODE_SPACE,
    AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_DPAD_RIGHT,
    AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_DPAD_DOWN,
    AndroidKey.KEYCODE_J, AndroidKey.KEYCODE_L, AndroidKey.KEYCODE_K,
    AndroidKey.KEYCODE_MOVE_HOME, AndroidKey.KEYCODE_MOVE_END,
    AndroidKey.KEYCODE_DEL, AndroidKey.KEYCODE_FORWARD_DEL,
    // A-Z, 0-9 and common punctuation type into the field
    AndroidKey.KEYCODE_A, AndroidKey.KEYCODE_B, AndroidKey.KEYCODE_C,
    AndroidKey.KEYCODE_D, AndroidKey.KEYCODE_E, AndroidKey.KEYCODE_F,
    AndroidKey.KEYCODE_G, AndroidKey.KEYCODE_H, AndroidKey.KEYCODE_I,
    AndroidKey.KEYCODE_M, AndroidKey.KEYCODE_N, AndroidKey.KEYCODE_O,
    AndroidKey.KEYCODE_P, AndroidKey.KEYCODE_Q, AndroidKey.KEYCODE_R,
    AndroidKey.KEYCODE_S, AndroidKey.KEYCODE_T, AndroidKey.KEYCODE_U,
    AndroidKey.KEYCODE_V, AndroidKey.KEYCODE_W, AndroidKey.KEYCODE_X,
    AndroidKey.KEYCODE_Z, AndroidKey.KEYCODE_0, AndroidKey.KEYCODE_1,
    AndroidKey.KEYCODE_2, AndroidKey.KEYCODE_3, AndroidKey.KEYCODE_4,
    AndroidKey.KEYCODE_5, AndroidKey.KEYCODE_6, AndroidKey.KEYCODE_7,
    AndroidKey.KEYCODE_8, AndroidKey.KEYCODE_9, AndroidKey.KEYCODE_COMMA,
    AndroidKey.KEYCODE_PERIOD, AndroidKey.KEYCODE_MINUS, AndroidKey.KEYCODE_SLASH,
    AndroidKey.KEYCODE_AT, AndroidKey.KEYCODE_APOSTROPHE,
)

/** Ctrl+letters a focused text field owns (copy/cut/paste/select-all). */
private val CLIPBOARD_KEYS = setOf(
    AndroidKey.KEYCODE_C, AndroidKey.KEYCODE_V, AndroidKey.KEYCODE_X,
    AndroidKey.KEYCODE_A, AndroidKey.KEYCODE_Z, AndroidKey.KEYCODE_Y,
)

/** A right-click menu request: the clip, and where it opened (dp, in the surface that opened it). */
data class ClipMenu(val clipId: String, val xDp: Float, val yDp: Float, val fromPreview: Boolean)

/** One row of the shortcuts cheat sheet. */
private data class DexShortcut(val keys: String, val action: String)

private val DEX_SHORTCUTS = listOf(
    DexShortcut("Space / K", "Play or pause"),
    DexShortcut("← / →", "One frame back / forward (Shift: one second)"),
    DexShortcut("J / L", "One second back / forward"),
    DexShortcut("↑ / ↓", "Previous / next cut"),
    DexShortcut("Home / End", "Start / end of the timeline"),
    DexShortcut("S", "Split at the playhead"),
    DexShortcut("Delete", "Delete the selected clip"),
    DexShortcut("Ctrl+Z / Ctrl+Shift+Z", "Undo / redo (Ctrl+Y also redoes)"),
    DexShortcut("Ctrl+C / Ctrl+V", "Copy / paste the selected clip"),
    DexShortcut("Ctrl+D", "Duplicate the selected clip"),
    DexShortcut("Alt+← / Alt+→", "Nudge the selected clip (Shift: one second)"),
    DexShortcut("Ctrl+= / Ctrl+−", "Zoom the timeline in / out (Ctrl+0 resets)"),
    DexShortcut("M", "Mute / unmute the main track"),
    DexShortcut("F", "Full-screen preview"),
    DexShortcut("Ctrl+F", "Find a tool"),
    DexShortcut("Ctrl+E", "Export"),
    DexShortcut("Ctrl+S", "Save now"),
    DexShortcut("Ctrl+/", "This list"),
    DexShortcut("Esc", "Back / close"),
    DexShortcut("Right-click", "The clip's menu"),
)

/**
 * DeX hardware-keyboard shortcuts for the editor. Must be applied from a @Composable (it focuses
 * the root so keys reach the handler): `Box(Modifier.dexShortcuts(vm))`.
 *
 * Two phases: undo/redo run on the *preview* (capture) phase so they work even when a button or
 * the timeline holds focus — a focused text field keeps its own Ctrl+Z/Y. Everything else runs on
 * the bubble phase ([onKeyEvent]), so a focused text field keeps what it handles (typing, arrows,
 * Ctrl+C inside it) and only the rest reaches the editor. While [EditorViewModel.textEditing] is
 * set, editing keys (Space, arrows, Delete, Ctrl+C/V/X) are left to the field.
 */
@Composable
fun Modifier.dexShortcuts(vm: EditorViewModel): Modifier {
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        try { focus.requestFocus() } catch (_: Exception) { }
    }
    // Panels steal focus (typing, sliders); when one closes, take it back so
    // hardware keys keep working without tapping the timeline first. Guarded
    // on panel null so typing inside an open panel never loses focus mid-key.
    androidx.compose.runtime.LaunchedEffect(vm.panel) {
        if (vm.panel == null && !vm.textEditing) {
            try { focus.requestFocus() } catch (_: Exception) { }
        }
    }
    return this.focusRequester(focus).focusable()
        .onPreviewKeyEvent { handleUndoRedo(vm, it) }
        .onKeyEvent { handleDexKey(vm, it) }
}

/** Capture-phase undo/redo: global even when buttons hold focus, but never stolen from typing. */
private fun handleUndoRedo(vm: EditorViewModel, e: KeyEvent): Boolean {
    val kev = e.nativeKeyEvent
    if (kev.action != android.view.KeyEvent.ACTION_DOWN || kev.repeatCount != 0) return false
    if (vm.textEditing) return false
    val ctrl = kev.isCtrlPressed
    if (!ctrl || kev.isAltPressed) return false
    return when (kev.keyCode) {
        AndroidKey.KEYCODE_Z -> {
            // Ctrl+Shift+Z redoes (Ctrl+Z undoes); Ctrl+Y redoes too
            vm.step(back = !kev.isShiftPressed)
            true
        }
        AndroidKey.KEYCODE_Y -> {
            vm.step(back = false)
            true
        }
        else -> false
    }
}

private fun handleDexKey(vm: EditorViewModel, e: KeyEvent): Boolean {
    val kev = e.nativeKeyEvent
    if (kev.action != android.view.KeyEvent.ACTION_DOWN) return false
    val ctrl = kev.isCtrlPressed
    val shift = kev.isShiftPressed
    val alt = kev.isAltPressed
    val once = kev.repeatCount == 0
    // arrows and J/L repeat while held, so scrubbing keeps up; everything else fires once
    val arrowsOk = kev.keyCode in
        setOf(AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_DPAD_RIGHT, AndroidKey.KEYCODE_J, AndroidKey.KEYCODE_L)
    if (!once && !arrowsOk) return false
    // typing wins for its own keys: letters, arrows, Space, Delete and clipboard go to the field
    if (vm.textEditing && (kev.keyCode in TYPING_KEYS || (ctrl && kev.keyCode in CLIPBOARD_KEYS))) return false
    when (kev.keyCode) {
        AndroidKey.KEYCODE_SPACE -> {
            if (ctrl || alt) return false
            vm.togglePlay()
            return true
        }
        AndroidKey.KEYCODE_K -> {
            if (ctrl || alt) return false
            vm.togglePlay()
            return true
        }
        AndroidKey.KEYCODE_DPAD_LEFT -> {
            if (ctrl) return false
            if (alt) vm.nudgeSelected(if (shift) -1_000_000L else -vm.frameUs()) else
                vm.jumpBy(if (shift) -1_000_000L else -vm.frameUs())
            return true
        }
        AndroidKey.KEYCODE_DPAD_RIGHT -> {
            if (ctrl) return false
            if (alt) vm.nudgeSelected(if (shift) 1_000_000L else vm.frameUs()) else
                vm.jumpBy(if (shift) 1_000_000L else vm.frameUs())
            return true
        }
        AndroidKey.KEYCODE_DPAD_UP -> {
            if (ctrl || alt) return false
            vm.gotoCut(-1)
            return true
        }
        AndroidKey.KEYCODE_DPAD_DOWN -> {
            if (ctrl || alt) return false
            vm.gotoCut(1)
            return true
        }
        AndroidKey.KEYCODE_J -> {
            if (ctrl || alt) return false
            vm.jumpBy(-1_000_000L)
            return true
        }
        AndroidKey.KEYCODE_L -> {
            if (ctrl || alt) return false
            vm.jumpBy(1_000_000L)
            return true
        }
        AndroidKey.KEYCODE_MOVE_HOME -> {
            if (ctrl || alt) return false
            vm.jumpTo(0L)
            return true
        }
        AndroidKey.KEYCODE_MOVE_END -> {
            if (ctrl || alt) return false
            vm.jumpTo(vm.timeline?.durationUs ?: 0L)
            return true
        }
        AndroidKey.KEYCODE_S -> {
            if (alt) return false
            if (ctrl) {
                if (!once) return false
                vm.saveNowAsync()
                return true
            }
            vm.splitAtPlayhead()
            return true
        }
        AndroidKey.KEYCODE_Z -> {
            // handled on the preview phase (see handleUndoRedo); bubble is the fallback
            if (!ctrl || alt || !once || vm.textEditing) return false
            vm.step(back = !shift)
            return true
        }
        AndroidKey.KEYCODE_Y -> {
            if (!ctrl || alt || !once || vm.textEditing) return false
            vm.step(back = false)
            return true
        }
        AndroidKey.KEYCODE_C -> {
            if (!ctrl || alt || !once) return false
            vm.copySelected()
            return true
        }
        AndroidKey.KEYCODE_V -> {
            if (!ctrl || alt || !once) return false
            vm.pasteCopied()
            return true
        }
        AndroidKey.KEYCODE_D -> {
            if (!ctrl || alt || !once) return false
            vm.duplicateSelected()
            return true
        }
        AndroidKey.KEYCODE_DEL, AndroidKey.KEYCODE_FORWARD_DEL -> {
            if (ctrl || alt || !once) return false
            vm.deleteSelected()
            return true
        }
        AndroidKey.KEYCODE_M -> {
            if (ctrl || alt || !once) return false
            vm.toggleMainSound()
            return true
        }
        AndroidKey.KEYCODE_F -> {
            if (alt || !once) return false
            if (ctrl) vm.toolSearch = true else vm.fullscreen = !vm.fullscreen
            return true
        }
        AndroidKey.KEYCODE_E -> {
            if (!ctrl || alt || !once) return false
            if ((vm.timeline?.durationUs ?: 0L) <= 0L) vm.message = "Add media first" else vm.exportSheet = true
            return true
        }
        AndroidKey.KEYCODE_SLASH -> {
            if (!ctrl || alt || !once) return false
            vm.showShortcuts = true
            return true
        }
        AndroidKey.KEYCODE_EQUALS -> {
            if (!ctrl || alt) return false
            vm.zoomTimeline(1.25f)
            return true
        }
        AndroidKey.KEYCODE_MINUS -> {
            if (!ctrl || alt) return false
            vm.zoomTimeline(1f / 1.25f)
            return true
        }
        AndroidKey.KEYCODE_0 -> {
            if (!ctrl || alt || !once) return false
            vm.resetZoom()
            return true
        }
        else -> return false
    }
}

/** The right-click menu over the timeline, at the press's spot. */
@Composable
fun TimelineClipMenu(vm: EditorViewModel) {
    val m = vm.clipMenu
    if (m == null || m.fromPreview) return
    ClipMenuBody(vm, m)
}

/** The right-click menu over the preview, at the press's spot. */
@Composable
fun PreviewClipMenu(vm: EditorViewModel) {
    val m = vm.clipMenu
    if (m == null || !m.fromPreview) return
    ClipMenuBody(vm, m)
}

@Composable
private fun ClipMenuBody(vm: EditorViewModel, m: ClipMenu) {
    val clip = vm.clip(m.clipId)
    DropdownMenu(
        expanded = true,
        onDismissRequest = { vm.clipMenu = null },
        offset = DpOffset(m.xDp.dp, m.yDp.dp),
        modifier = Modifier.background(Palette.Card),
    ) {
        if (clip == null) {
            DropdownMenuItem(text = { Text("Close", style = Type.Body) }, onClick = { vm.clipMenu = null })
            return@DropdownMenu
        }
        val inside = vm.playhead > clip.startUs + 30_000 && vm.playhead < clip.startUs + clip.durationUs - 30_000
        ClipMenuEntry("Split at playhead", enabled = inside) {
            vm.clipMenu = null
            vm.splitAtPlayhead()
        }
        ClipMenuEntry("Bring forward") {
            vm.clipMenu = null
            vm.stackForward()
        }
        ClipMenuEntry("Send backward") {
            vm.clipMenu = null
            vm.stackBackward()
        }
        ClipMenuEntry("Duplicate") {
            vm.clipMenu = null
            vm.duplicateSelected()
        }
        ClipMenuEntry("Copy") {
            vm.clipMenu = null
            vm.copySelected()
        }
        ClipMenuEntry("Delete") {
            vm.clipMenu = null
            vm.deleteSelected()
        }
    }
}

@Composable
private fun ClipMenuEntry(label: String, enabled: Boolean = true, onPick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = Type.Body.copy(color = if (enabled) Palette.Text else Palette.Disabled)) },
        enabled = enabled,
        onClick = onPick,
    )
}

/** Every shortcut, in a card over the editor. */
@Composable
fun ShortcutsSheet(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
    ) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth(0.92f).widthIn(max = 560.dp)
                .clip(RoundedCornerShape(14.dp)).background(Palette.Panel)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 20.dp, vertical = 16.dp).verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Glyph(LucideIcons.Keyboard, Palette.DockText, size = 22.dp)
                Spacer(Modifier.width(10.dp))
                Text("Keyboard shortcuts", style = Type.Title)
                Spacer(Modifier.weight(1f))
                Text(
                    "Done",
                    style = Type.BodyStrong.copy(color = Palette.Text, fontSize = 15.sp),
                    modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("For Samsung DeX and any hardware keyboard and mouse.", style = Type.Small)
            Spacer(Modifier.height(12.dp))
            DEX_SHORTCUTS.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        s.keys,
                        style = Type.BodyStrong.copy(fontSize = 13.sp, color = Palette.DockText),
                        modifier = Modifier.widthIn(min = 168.dp),
                    )
                    Text(s.action, style = Type.Body.copy(fontSize = 13.sp), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
