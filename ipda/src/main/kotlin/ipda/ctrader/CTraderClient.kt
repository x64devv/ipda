package ipda.ctrader

import com.xtrader.protocol.openapi.v2.ProtoOAAccountAuthReq
import com.xtrader.protocol.openapi.v2.ProtoOAAccountAuthRes
import com.xtrader.protocol.openapi.v2.ProtoOAApplicationAuthReq
import com.xtrader.protocol.openapi.v2.ProtoOAApplicationAuthRes
import com.xtrader.protocol.openapi.v2.ProtoOAGetAccountListByAccessTokenReq
import com.xtrader.protocol.openapi.v2.ProtoOAGetAccountListByAccessTokenRes
import com.xtrader.protocol.openapi.v2.ProtoOAGetTrendbarsReq
import com.xtrader.protocol.openapi.v2.ProtoOAGetTrendbarsRes
import com.xtrader.protocol.openapi.v2.ProtoOARefreshTokenReq
import com.xtrader.protocol.openapi.v2.ProtoOARefreshTokenRes
import com.xtrader.protocol.openapi.v2.ProtoOAReconcileReq
import com.xtrader.protocol.openapi.v2.ProtoOAReconcileRes
import com.xtrader.protocol.openapi.v2.ProtoOASubscribeLiveTrendbarReq
import com.xtrader.protocol.openapi.v2.ProtoOASubscribeLiveTrendbarRes
import com.xtrader.protocol.openapi.v2.ProtoOASubscribeSpotsReq
import com.xtrader.protocol.openapi.v2.ProtoOASubscribeSpotsRes
import com.xtrader.protocol.openapi.v2.ProtoOASymbolsListReq
import com.xtrader.protocol.openapi.v2.ProtoOASymbolsListRes
import com.xtrader.protocol.openapi.v2.model.ProtoOACtidTraderAccount
import com.xtrader.protocol.openapi.v2.model.ProtoOAPayloadType.*
import ipda.model.Timeframe
import java.time.Instant

/**
 * High-level, sequential cTrader Open API client for the data-fetch milestone.
 * One connection, one account session; every call is blocking.
 */
class CTraderClient(private val conn: OpenApiTransport) : AutoCloseable {

    fun applicationAuth(clientId: String, clientSecret: String) {
        val req = ProtoOAApplicationAuthReq.newBuilder()
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .build()
        ProtoOAApplicationAuthRes.parseFrom(
            conn.request(PROTO_OA_APPLICATION_AUTH_REQ_VALUE, req, PROTO_OA_APPLICATION_AUTH_RES_VALUE)
        )
    }

    fun accountsByToken(accessToken: String): List<ProtoOACtidTraderAccount> {
        val req = ProtoOAGetAccountListByAccessTokenReq.newBuilder()
            .setAccessToken(accessToken)
            .build()
        val res = ProtoOAGetAccountListByAccessTokenRes.parseFrom(
            conn.request(PROTO_OA_GET_ACCOUNTS_BY_ACCESS_TOKEN_REQ_VALUE, req, PROTO_OA_GET_ACCOUNTS_BY_ACCESS_TOKEN_RES_VALUE)
        )
        return res.ctidTraderAccountList
    }

    fun accountAuth(ctidTraderAccountId: Long, accessToken: String) {
        val req = ProtoOAAccountAuthReq.newBuilder()
            .setCtidTraderAccountId(ctidTraderAccountId)
            .setAccessToken(accessToken)
            .build()
        ProtoOAAccountAuthRes.parseFrom(
            conn.request(PROTO_OA_ACCOUNT_AUTH_REQ_VALUE, req, PROTO_OA_ACCOUNT_AUTH_RES_VALUE)
        )
    }

    /**
     * Symbol name → id, names normalized by stripping '/' and uppercasing
     * ("EUR/USD" and "EURUSD" both map to "EURUSD" — broker-dependent naming).
     */
    fun symbolIdsByName(ctidTraderAccountId: Long): Map<String, Long> {
        val req = ProtoOASymbolsListReq.newBuilder()
            .setCtidTraderAccountId(ctidTraderAccountId)
            .build()
        val res = ProtoOASymbolsListRes.parseFrom(
            conn.request(PROTO_OA_SYMBOLS_LIST_REQ_VALUE, req, PROTO_OA_SYMBOLS_LIST_RES_VALUE)
        )
        return res.symbolList
            .filter { it.hasSymbolName() }
            .associate { it.symbolName.replace("/", "").uppercase() to it.symbolId }
    }

    /** Open positions + pending orders on the account (live-loop startup/reconnect). */
    fun reconcile(ctidTraderAccountId: Long): ProtoOAReconcileRes {
        val req = ProtoOAReconcileReq.newBuilder()
            .setCtidTraderAccountId(ctidTraderAccountId)
            .build()
        return ProtoOAReconcileRes.parseFrom(
            conn.request(PROTO_OA_RECONCILE_REQ_VALUE, req, PROTO_OA_RECONCILE_RES_VALUE)
        )
    }

    /** Subscribe to spot events (bid/ask) for [symbolIds]; live trendbars ride on these. */
    fun subscribeSpots(ctidTraderAccountId: Long, symbolIds: List<Long>) {
        val req = ProtoOASubscribeSpotsReq.newBuilder()
            .setCtidTraderAccountId(ctidTraderAccountId)
            .addAllSymbolId(symbolIds)
            .setSubscribeToSpotTimestamp(true)
            .build()
        ProtoOASubscribeSpotsRes.parseFrom(
            conn.request(PROTO_OA_SUBSCRIBE_SPOTS_REQ_VALUE, req, PROTO_OA_SUBSCRIBE_SPOTS_RES_VALUE)
        )
    }

    /** Subscribe to live trendbar updates for one (symbol, timeframe); requires an active spot subscription. */
    fun subscribeLiveTrendbars(ctidTraderAccountId: Long, symbolId: Long, tf: Timeframe) {
        val req = ProtoOASubscribeLiveTrendbarReq.newBuilder()
            .setCtidTraderAccountId(ctidTraderAccountId)
            .setPeriod(TrendbarMapper.toPeriod(tf))
            .setSymbolId(symbolId)
            .build()
        ProtoOASubscribeLiveTrendbarRes.parseFrom(
            conn.request(PROTO_OA_SUBSCRIBE_LIVE_TRENDBAR_REQ_VALUE, req, PROTO_OA_SUBSCRIBE_LIVE_TRENDBAR_RES_VALUE)
        )
    }

    fun refreshToken(refreshToken: String): ProtoOARefreshTokenRes {
        val req = ProtoOARefreshTokenReq.newBuilder().setRefreshToken(refreshToken).build()
        return ProtoOARefreshTokenRes.parseFrom(
            conn.request(PROTO_OA_REFRESH_TOKEN_REQ_VALUE, req, PROTO_OA_REFRESH_TOKEN_RES_VALUE)
        )
    }

    /** One raw trendbar window request. Bounds are Unix ms; server treats them as a closed search window. */
    fun getTrendbarsWindow(
        ctidTraderAccountId: Long,
        symbolId: Long,
        tf: Timeframe,
        fromMs: Long,
        toMs: Long,
    ): ProtoOAGetTrendbarsRes {
        val req = ProtoOAGetTrendbarsReq.newBuilder()
            .setCtidTraderAccountId(ctidTraderAccountId)
            .setSymbolId(symbolId)
            .setPeriod(TrendbarMapper.toPeriod(tf))
            .setFromTimestamp(fromMs)
            .setToTimestamp(toMs)
            .build()
        return ProtoOAGetTrendbarsRes.parseFrom(
            conn.request(PROTO_OA_GET_TRENDBARS_REQ_VALUE, req, PROTO_OA_GET_TRENDBARS_RES_VALUE)
        )
    }

    /**
     * Fetch the full [fromMs, toMs) range in chunks, walking forward.
     *
     * The docs state per-period span caps exist for ProtoOAGetTrendbarsReq but
     * current pages don't publish the numbers, so this is engineered to be
     * limit-agnostic: conservative default windows (well under the historical
     * caps), halve-and-retry on boundary errors, and if a response signals
     * hasMore (server-side chunk cap), the window is halved too. Bars dedupe
     * by open time. Requests are throttled to stay clear of rate limits;
     * BLOCKED_PAYLOAD_TYPE honours the server's retryAfter.
     */
    fun fetchTrendbars(
        ctidTraderAccountId: Long,
        symbolId: Long,
        tf: Timeframe,
        fromMs: Long,
        toMs: Long,
        throttleMs: Long = 250,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): List<TrendbarMapper.RawBar> {
        val bars = LinkedHashMap<Long, TrendbarMapper.RawBar>()
        var windowMs = defaultWindowMs(tf)
        var cursor = fromMs
        while (cursor < toMs) {
            val end = minOf(cursor + windowMs, toMs)
            val res = try {
                Thread.sleep(throttleMs)
                getTrendbarsWindow(ctidTraderAccountId, symbolId, tf, cursor, end)
            } catch (e: OpenApiErrorException) {
                when {
                    e.retryAfterSeconds != null -> { // rate limited — wait it out, same window
                        Thread.sleep((e.retryAfterSeconds + 1) * 1000)
                        continue
                    }
                    windowMs > tf.duration.toMillis() * 4 -> { // likely span-cap violation — halve
                        windowMs /= 2
                        continue
                    }
                    else -> throw e
                }
            }
            if (res.hasHasMore() && res.hasMore && windowMs > tf.duration.toMillis() * 4) {
                windowMs /= 2 // server chunk cap hit — shrink so windows are complete
                continue
            }
            for (tb in res.trendbarList) {
                val raw = TrendbarMapper.toRaw(tb)
                bars[raw.openTimeMs] = raw
            }
            cursor = end
            onProgress(cursor - fromMs, toMs - fromMs)
        }
        return bars.values.sortedBy { it.openTimeMs }
    }

    /** Conservative first-attempt windows (bar count ≈ 1500 per request). */
    internal fun defaultWindowMs(tf: Timeframe): Long = when (tf) {
        Timeframe.M1 -> 1L * 24 * 3600 * 1000       // 1 day  (~1440 bars)
        Timeframe.M5 -> 5L * 24 * 3600 * 1000       // 5 days
        Timeframe.M15 -> 14L * 24 * 3600 * 1000     // 2 weeks (~1350 bars)
        Timeframe.H1 -> 56L * 24 * 3600 * 1000      // 8 weeks (~1350 bars)
        Timeframe.H4 -> 224L * 24 * 3600 * 1000
        Timeframe.D1 -> 1344L * 24 * 3600 * 1000
    }

    /** Latest instant that is a completed-bar boundary for [tf] as of [now]. */
    fun lastCompletedBoundary(tf: Timeframe, now: Instant): Instant {
        val ms = tf.duration.toMillis()
        return Instant.ofEpochMilli((now.toEpochMilli() / ms) * ms)
    }

    override fun close() = conn.close()
}
