package com.chisadin.hudwz.bluetooth

import com.chisadin.hudwz.domain.BluetoothDeviceInfo
import com.chisadin.hudwz.domain.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import android.util.Base64

/**
 * Wi-Fi WebSocket **server** transport.
 *
 * HUD (WazeHUD_ANDROID) opens a TCP server socket on the configured port and
 * waits for WazeMod to connect as a WebSocket client. Each HLP JSON packet
 * arrives as a single WebSocket text frame. Replies (pings, device declarations)
 * are sent as text frames back to the client.
 *
 * Protocol compliance (RFC 6455, subset):
 *  - Performs HTTP Upgrade handshake with SHA-1 / Base64 accept key.
 *  - Handles opcodes: 0x1 text, 0x8 close, 0x9 ping → pong (0xA).
 *  - Client frames are always masked (unmask on read).
 *  - Server frames are not masked (per RFC 6455 §5.1).
 *  - Supports continuation frames (opcode 0x0) for fragmented messages.
 *  - Only accepts client addresses inside private/LAN ranges.
 *
 * This class implements [BluetoothTransport] so it can be used as a drop-in
 * in [com.chisadin.hudwz.service.HudBluetoothService] without any changes
 * to the session / reconnect logic.
 */
class WifiWebSocketTransport(
    private val port: Int = 8765,
    private val path: String = "/hlp",
) : BluetoothTransport {

    override val type = TransportType.WIFI_WEBSOCKET

    private val _status = MutableStateFlow<TransportStatus>(TransportStatus.Idle)
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var clientOut: OutputStream? = null
    private var readerJob: Job? = null

    // ─── connect ─────────────────────────────────────────────────────────────

    override suspend fun connect(device: BluetoothDeviceInfo, timeoutMillis: Long) {
        disconnect()
        _status.value = TransportStatus.Connecting

        val server = withContext(Dispatchers.IO) { ServerSocket(port) }
        server.soTimeout = 0  // block indefinitely waiting for client
        serverSocket = server

        val client: Socket = withContext(Dispatchers.IO) {
            server.accept()
        }

        // LAN-only guard: reject connections from public internet.
        val remoteAddr = client.inetAddress
        if (remoteAddr !is Inet4Address || !LanAddressHelper.isPrivateAddress(remoteAddr)) {
            runCatching { client.close() }
            _status.value = TransportStatus.Failed("Từ chối kết nối từ địa chỉ không phải LAN: ${remoteAddr.hostAddress}")
            throw IllegalStateException("Không chấp nhận kết nối từ internet công cộng")
        }

        // Perform WebSocket handshake.
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val requestHeaders = readHttpRequest(input)
        val wsKey = requestHeaders["sec-websocket-key"]
            ?: throw IllegalStateException("Thiếu Sec-WebSocket-Key trong HTTP request")

        val acceptKey = computeAcceptKey(wsKey)
        val clientProtocol = requestHeaders["sec-websocket-protocol"]
        val protocolHeader = if (clientProtocol?.contains("hlp.v1") == true) {
            "Sec-WebSocket-Protocol: hlp.v1\r\n"
        } else ""

        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $acceptKey\r\n" +
            protocolHeader +
            "\r\n"
        withContext(Dispatchers.IO) { output.write(response.toByteArray(Charsets.US_ASCII)) }

        clientSocket = client
        clientOut = output
        _status.value = TransportStatus.Connected()

        // Start reading frames from client.
        readerJob = scope.launch {
            val fragmentBuffer = StringBuilder()
            try {
                while (isActive) {
                    val frame = readFrame(input) ?: break
                    when (frame.opcode) {
                        0x0 -> {  // Continuation
                            fragmentBuffer.append(String(frame.payload, Charsets.UTF_8))
                            if (frame.fin) {
                                val text = fragmentBuffer.toString()
                                fragmentBuffer.clear()
                                if (text.isNotBlank()) {
                                    _incoming.emit(text.toByteArray(Charsets.UTF_8))
                                }
                            }
                        }
                        0x1 -> {  // Text frame
                            if (frame.fin) {
                                val text = String(frame.payload, Charsets.UTF_8)
                                if (text.isNotBlank()) {
                                    _incoming.emit(text.toByteArray(Charsets.UTF_8))
                                }
                            } else {
                                fragmentBuffer.clear()
                                fragmentBuffer.append(String(frame.payload, Charsets.UTF_8))
                            }
                        }
                        0x8 -> {  // Close frame
                            sendCloseFrame(output)
                            break
                        }
                        0x9 -> {  // Ping — reply with pong
                            sendFrame(output, opcode = 0xA, payload = frame.payload)
                        }
                        // 0xA pong — ignore
                    }
                }
                _status.value = TransportStatus.Disconnected("Waze Mod đã đóng WebSocket")
            } catch (e: Throwable) {
                if (isActive) _status.value = TransportStatus.Disconnected(e.message)
            } finally {
                runCatching { client.close() }
            }
        }
    }

    // ─── write ────────────────────────────────────────────────────────────────

    override suspend fun write(bytes: ByteArray) = writeMutex.withLock {
        val out = clientOut ?: throw IllegalStateException("WebSocket chưa kết nối")
        withContext(Dispatchers.IO) {
            // Append LF if not present (HLP protocol line separator).
            val payload = if (bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) {
                bytes + '\n'.code.toByte()
            } else bytes
            sendFrame(out, opcode = 0x1, payload = payload)
        }
    }

    // ─── disconnect ───────────────────────────────────────────────────────────

    override suspend fun disconnect() {
        val activeReader = readerJob
        readerJob = null
        clientOut?.let { out -> runCatching { sendCloseFrame(out) } }
        clientOut = null
        runCatching { clientSocket?.close() }
        clientSocket = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        if (activeReader != null && activeReader != kotlinx.coroutines.currentCoroutineContext()[Job]) {
            activeReader.cancelAndJoin()
        }
        _status.value = TransportStatus.Idle
    }

    // ─── WebSocket frame helpers ──────────────────────────────────────────────

    private data class WsFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    /**
     * Reads one WebSocket frame from [input]. Returns null on EOF.
     * Client frames are masked (RFC 6455 §5.3); this method unmasks them.
     */
    private fun readFrame(input: InputStream): WsFrame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null

        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var payloadLen = (b1 and 0x7F).toLong()

        if (payloadLen == 126L) {
            val hi = input.read()
            val lo = input.read()
            if (hi < 0 || lo < 0) return null
            payloadLen = ((hi shl 8) or lo).toLong()
        } else if (payloadLen == 127L) {
            var len = 0L
            repeat(8) {
                val b = input.read()
                if (b < 0) return null
                len = (len shl 8) or b.toLong()
            }
            payloadLen = len
        }

        val maskKey = if (masked) {
            ByteArray(4) { input.read().toByte() }
        } else null

        val payload = ByteArray(payloadLen.toInt())
        var offset = 0
        while (offset < payload.size) {
            val read = input.read(payload, offset, payload.size - offset)
            if (read < 0) return null
            offset += read
        }

        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }

        return WsFrame(fin, opcode, payload)
    }

    /**
     * Sends a WebSocket text or control frame to [out]. Server frames are NOT masked.
     */
    private fun sendFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        val len = payload.size
        val header = when {
            len < 126 -> byteArrayOf(
                (0x80 or opcode).toByte(),
                len.toByte(),
            )
            len < 65536 -> byteArrayOf(
                (0x80 or opcode).toByte(),
                126.toByte(),
                (len shr 8).toByte(),
                (len and 0xFF).toByte(),
            )
            else -> {
                val b = ByteArray(10)
                b[0] = (0x80 or opcode).toByte()
                b[1] = 127.toByte()
                for (i in 7 downTo 2) b[i] = (len shr ((7 - i) * 8)).toByte()
                b
            }
        }
        out.write(header)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun sendCloseFrame(out: OutputStream) {
        runCatching { sendFrame(out, opcode = 0x8, payload = byteArrayOf(0x03, 0xE8.toByte())) }
    }

    // ─── HTTP handshake helpers ───────────────────────────────────────────────

    /**
     * Reads HTTP request headers line-by-line. Returns a map of lowercase
     * header names to their values.
     */
    private fun readHttpRequest(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val lineBuffer = StringBuilder()
        var prevChar = 0
        var lineCount = 0

        while (true) {
            val ch = input.read()
            if (ch < 0) break
            if (ch == '\n'.code) {
                val line = lineBuffer.toString().trimEnd('\r', '\n')
                lineBuffer.clear()
                if (lineCount == 0) {
                    // Request line — e.g. "GET /hlp HTTP/1.1"
                    lineCount++
                    continue
                }
                if (line.isEmpty()) break  // End of headers
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
                lineCount++
            } else {
                lineBuffer.append(ch.toChar())
            }
            prevChar = ch
        }
        return headers
    }

    /** Computes the Sec-WebSocket-Accept value (RFC 6455 §4.2.2). */
    private fun computeAcceptKey(clientKey: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = MessageDigest.getInstance("SHA-1")
        val digest = sha1.digest((clientKey + magic).toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }
}