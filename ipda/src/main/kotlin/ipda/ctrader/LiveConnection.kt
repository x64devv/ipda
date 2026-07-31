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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Event-driven cTrader Open API connection for the LIVE loop (extends the
 * proven wire layer — [FrameCodec] and the same TLS endpoints as
 * [OpenApiConnection] — rather than replacing it).
 *
 * Threading model:
 *  - One dedicated READER thread parses frames and routes them:
 *      * frames whose clientMsgId matches a pending [request] complete (or
 *        fail, for error payloads) that request's future;
 *      * everything else — spot events, execution events, order errors,
 *        account disconnect/token invalidation, server heartbeats — is
 *        handed to [eventListener] ON THE READER THREAD. Listeners must be
 *        quick and non-blocking (the live session queues events for its own
 *        pump thread).
 *  - Writes are serialized by a lock; any thread may [send]/[request].
 *  - A scheduled HEARTBEAT task sends ProtoHeartbeatEvent every
 *    [heartbeatIntervalSeconds] (the API requires one at least every ~10s of
 *    inactivity to keep the session alive).
 *
 * On stream failure every pending request fails, [disconnectListener] fires
 * exactly once, and the connection is dead — the live session reconnects with
 * backoff and re-authenticates (tokens, subscriptions, reconcile).
 */
class LiveConnection internal constructor(
    private val socket: SSLSocket?,
    private val input: InputStream,
    private val output: OutputStream,
    heartbeatIntervalSeconds: Long = 10,
) : OpenApiTransport {

    private val msgId = AtomicLong(1)
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)
    private val disconnectNotified = AtomicBoolean(false)

    private data class PendingRequest(
        val expectedResponseType: Int,
        val future: CompletableFuture<ByteString>,
    )

    private val pending = ConcurrentHashMap<String, PendingRequest>()

    /** Uncorrelated frames (events) land here, on the reader thread. */
    @Volatile
    var eventListener: (payloadType: Int, payload: ByteString) -> Unit = { _, _ -> }

    /** Fired once when the stream dies (null cause = local close()). */
    @Volatile
    var disconnectListener: (Throwable?) -> Unit = { }

    @Volatile
    private var readerFailure: Throwable? = null

    private val reader = Thread({ readLoop() }, "ctrader-live-reader").apply {
        isDaemon = true
    }

    private val heartbeats = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ctrader-live-heartbeat").apply { isDaemon = true }
    }

    init {
        reader.start()
        heartbeats.scheduleAtFixedRate(
            {
                runCatching { send(ProtoPayloadType.HEARTBEAT_EVENT_VALUE, ProtoHeartbeatEvent.newBuilder().build()) }
            },
            heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS,
        )
    }

    companion object {
        fun connect(
            host: String = OpenApiConnection.DEMO_HOST,
            port: Int = OpenApiConnection.PROTOBUF_PORT,
            heartbeatIntervalSeconds: Long = 10,
        ): LiveConnection {
            val socket = SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket
            // No soTimeout: the reader blocks until a frame or stream death;
            // liveness is maintained by our outbound heartbeats.
            socket.startHandshake()
            return LiveConnection(
                socket, socket.inputStream.buffered(), socket.outputStream.buffered(),
                heartbeatIntervalSeconds,
            )
        }
    }

    val isOpen: Boolean get() = !closed.get() && readerFailure == null

    override fun send(payloadType: Int, payload: MessageLite) {
        sendEnvelope(payloadType, payload, clientMsgId = null)
    }

    override fun request(
        requestType: Int,
        payload: MessageLite,
        expectedResponseType: Int,
        overallDeadlineMs: Long,
    ): ByteString {
        check(isOpen) { "connection is closed" }
        val id = "req-" + msgId.getAndIncrement()
        val p = PendingRequest(expectedResponseType, CompletableFuture())
        pending[id] = p
        try {
            sendEnvelope(requestType, payload, id)
            return p.future.get(overallDeadlineMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw OpenApiErrorException("TIMEOUT", "No response of type $expectedResponseType within ${overallDeadlineMs}ms")
        } catch (e: ExecutionException) {
            throw (e.cause as? OpenApiErrorException)
                ?: OpenApiErrorException("TRANSPORT", e.cause?.message ?: "request failed")
        } finally {
            pending.remove(id)
        }
    }

    private fun sendEnvelope(payloadType: Int, payload: MessageLite, clientMsgId: String?) {
        val b = ProtoMessage.newBuilder()
            .setPayloadType(payloadType)
            .setPayload(ByteString.copyFrom(payload.toByteArray()))
        if (clientMsgId != null) b.clientMsgId = clientMsgId
        val envelope = b.build()
        synchronized(writeLock) {
            FrameCodec.write(output, envelope)
        }
    }

    private fun readLoop() {
        while (!closed.get()) {
            val frame = try {
                FrameCodec.read(input)
            } catch (e: Exception) {
                if (!closed.get()) failAll(e) else failAll(null)
                return
            }
            route(frame)
        }
        failAll(null)
    }

    private fun route(frame: ProtoMessage) {
        val correlated = if (frame.hasClientMsgId() && frame.clientMsgId.isNotEmpty()) {
            pending[frame.clientMsgId]
        } else null

        if (correlated != null) {
            when (frame.payloadType) {
                correlated.expectedResponseType -> {
                    pending.remove(frame.clientMsgId)
                    correlated.future.complete(frame.payload)
                    return
                }
                ProtoPayloadType.ERROR_RES_VALUE -> {
                    pending.remove(frame.clientMsgId)
                    val err = ProtoErrorRes.parseFrom(frame.payload)
                    correlated.future.completeExceptionally(OpenApiErrorException(err.errorCode, err.description))
                    return
                }
                ProtoOAPayloadType.PROTO_OA_ERROR_RES_VALUE -> {
                    pending.remove(frame.clientMsgId)
                    val err = ProtoOAErrorRes.parseFrom(frame.payload)
                    correlated.future.completeExceptionally(
                        OpenApiErrorException(err.errorCode, err.description, if (err.hasRetryAfter()) err.retryAfter else null)
                    )
                    return
                }
                // Correlated but a different type (e.g. an execution event
                // echoing our clientMsgId before the formal response): fall
                // through to event dispatch, keep the request pending.
            }
        }

        when (frame.payloadType) {
            ProtoPayloadType.HEARTBEAT_EVENT_VALUE -> { /* server liveness ping — our own timer answers */ }
            else -> runCatching { eventListener(frame.payloadType, frame.payload) }
        }
    }

    private fun failAll(cause: Throwable?) {
        readerFailure = cause ?: readerFailure
        val e = cause ?: OpenApiErrorException("DISCONNECTED", "connection closed")
        for ((id, p) in pending) {
            pending.remove(id)
            p.future.completeExceptionally(e)
        }
        if (disconnectNotified.compareAndSet(false, true)) {
            runCatching { disconnectListener(cause) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        heartbeats.shutdownNow()
        runCatching { socket?.close() }
        runCatching { input.close() }
        runCatching { output.close() }
    }
}
