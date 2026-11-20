package io.github.artcraftandroid.capcraft.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.capcraft.engine.AgentServer

/**
 * Settings → Automation: the agent API for AI agents (DSH-go's agent,
 * scripts, editor plugins on the same machine).
 *
 * A loopback-socket JSON-RPC server, mirroring desktop Concat's automation
 * socket: projects, edits, the effect catalogue, exports and frame previews,
 * driven by the DSH-go plugin and the `capcraft_bridge.py` MCP bridge. Shows
 * the address and token the bridge reads from `automation.json`, with buttons
 * to copy each.
 */
@Composable
fun AutomationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf<String?>(null) }
    val running = remember { AgentServer.isRunning() }
    val address = remember { AgentServer.address() }
    val listing = remember {
        runCatching {
            val file = java.io.File(context.filesDir, "automation.json")
            if (file.isFile) org.json.JSONObject(file.readText()) else null
        }.getOrNull()
    }
    val token = listing?.optString("token").orEmpty()

    Page("Automation", onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                "Let an AI agent drive CapCraft: open projects, import media, edit the timeline, " +
                    "render frame previews and export — the same edits the editor makes, each one undo step.",
                style = ink(14f, color = Home.Muted),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                if (running) "Status: running" else "Status: starting… (restart the app if this stays)",
                style = ink(15f, FontWeight.SemiBold),
            )
            Spacer(Modifier.height(12.dp))
            AutomationField("Address", address.ifBlank { "—" }, "address", clipboard, { copied = it }, copied)
            Spacer(Modifier.height(12.dp))
            AutomationField("Token", if (token.isEmpty()) "—" else token, "token", clipboard, { copied = it }, copied)
            Spacer(Modifier.height(20.dp))
            Text("Setup", style = ink(16f, FontWeight.SemiBold))
            Spacer(Modifier.height(8.dp))
            Text(
                "1. Install the DSH-go plugin local-capcraft (composer capsules, status strip, panel).\n" +
                    "2. Point the capcraft_bridge.py MCP server at this address and token.\n" +
                    "3. Send the agent a task: it calls project.list, media.import, edit.apply, " +
                    "preview.frame and export.start, and reports the output path.\n\n" +
                    "Loopback only: nothing leaves the phone. Any app holding the token can edit " +
                    "projects — re-mint it by clearing the app's files if it leaks.",
                style = ink(13f, color = Home.Muted),
            )
        }
    }
}

@Composable
private fun AutomationField(
    label: String,
    value: String,
    id: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    onCopied: (String) -> Unit,
    copied: String?,
) {
    Text(label, style = ink(13f, color = Home.Muted))
    Spacer(Modifier.height(4.dp))
    Text(
        value,
        style = ink(13f).copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        if (copied == id) "Copied" else "Copy $label",
        style = ink(14f, FontWeight.SemiBold),
        modifier = Modifier.clickable(enabled = value != "—") {
            clipboard.setText(AnnotatedString(value))
            onCopied(id)
        }.padding(vertical = 6.dp),
    )
}
