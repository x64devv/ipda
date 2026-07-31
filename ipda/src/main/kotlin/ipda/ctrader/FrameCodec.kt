package ipda.ctrader

import com.xtrader.protocol.proto.commons.ProtoMessage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * cTrader Open API wire framing: each frame is a 4-byte big-endian length
 * followed by a serialized ProtoMessage envelope (payloadType + payload bytes
 * + optional clientMsgId). Same framing both directions.
 */
object FrameCodec {

    /** Sanity cap — a frame larger than this means we've lost sync. */
    const val MAX_FRAME_BYTES: Int = 32 * 1024 * 1024

    fun write(out: OutputStream, message: ProtoMessage) {
        val bytes = message.toByteArray()
        val d = DataOutputStream(out)
        d.writeInt(bytes.size)
        d.write(bytes)
        d.flush()
    }

    /** Blocking read of one frame. Throws EOFException on clean stream end. */
    fun read(input: InputStream): ProtoMessage {
        val d = DataInputStream(input)
        val len = d.readInt()
        if (len < 0 || len > MAX_FRAME_BYTES) {
            throw IllegalStateException("Bad frame length $len — stream out of sync")
        }
        val buf = ByteArray(len)
        d.readFully(buf)
        return ProtoMessage.parseFrom(buf)
    }

    /** Read one frame, or null on clean EOF. */
    fun readOrNull(input: InputStream): ProtoMessage? =
        try {
            read(input)
        } catch (e: EOFException) {
            null
        }
}
