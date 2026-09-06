package com.skyd.podaura.buildlogic

import com.skyd.podaura.media.MediaTypes
import java.io.File

/** jpackage writes extension defaults; expose candidates without claiming those defaults. */
fun configureWindowsMsiOpenWith(msi: File, temporaryDirectory: File) {
    val script = temporaryDirectory.resolve("windows-open-with.ps1")
    temporaryDirectory.mkdirs()
    checkNotNull(WindowsMsiResources::class.java.getResourceAsStream("/windows-open-with.ps1")).use {
        script.outputStream().use(it::copyTo)
    }
    val process = ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
        "-File", script.absolutePath,
        "-MsiPath", msi.absolutePath,
        "-Extensions", MediaTypes.playableExtensions.joinToString(","),
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) { "Cannot configure MSI Open With registration:\n$output" }
}

private object WindowsMsiResources
