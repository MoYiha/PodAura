package com.skyd.podaura.ui.window

import io.github.vinceglb.filekit.PlatformFile
import com.skyd.fundation.config.appDirectories
import com.skyd.fundation.util.Platform
import com.skyd.fundation.util.platform
import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.awt.Desktop
import java.io.File
import java.nio.file.Path

/** Register before application initialization so Launch Services cold-start events are buffered. */
internal class DesktopOpenFiles : AutoCloseable {
    private val pending = Channel<List<PlatformFile>>(Channel.UNLIMITED)
    val requests = pending.receiveAsFlow()
    private var instance: DesktopInstance? = null

    fun start(arguments: Array<String>): Boolean {
        if (platform == Platform.Windows) {
            val applicationPath = System.getProperty("jpackage.app-path")?.let(Path::of)
                ?: Path.of(DesktopOpenFiles::class.java.protectionDomain.codeSource.location.toURI())
            val instance = DesktopInstance(
                directory = Path.of(appDirectories.dataDir, "instances"),
                applicationPath = applicationPath,
                onRequest = { paths ->
                    pending.trySend(paths.map { PlatformFile(File(it)) })
                },
                allowForeground = { pid -> ForegroundApi.instance.AllowSetForegroundWindow(pid.toInt()) },
            ).also { this.instance = it }
            // Resolve relative paths in the sending process, whose working directory may differ.
            return instance.startOrForward(desktopFileArguments(arguments))
        }
        register()
        if (arguments.isNotEmpty()) {
            pending.trySend(desktopFileArguments(arguments).map { PlatformFile(File(it)) })
        }
        return true
    }

    fun register() {
        if (!Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
            desktop.setOpenFileHandler { event ->
                if (event.files.isNotEmpty()) {
                    pending.trySend(event.files.map(::PlatformFile))
                }
            }
        }
    }

    override fun close() {
        instance?.close()
        pending.close()
    }

    private interface ForegroundApi : StdCallLibrary {
        fun AllowSetForegroundWindow(processId: Int): Boolean

        companion object {
            val instance: ForegroundApi = Native.load("user32", ForegroundApi::class.java)
        }
    }
}

internal fun desktopFileArguments(arguments: Array<String>): List<String> = arguments
    .filter { it.isNotBlank() }
    .map { File(it).absoluteFile.normalize().path }
