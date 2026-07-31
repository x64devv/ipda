package ipda.ctrader

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite

/**
 * Transport seam over the cTrader Open API wire (milestone: live demo loop).
 *
 * Two implementations:
 *  - [OpenApiConnection] — the original blocking request/response loop used by
 *    the sequential fetcher (reads frames inline until the response arrives).
 *  - [LiveConnection] — event-driven: a dedicated reader thread routes
 *    correlated responses to pending request futures and pushes everything
 *    else (spot events, execution events, order errors) to a listener.
 *
 * [CTraderClient] works over either, so the proven auth chain and trendbar
 * fetch path are reused verbatim by the live loop (extend, don't replace).
 */
interface OpenApiTransport : AutoCloseable {
    /** Fire-and-forget send (no response correlation). */
    fun send(payloadType: Int, payload: MessageLite)

    /**
     * Send [payload] and block until the response with [expectedResponseType]
     * arrives (correlated by clientMsgId on the live path). Error payloads
     * raise [OpenApiErrorException]; [overallDeadlineMs] bounds total wait.
     */
    fun request(
        requestType: Int,
        payload: MessageLite,
        expectedResponseType: Int,
        overallDeadlineMs: Long = 60_000,
    ): ByteString
}
