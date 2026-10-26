package dev.kosend

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CalibreServer(
    private val hostName: String,
    private val libraryUuid: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onStateChanged(state: State)
    }

    sealed class State {
        data class Waiting(val port: Int) : State()
        data class Connected(val deviceName: String) : State()
        data class Error(val message: String) : State()
        data object Stopped : State()
    }

    private enum class Command { SEND_PENDING, EJECT }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val udpSockets = mutableListOf<DatagramSocket>()
    private val threads = mutableListOf<Thread>()
    @Volatile private var session: Session? = null
    @Volatile var port = 0
        private set

    fun start() {
        if (running) return
        running = true
        val ss = openServerSocket() ?: run {
            listener.onStateChanged(State.Error(Messages.NO_PORT))
            running = false
            return
        }
        serverSocket = ss
        port = ss.localPort
        for (p in BROADCAST_PORTS) {
            runCatching {
                val s = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(p)) }
                udpSockets.add(s)
                thread("udp-$p") { discoveryLoop(s) }
            }.onFailure { Log.w(TAG, "UDP port $p unavailable", it) }
        }
        thread("tcp-accept") { acceptLoop(ss) }
        listener.onStateChanged(State.Waiting(port))
    }

    fun stop() {
        running = false
        session?.close()
        runCatching { serverSocket?.close() }
        udpSockets.forEach { runCatching { it.close() } }
        udpSockets.clear()
        threads.forEach { it.interrupt() }
        threads.clear()
        listener.onStateChanged(State.Stopped)
    }

    fun sendPending() { session?.commands?.offer(Command.SEND_PENDING) }

    fun eject() { session?.commands?.offer(Command.EJECT) }

    val isConnected: Boolean get() = session != null

    private fun openServerSocket(): ServerSocket? {
        for (p in 9090..9099) {
            try {
                return ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) }
            } catch (_: IOException) {
            }
        }
        return null
    }

    private fun thread(name: String, body: () -> Unit) {
        val t = Thread(body, name).apply { isDaemon = true }
        threads.add(t)
        t.start()
    }

    private fun discoveryLoop(s: DatagramSocket) {
        val buf = ByteArray(512)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                val reply = "calibre wireless device client (on $hostName);$port,$port".toByteArray()
                s.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
            } catch (e: IOException) {
                if (running) Log.w(TAG, "discovery", e)
            }
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept", e)
                continue
            }

            session?.close()
            val s = Session(socket)
            session = s
            Thread({
                try {
                    s.run()
                } catch (e: Exception) {
                    Log.w(TAG, "sessione terminata", e)
                } finally {
                    s.close()

                    BookStore.resetSending()
                    if (session === s) {
                        session = null
                        if (running) listener.onStateChanged(State.Waiting(port))
                    }
                }
            }, "session").apply { isDaemon = true }.start()
        }
    }

    private inner class Session(private val socket: Socket) {
        val commands = LinkedBlockingQueue<Command>()
        private val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
        private val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        private var accepted: Set<String> = emptySet()
        private var deviceName = "KOReader"
        private var devicePaths: Map<String, String> = emptyMap()

        fun close() {
            runCatching { socket.close() }
        }

        fun run() {
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            handshake()
            listener.onStateChanged(State.Connected(deviceName))
            commands.offer(Command.SEND_PENDING)
            while (running && !socket.isClosed) {
                when (commands.poll(KEEPALIVE_SECONDS, TimeUnit.SECONDS)) {
                    Command.SEND_PENDING -> sendPendingBooks()
                    Command.EJECT -> {
                        send(NOOP, JSONObject().put("ejecting", true))
                        runCatching { receive() }
                        return
                    }
                    null -> {
                        send(NOOP, JSONObject())
                        expectOk()
                    }
                }
            }
        }

        private fun handshake() {
            send(GET_INITIALIZATION_INFO, JSONObject()
                .put("serverProtocolVersion", 1)
                .put("validExtensions", JSONArray(SUPPORTED_EXTENSIONS))
                .put("passwordChallenge", "")
                .put("currentLibraryName", "KoSend")
                .put("currentLibraryUUID", libraryUuid)
                .put("pubdateFormat", "dd MMM yyyy")
                .put("timestampFormat", "dd MMM yyyy")
                .put("lastModifiedFormat", "dd MMM yyyy")
                .put("calibre_version", JSONArray(listOf(7, 0, 0)))
                .put("canSupportUpdateBooks", true)
                .put("canSupportLpathChanges", true))
            val init = expectOk()
            init.optJSONArray("acceptedExtensions")?.let { arr ->
                accepted = (0 until arr.length()).map { arr.getString(it).lowercase() }.toSet()
            }
            deviceName = init.optString("deviceName", deviceName)

            send(GET_DEVICE_INFORMATION, JSONObject())
            val info = expectOk().optJSONObject("device_info") ?: JSONObject()

            send(SET_CALIBRE_DEVICE_INFO, JSONObject()
                .put("device_store_uuid", info.optString("device_store_uuid", UUID.randomUUID().toString()))
                .put("device_name", info.optString("device_name", deviceName))
                .put("location_code", "main")
                .put("last_library_uuid", libraryUuid)
                .put("calibre_version", "7.0.0")
                .put("date_last_connected", isoNow())
                .put("prefix", ""))
            expectOk()

            send(FREE_SPACE, JSONObject())
            expectOk()

            send(SET_LIBRARY_INFO, JSONObject()
                .put("libraryName", "KoSend")
                .put("libraryUuid", libraryUuid)
                .put("fieldMetadata", JSONObject())
                .put("otherInfo", JSONObject()))
            expectOk()

            send(GET_BOOK_COUNT, JSONObject()
                .put("canStream", true)
                .put("canScan", true)
                .put("willUseCachedMetadata", true)
                .put("supportsSync", false))
            val count = expectOk().optInt("count", 0)
            val present = mutableMapOf<String, String>()
            repeat(count) {
                val b = receive().second
                val lpath = b.optString("lpath")
                if (lpath.isNotEmpty()) present[lpath] = b.optString("uuid")
            }
            devicePaths = present
            BookStore.syncDevice(present.entries.associate { (lpath, uuid) -> uuid to lpath })
        }

        private fun sendPendingBooks() {
            var i = 0
            while (running) {
                val book = BookStore.nextPending() ?: break
                val total = i + BookStore.pendingCount()
                if (accepted.isNotEmpty() && book.extension !in accepted) {
                    BookStore.update(book.id, BookStatus.ERROR, Messages.unsupported(book.extension))
                    continue
                }
                val file = File(book.path)
                if (!file.exists()) {
                    BookStore.update(book.id, BookStatus.ERROR, Messages.MISSING_FILE)
                    continue
                }
                BookStore.update(book.id, BookStatus.SENDING)
                val prepared = if (book.edited) BookWriter.write(book, file, BookStore.outgoingDir) else null
                val sent = try {
                    sendBook(book, prepared ?: file, i, total)
                } finally {
                    prepared?.delete()
                }
                if (sent) i++
            }
        }

        private fun sendBook(book: Book, file: File, i: Int, total: Int): Boolean {
            val length = file.length()
            val version = BookWriter.version(book)
            val previous = book.deviceLpath.ifEmpty { null }
            val lpath = if (book.sentPaths.isNotEmpty() && book.sentVersion == version) book.sentPaths.last()
                else BookWriter.freshPath(lpathFor(book), book.sentPaths + devicePaths.filterValues { it != book.id }.keys)
            send(SEND_BOOK, JSONObject()
                .put("lpath", lpath)
                .put("length", length)
                .put("thisBook", i)
                .put("totalBooks", total)
                .put("willStreamBooks", true)
                .put("willStreamBinary", true)
                .put("wantsSendOkToSendbook", true)
                .put("canSupportLpathChanges", true)
                .put("metadata", metadataFor(book, lpath, length)))
            val (op, reply) = receive()
            if (op == ERROR) {
                BookStore.update(book.id, BookStatus.ERROR, reply.optString("message").ifEmpty { Messages.REJECTED })
                return false
            }
            if (op != OK) throw IOException("unexpected reply to SEND_BOOK: $op")

            file.inputStream().use { inp ->
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                var lastPct = -1
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    sent += n
                    val pct = if (length > 0) (sent * 100 / length).toInt() else 100
                    if (pct != lastPct) {
                        lastPct = pct
                        BookStore.update(book.id, BookStatus.SENDING, progress = pct)
                    }
                }
                output.flush()
            }

            send(NOOP, JSONObject())
            expectOk()
            BookStore.recordSent(book.id, lpath, version)
            devicePaths = devicePaths - (previous ?: "") + (lpath to book.id)
            if (previous != null && previous != lpath) runCatching { deleteOnDevice(previous) }
            BookStore.update(book.id, BookStatus.SENT, deviceName)
            return true
        }

        private fun deleteOnDevice(lpath: String) {
            send(DELETE_BOOK, JSONObject().put("lpaths", JSONArray().put(lpath)))
            expectOk()
            val timeout = socket.soTimeout
            socket.soTimeout = 5_000
            try {
                expectOk()
            } finally {
                socket.soTimeout = timeout
            }
        }

        private fun lpathFor(book: Book) = BookWriter.deviceName(book)

        private fun metadataFor(book: Book, lpath: String, length: Long) = JSONObject()
            .put("uuid", book.id)
            .put("lpath", lpath)
            .put("title", book.title)
            .put("authors", JSONArray(book.authors))
            .put("author_sort", book.authors.firstOrNull() ?: "")
            .put("tags", JSONArray(book.details.subjects))
            .put("series", book.details.series.ifBlank { JSONObject.NULL })
            .put("series_index", book.details.seriesIndex.toDoubleOrNull() ?: JSONObject.NULL)
            .put("size", length)
            .put("last_modified", isoNow())

        private fun send(op: Int, payload: JSONObject) {
            val json = JSONArray().put(op).put(payload).toString().toByteArray(Charsets.UTF_8)
            output.write(json.size.toString().toByteArray(Charsets.US_ASCII))
            output.write(json)
            output.flush()
        }

        private fun receive(): Pair<Int, JSONObject> {
            val len = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("connection closed by KOReader")
                if (b == '['.code) break
                if (b in '0'.code..'9'.code) len.append(b.toChar())
            }
            val size = len.toString().toIntOrNull() ?: throw IOException("invalid message length")
            val buf = ByteArray(size)
            buf[0] = '['.code.toByte()
            var off = 1
            while (off < size) {
                val n = input.read(buf, off, size - off)
                if (n < 0) throw EOFException("connection closed by KOReader")
                off += n
            }
            val arr = JSONArray(String(buf, Charsets.UTF_8))
            return arr.getInt(0) to (arr.opt(1) as? JSONObject ?: JSONObject())
        }

        private fun expectOk(): JSONObject {
            val (op, data) = try {
                receive()
            } catch (e: SocketTimeoutException) {
                throw IOException("KOReader is not responding", e)
            }
            if (op != OK) throw IOException("unexpected reply: $op")
            return data
        }
    }

    companion object {
        private const val TAG = "CalibreServer"
        private val BROADCAST_PORTS = intArrayOf(54982, 48123, 39001, 44044, 59678)
        private const val KEEPALIVE_SECONDS = 60L

        private const val OK = 0
        private const val SET_CALIBRE_DEVICE_INFO = 1
        private const val GET_DEVICE_INFORMATION = 3
        private const val FREE_SPACE = 5
        private const val GET_BOOK_COUNT = 6
        private const val SEND_BOOK = 8
        private const val GET_INITIALIZATION_INFO = 9
        private const val NOOP = 12
        private const val DELETE_BOOK = 13
        private const val SET_LIBRARY_INFO = 19
        private const val ERROR = 20

        val SUPPORTED_EXTENSIONS = listOf("epub", "pdf", "mobi", "azw3", "azw", "fb2", "cbz", "djvu", "txt", "rtf", "docx")

        private fun isoNow(): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'+00:00'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())
    }
}
