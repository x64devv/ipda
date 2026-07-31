package ipda.ctrader

import com.google.protobuf.ByteString
import com.xtrader.protocol.proto.commons.ProtoMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FrameCodecTest {

    private fun msg(type: Int, payload: String, id: String? = null): ProtoMessage =
        ProtoMessage.newBuilder()
            .setPayloadType(type)
            .setPayload(ByteString.copyFromUtf8(payload))
            .apply { if (id != null) clientMsgId = id }
            .build()

    @Test
    fun `single frame round-trips`() {
        val out = ByteArrayOutputStream()
        val original = msg(2100, "hello", "42")
        FrameCodec.write(out, original)
        val decoded = FrameCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(original, decoded)
    }

    @Test
    fun `multiple frames decode in order from one stream`() {
        val out = ByteArrayOutputStream()
        val messages = (1..5).map { msg(2100 + it, "payload-$it", "$it") }
        messages.forEach { FrameCodec.write(out, it) }

        val input = ByteArrayInputStream(out.toByteArray())
        val decoded = (1..5).map { FrameCodec.read(input) }
        assertEquals(messages, decoded)
        assertNull(FrameCodec.readOrNull(input)) // clean EOF after last frame
    }

    @Test
    fun `oversized length prefix is rejected - lost sync protection`() {
        val bytes = byteArrayOf(0x7F, -1, -1, -1) + ByteArray(10) // length prefix ~2GB
        assertFailsWith<IllegalStateException> {
            FrameCodec.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `empty payload frame round-trips`() {
        val out = ByteArrayOutputStream()
        val original = ProtoMessage.newBuilder().setPayloadType(51).build() // heartbeat-style
        FrameCodec.write(out, original)
        assertEquals(original, FrameCodec.read(ByteArrayInputStream(out.toByteArray())))
    }
}
