package io.github.artcraftandroid.capcraft

import android.app.Application
import android.content.ComponentCallbacks2
import android.system.Os
import io.github.artcraftandroid.capcraft.core.init
import io.github.artcraftandroid.capcraft.core.trimMemory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class CapCraftApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The engine, started once per process on a background thread. */
    val engine: Deferred<Result<Unit>> by lazy {
        scope.async {
            runCatching {
                // Rust's temporary folder and home on Android are not the app's own: point them at
                // it before the library first reads them.
                Os.setenv("TMPDIR", cacheDir.absolutePath, true)
                Os.setenv("HOME", filesDir.absolutePath, true)
                File(cacheDir, "exports").mkdirs()
                init(filesDir.absolutePath, cacheDir.absolutePath)
                NativeContext.start(this@CapCraftApplication)
                // the agent API: a loopback-socket JSON-RPC server for AI agents
                // (DSH-go, scripts) to drive projects without touching the UI.
                runCatching { io.github.artcraftandroid.capcraft.engine.AgentServer.start(this@CapCraftApplication) }
                engineLoaded = true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // the timeline's whole-file strips outlive a project (and the process)
        io.github.artcraftandroid.capcraft.ui.editor.Thumbs.init(this)
    }

    @Volatile
    private var engineLoaded = false

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && engineLoaded) {
            scope.launch { runCatching { trimMemory() } }
        }
    }
}
