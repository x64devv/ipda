package ipda.ctrader

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.xtrader.protocol.proto.commons.ProtoErrorRes
import com.xtrader.protocol.proto.commons.ProtoHeartbeatEvent
import com.xtrader.protocol.proto.commons.ProtoMessage
import com.xtrader.protocol.proto.commons.model.ProtoPayloadType
import com.xtrader.protocol.openapi.v2.ProtoOAErrorRes
import com.xtrader.protocol.openapi.v2.model.ProtoOAPayloadType
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Blocking request/response connection to a cTrader Open API proxy over TLS.
 *
 * Endpoints (verified against help.ctrader.com/open-api/proxies-endpoints, 26 Jul 2026):
 *   demo: demo.ctraderapi.com:5035 (protobuf)   live: live.ctraderapi.com:5035
 * Demo and live are fully isolated environments.
 *
 * Design: the fetcher is strictly sequential, so a simple blocking loop is
 * enough — send a request, then read frames until the matching response
 * payload type arrives. Heartbeats are answered inline; unsolicited events are
 * skipped. Error payloads (ERROR_RES / PROTO_OA_ERROR_RES) raise
 * [OpenApiErrorException] with the server's errorCode + description.
 */
class OpenApiConnection private constructor(
    private val socket: SSLSocket,
    private val input: InputStream,
    private val output: OutputStream,
) : OpenApiTransport {

    private val msgId = AtomicLong(1)

    companion object {
        const val DEMO_HOST = "demo.ctraderapi.com"
        const val LIVE_HOST = "live.ctraderapi.com"
        const val PROTOBUF_PORT = 5035

        fun connect(host: String = DEMO_HOST, port: Int = PROTOBUF_PORT, soTimeoutMs: Int = 20_000): OpenApiConnection {
            val socket = SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket
            socket.soTimeout = soTimeoutMs
            socket.startHandshake()
            return OpenApiConnection(socket, socket.inputStream.buffered(), socket.outputStream.buffered())
        }
    }

    override fun send(payloadType: Int, payload: MessageLite) {
        val envelope = ProtoMessage.newBuilder()
            .setPayloadType(payloadType)
            .setPayload(ByteString.copyFrom(payload.toByteArray()))
            .setClientMsgId(msgId.getAndIncrement().toString())
            .build()
        FrameCodec.write(output, envelope)
    }

    /**
     * Send [payload] and block until a frame with [expectedResponseType]
     * arrives. Heartbeats are answered; other event frames are ignored (the
     * fetcher subscribes to nothing). [overallDeadlineMs] bounds total wait.
     */
    override fun request(
        requestType: Int,
        payload: MessageLite,
        expectedResponseType: Int,
        overallDeadlineMs: Long,
    ): ByteString {
        send(requestType, payload)
        val deadline = System.nanoTime() + overallDeadlineMs * 1_000_000
        while (true) {
            if (System.nanoTime() > deadline) {
                throw OpenApiErrorException("TIMEOUT", "No response of type $expectedResponseType within ${overallDeadlineMs}ms")
            }
            val frame = try {
                FrameCodec.read(input)
            } catch (e: SocketTimeoutException) {
                sendHeartbeat() // keep the connection alive, keep waiting
                continue
            }
            when (frame.payloadType) {
                expectedResponseType -> return frame.payload
                ProtoPayloadType.HEARTBEAT_EVENT_VALUE -> sendHeartbeat()
                ProtoPayloadType.ERROR_RES_VALUE -> {
                    val err = ProtoErrorRes.parseFrom(frame.payload)
                    throw OpenApiErrorException(err.errorCode, err.description)
                }
                ProtoOAPayloadType.PROTO_OA_ERROR_RES_VALUE -> {
                    val err = ProtoOAErrorRes.parseFrom(frame.payload)
                    throw OpenApiErrorException(err.errorCode, err.description, if (err.hasRetryAfter()) err.retryAfter else null)
                }
                else -> { /* unsolicited event — not subscribed to anything we care about */ }
            }
        }
    }

    private fun sendHeartbeat() {
        val hb = ProtoHeartbeatEvent.newBuilder().build()
        val envelope = ProtoMessage.newBuilder()
            .setPayloadType(ProtoPayloadType.HEARTBEAT_EVENT_VALUE)
            .setPayload(ByteString.copyFrom(hb.toByteArray()))
            .build()
        FrameCodec.write(output, envelope)
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

class OpenApiErrorException(
    val errorCode: String,
    description: String?,
    val retryAfterSeconds: Long? = null,
) : RuntimeException("cTrader Open API error $errorCode: ${description ?: "(no description)"}")
