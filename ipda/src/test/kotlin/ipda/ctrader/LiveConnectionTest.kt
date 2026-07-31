package ipda.ctrader

import com.google.protobuf.ByteString
import com.xtrader.protocol.proto.commons.ProtoMessage
import com.xtrader.protocol.openapi.v2.ProtoOAErrorRes
import com.xtrader.protocol.openapi.v2.ProtoOAVersionReq
import com.xtrader.protocol.openapi.v2.ProtoOAVersionRes
import com.xtrader.protocol.openapi.v2.model.ProtoOAPayloadType
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Event-driven connection semantics over in-memory pipes — no network.
 * The "server" side reads frames the connection writes and replies through
 * the paired pipe.
 */
class LiveConnectionTest {

    private val toServer = PipedInputStream(1 shl 16)      // server reads what the client writes
    private val clientOut = PipedOutputStream(toServer)
    private val toClient = PipedInputStream(1 shl 16)      // client reads what the server writes
    private val serverOut = PipedOutputStream(toClient)

    private val conn = LiveConnection(
        socket = null, input = toClient, output = clientOut,
        heartbeatIntervalSeconds = 3600, // out of the way for tests
    )

    private val exec = Executors.newCachedThreadPool()

    @AfterTest
    fun tearDown() {
        conn.close()
        exec.shutdownNow()
    }

    private fun serverReply(payloadType: Int, payload: ByteString, clientMsgId: String?) {
        val b = ProtoMessage.newBuilder().setPayloadType(payloadType).setPayload(payload)
        if (clientMsgId != null) b.clientMsgId = clientMsgId
        FrameCodec.write(serverOut, b.build())
    }

    @Test
    fun `request completes with the correlated response`() {
        val versionRes = ProtoOAVersionRes.newBuilder().setVersion("42").build()
        val future = exec.submit<ByteString> {
            conn.request(
                ProtoOAPayloadType.PROTO_OA_VERSION_REQ_VALUE,
                ProtoOAVersionReq.newBuilder().build(),
                ProtoOAPayloadType.PROTO_OA_VERSION_RES_VALUE,
                overallDeadlineMs = 5_000,
            )
        }
        val sent = FrameCodec.read(toServer) // the request the client wrote
        assertTrue(sent.hasClientMsgId())
        // An unrelated event in between must not satisfy the request…
        serverReply(ProtoOAPayloadType.PROTO_OA_SPOT_EVENT_VALUE, ByteString.EMPTY, clientMsgId = null)
        // …the correlated response does.
        serverReply(ProtoOAPayloadType.PROTO_OA_VERSION_RES_VALUE, versionRes.toByteString(), sent.clientMsgId)
        val payload = future.get(5, TimeUnit.SECONDS)
        assertEquals("42", ProtoOAVersionRes.parseFrom(payload).version)
    }

    @Test
    fun `correlated error response fails the request with the server error`() {
        val future = exec.submit<ByteString> {
            conn.request(
                ProtoOAPayloadType.PROTO_OA_VERSION_REQ_VALUE,
                ProtoOAVersionReq.newBuilder().build(),
                ProtoOAPayloadType.PROTO_OA_VERSION_RES_VALUE,
                overallDeadlineMs = 5_000,
            )
        }
        val sent = FrameCodec.read(toServer)
        val err = ProtoOAErrorRes.newBuilder().setErrorCode("CH_ACCESS_TOKEN_INVALID").setDescription("expired").build()
        serverReply(ProtoOAPayloadType.PROTO_OA_ERROR_RES_VALUE, err.toByteString(), sent.clientMsgId)
        val thrown = assertFailsWith<java.util.concurrent.ExecutionException> { future.get(5, TimeUnit.SECONDS) }
        val cause = thrown.cause
        assertTrue(cause is OpenApiErrorException)
        assertEquals("CH_ACCESS_TOKEN_INVALID", cause.errorCode)
    }

    @Test
    fun `uncorrelated frames are dispatched to the event listener`() {
        val events = LinkedBlockingQueue<Int>()
        conn.eventListener = { payloadType, _ -> events.offer(payloadType) }
        serverReply(ProtoOAPayloadType.PROTO_OA_SPOT_EVENT_VALUE, ByteString.EMPTY, clientMsgId = null)
        serverReply(ProtoOAPayloadType.PROTO_OA_EXECUTION_EVENT_VALUE, ByteString.EMPTY, clientMsgId = null)
        assertEquals(ProtoOAPayloadType.PROTO_OA_SPOT_EVENT_VALUE, events.poll(5, TimeUnit.SECONDS))
        assertEquals(ProtoOAPayloadType.PROTO_OA_EXECUTION_EVENT_VALUE, events.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `stream death fails pending requests and notifies the disconnect listener`() {
        val disconnected = CountDownLatch(1)
        conn.disconnectListener = { disconnected.countDown() }
        val future = exec.submit<ByteString> {
            conn.request(
                ProtoOAPayloadType.PROTO_OA_VERSION_REQ_VALUE,
                ProtoOAVersionReq.newBuilder().build(),
                ProtoOAPayloadType.PROTO_OA_VERSION_RES_VALUE,
                overallDeadlineMs = 10_000,
            )
        }
        FrameCodec.read(toServer) // request is on the wire; now kill the stream
        serverOut.close()
        val thrown = assertFailsWith<java.util.concurrent.ExecutionException> { future.get(5, TimeUnit.SECONDS) }
        assertTrue(thrown.cause is OpenApiErrorException || thrown.cause is Exception)
        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
    }
}
