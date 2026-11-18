package nl.diments.requestify.desktop

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
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
import java.util.UUID

/** Whether this start is the one Requestify, or another one is running already. */
sealed interface InstanceStart {
    class First(val instance: RunningInstance) : InstanceStart

    data object AlreadyRunning : InstanceStart
}

/**
 * Makes sure only one Requestify runs per data folder (`gradlew run` and the installed app share it). The first holds
 * an OS lock on `instance.lock` for its whole life (released by the OS when the process dies, so a crash never blocks
 * the next start) and listens on a loopback port, written with a random token to `instance.port`. A later start asks
 * it to show its window and steps aside.
 */
class SingleInstance(private val dir: Path) {
    private val lockFile: Path get() = dir.resolve("instance.lock")
    private val portFile: Path get() = dir.resolve("instance.port")

    /**
     * Becomes the one instance, or finds the running one. [askToShow] (false for Windows' autostart) asks the running
     * one to show its window; [onShowRequested] is what this one does when a later start asks. [waitForLockMs] (the
     * crash restart) waits for an old process to let go of the lock instead of asking it anything.
     */
    fun start(onShowRequested: () -> Unit, askToShow: Boolean = true, waitForLockMs: Long = 0): InstanceStart {
        Files.createDirectories(dir)
        val deadline = System.currentTimeMillis() + waitForLockMs
        while (true) {
            tryLock()?.let { (channel, lock) -> return InstanceStart.First(serve(channel, lock, onShowRequested)) }
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(LOCK_RETRY_MS)
        }
        if (askToShow && waitForLockMs == 0L) askRunningToShow()
        return InstanceStart.AlreadyRunning
    }

    /** The lock when it is free; null while another process (or another start in this one) holds it. */
    private fun tryLock(): Pair<FileChannel, FileLock>? {
        val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (e: IOException) {
            channel.close()
            throw e
        }
        if (lock == null) {
            channel.close()
            return null
        }
        return channel to lock
    }

    private fun serve(channel: FileChannel, lock: FileLock, onShowRequested: () -> Unit): RunningInstance {
        val server = ServerSocket()
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val token = UUID.randomUUID().toString()
        Files.writeString(portFile, "${server.localPort} $token")
        val listener = Thread({
            while (!server.isClosed) {
                try {
                    server.accept().use { socket -> answer(socket, token, onShowRequested) }
                } catch (_: IOException) {
                    // closed, or a caller that went away
                }
            }
        }, "requestify-single-instance").apply { isDaemon = true }
        listener.start()
        return RunningInstance(channel, lock, server, portFile)
    }

    private fun answer(socket: Socket, token: String, onShowRequested: () -> Unit) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val line = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
        val out = PrintWriter(socket.getOutputStream(), true)
        if (line == "show $token") {
            out.println("ok")
            onShowRequested()
        } else {
            out.println("no")
        }
    }

    /** Tells the running one to show its window; it may still be starting, so this tries for a short while. */
    private fun askRunningToShow() {
        repeat(ASK_TRIES) {
            val parts = try {
                Files.readString(portFile).trim().split(" ")
            } catch (_: IOException) {
                emptyList()
            }
            val port = parts.getOrNull(0)?.toIntOrNull()
            val token = parts.getOrNull(1)
            if (port != null && token != null) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), SOCKET_TIMEOUT_MS)
                        socket.soTimeout = SOCKET_TIMEOUT_MS
                        PrintWriter(socket.getOutputStream(), true).println("show $token")
                        if (BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine() == "ok") return
                    }
                } catch (_: IOException) {
                    // not listening yet
                }
            }
            Thread.sleep(ASK_RETRY_MS)
        }
    }

    private companion object {
        const val LOCK_RETRY_MS = 100L
        const val ASK_TRIES = 20
        const val ASK_RETRY_MS = 100L
        const val SOCKET_TIMEOUT_MS = 1_000
    }
}

/** The one running Requestify: holds the lock and answers later starts until [close]. */
class RunningInstance internal constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val portFile: Path,
) : AutoCloseable {
    override fun close() {
        try { server.close() } catch (_: IOException) {}
        try { Files.deleteIfExists(portFile) } catch (_: IOException) {}
        try { lock.release() } catch (_: IOException) {}
        try { channel.close() } catch (_: IOException) {}
    }
}
