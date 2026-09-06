package com.skyd.podaura.ui.window

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** A per-user, per-application-copy lock with authenticated loopback activation forwarding. */
internal class DesktopInstance(
    directory: Path,
    applicationPath: Path,
    private val onRequest: (List<String>) -> Unit,
    private val allowForeground: (Long) -> Unit = {},
) : AutoCloseable {
    private val key = MessageDigest.getInstance("SHA-256")
        .digest(applicationPath.toRealPath().toString().lowercase(java.util.Locale.ROOT).toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val lockPath = directory.resolve("$key.lock")
    private val endpointPath = directory.resolve("$key.properties")
    private val closed = AtomicBoolean()
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null
    private var listener: Thread? = null

    init {
        Files.createDirectories(directory)
    }

    /** Returns false only after the running instance has acknowledged the complete request. */
    fun startOrForward(arguments: List<String>): Boolean {
        check(channel == null && !closed.get())
        require(arguments.size <= MAX_FILES)
        val fileChannel = FileChannel.open(
            lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
        ).also { channel = it }
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            lock = try {
                fileChannel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock != null) {
                try {
                    listen(arguments)
                    return true
                } catch (exception: Exception) {
                    close()
                    throw exception
                }
            }
            if (forward(arguments)) return false
            // The owner may still be publishing its endpoint, or may be exiting.
            Thread.sleep(100)
        } while (System.nanoTime() < deadline)
        close()
        error("The running PodAura instance did not respond to the open request.")
    }

    private fun listen(arguments: List<String>) {
        val socket = ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        }.also { server = it }
        val token = UUID.randomUUID().toString()
        // Queue the original launch before accepting later launches, even during UI startup.
        onRequest(arguments)
        listener = thread(name = "PodAura activation", isDaemon = true) {
            while (!closed.get()) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 2_000
                        val input = DataInputStream(client.getInputStream())
                        if (input.readUTF() != token) return@use
                        val count = input.readInt()
                        if (count !in 0..MAX_FILES) return@use
                        var totalLength = 0
                        val files = List(count) {
                            input.readUTF().also {
                                totalLength += it.length
                                if (totalLength > MAX_REQUEST_LENGTH) throw IOException("Request too large")
                            }
                        }
                        onRequest(files)
                        DataOutputStream(client.getOutputStream()).apply {
                            writeBoolean(true)
                            flush()
                        }
                    }
                } catch (_: IOException) {
                    // A disconnected or malformed client must not disable subsequent launches.
                }
            }
        }
        Files.newOutputStream(endpointPath).use { output ->
            Properties().apply {
                setProperty("port", socket.localPort.toString())
                setProperty("pid", ProcessHandle.current().pid().toString())
                setProperty("token", token)
            }.store(output, null)
        }
    }

    private fun forward(arguments: List<String>): Boolean = try {
        val endpoint = Properties().apply {
            Files.newInputStream(endpointPath).use(::load)
        }
        val port = endpoint.getProperty("port")?.toIntOrNull()
        val pid = endpoint.getProperty("pid")?.toLongOrNull()
        val token = endpoint.getProperty("token")
        if (port == null || port !in 1..65535 || pid == null || token == null) {
            false
        } else {
            Socket().use { client ->
                client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
                client.soTimeout = 2_000
                runCatching { allowForeground(pid) }
                DataOutputStream(client.getOutputStream()).apply {
                    writeUTF(token)
                    writeInt(arguments.size)
                    arguments.forEach(::writeUTF)
                    flush()
                }
                DataInputStream(client.getInputStream()).readBoolean()
            }
        }
    } catch (_: IOException) {
        false
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            server?.close()
            listener?.join(2_500)
            if (lock != null) Files.deleteIfExists(endpointPath)
        } finally {
            try {
                lock?.release()
            } finally {
                channel?.close()
            }
        }
        // Keep the lock file: deleting it permits two owners to lock different file objects.
    }

    private companion object {
        const val MAX_FILES = 1_024
        const val MAX_REQUEST_LENGTH = 1_048_576
    }
}
