package io.github.some_example_name.lwjgl3.mcp

import com.badlogic.gdx.Gdx
import io.github.some_example_name.lwjgl3.Lwjgl3Launcher
import java.io.*
import java.net.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.concurrent.thread

/** Закрытый канал между stdio-процессом клиента и окном этой установки игры. */
object GameBridge {
    private const val MAX_FRAME = 8 * 1024 * 1024
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val directory by lazy {
        val location = Lwjgl3Launcher::class.java.protectionDomain.codeSource.location.toURI().normalize().toString()
        val key = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(location.toByteArray())).take(24)
        Path.of(System.getProperty("user.home"), ".genomeia", "mcp", key)
    }
    private val descriptor get() = directory.resolve("session.json")

    @JvmStatic fun start() {
        try {
            Files.createDirectories(directory)
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            val channel = FileChannel.open(directory.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            if (channel.tryLock() == null) {
                channel.close()
                Gdx.app.error("MCP", "Another game window already owns the MCP connection")
                return
            }
            val server = ServerSocket(0, 4, loopback)
            val token = UUID.randomUUID().toString()
            val temp = Files.createTempFile(directory, "session-", ".tmp")
            Files.writeString(temp, obj("port" to server.localPort, "token" to token).toString())
            Files.move(temp, descriptor, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Runtime.getRuntime().addShutdownHook(Thread {
                server.close()
                Files.deleteIfExists(descriptor)
                channel.close()
            })
            thread(isDaemon = true, name = "Genomeia-MCP") {
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (_: SocketException) { break }
                    runCatching {
                        client.use {
                            it.soTimeout = 3000
                            val input = DataInputStream(it.getInputStream().buffered())
                            require(input.readUTF() == token)
                            val request = Json.parseToJsonElement(input.readFrame()).jsonObject
                            val task = FutureTask {
                                try { GenomeTools.call(request.string("name"), request["arguments"]?.jsonObject ?: obj()) }
                                catch (e: Exception) { failure(e.message ?: e.javaClass.simpleName) }
                            }
                            Gdx.app.postRunnable(task)
                            val reply = try { task.get(30, TimeUnit.SECONDS) } catch (_: java.util.concurrent.TimeoutException) {
                                task.cancel(false)
                                failure("Game did not respond in time. Read the state before retrying.")
                            }
                            DataOutputStream(it.getOutputStream()).writeFrame(reply.toString())
                        }
                    }
                }
            }
            Gdx.app.log("MCP", "Ready for local stdio clients")
        } catch (e: Exception) {
            Gdx.app.error("MCP", "Could not enable MCP", e)
        }
    }

    internal fun call(request: JsonObject): JsonObject {
        val session = try { Json.parseToJsonElement(Files.readString(descriptor)).jsonObject }
            catch (_: IOException) { error("Start this installation of Genomeia with --enable-mcp first.") }
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(loopback, session.getValue("port").jsonPrimitive.int), 2000)
            socket.soTimeout = 35000
            val output = DataOutputStream(socket.getOutputStream().buffered())
            output.writeUTF(session.string("token"))
            output.writeFrame(request.toString())
            Json.parseToJsonElement(DataInputStream(socket.getInputStream().buffered()).readFrame()).jsonObject
        }
    }

    private fun DataInputStream.readFrame(): String {
        val length = readInt()
        require(length in 1..MAX_FRAME) { "Frame too large" }
        return ByteArray(length).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.writeFrame(message: String) {
        val data = message.toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_FRAME) { "Response too large, use a smaller page" }
        writeInt(data.size)
        write(data)
        flush()
    }
}
