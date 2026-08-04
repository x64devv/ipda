package ipda.live

import com.google.protobuf.ByteString
import com.xtrader.protocol.openapi.v2.ProtoOAAmendPositionSLTPReq
import com.xtrader.protocol.openapi.v2.ProtoOAClosePositionReq
import com.xtrader.protocol.openapi.v2.ProtoOAExecutionEvent
import com.xtrader.protocol.openapi.v2.ProtoOANewOrderReq
import com.xtrader.protocol.openapi.v2.ProtoOAOrderErrorEvent
import com.xtrader.protocol.openapi.v2.ProtoOASpotEvent
import com.xtrader.protocol.openapi.v2.model.ProtoOAOrderType
import com.xtrader.protocol.openapi.v2.model.ProtoOAPayloadType
import com.xtrader.protocol.openapi.v2.model.ProtoOATradeSide
import ipda.broker.Side
import ipda.config.IpdaConfig
import ipda.ctrader.CTraderClient
import ipda.ctrader.FatalConfigException
import ipda.ctrader.accountGrantHelp
import ipda.ctrader.LiveConnection
import ipda.ctrader.OpenApiConnection
import ipda.ctrader.OpenApiErrorException
import ipda.ctrader.OpenApiTransport
import ipda.ctrader.TrendbarMapper
import ipda.feed.Feed
import ipda.feed.canonicalOrder
import ipda.log.EventLog
import ipda.model.Candle
import ipda.model.Timeframe
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The LIVE side of the [Feed] seam (milestone: live demo loop). The engine
 * consumes candles from [run] and cannot tell it is live — the canonical
 * ordering contract (close time → HTF first → symbol) is enforced by the
 * [CanonicalSequencer], completed bars only (standing rule 1) by the
 * [BarAssembler]s.
 *
 * Lifecycle, all inside [run] so the engine sees ONE uninterrupted stream:
 *  1. connect (TLS, event-driven [LiveConnection]) + proven auth chain
 *     (app auth → accounts-by-token → account auth), with an in-protocol
 *     token refresh + persist on auth failure;
 *  2. reconcile → adopt open positions (exposure lock survives restarts);
 *  3. subscribe spots + live trendbars for every configured (symbol, TF);
 *  4. CATCH-UP: fetch completed bars from the last emitted candle (or
 *     warmupDays on first start) via the proven trendbar fetch path and
 *     emit them through the same seam — this warms detectors after restart
 *     and replays anything missed while disconnected. The broker's
 *     staleness guard keeps these signals from ever trading.
 *  5. pump live events: assemble → sequence → emit; per-series GAP-FILL
 *     fetches any bars missing between consecutive emissions (weekend gaps
 *     fetch empty and cost one throttled request).
 *  6. on stream death: log, back off exponentially, reconnect, goto 1 —
 *     detector/engine state lives across reconnects because the engine
 *     never returns from [run].
 */
class LiveSession(
    private val cfg: IpdaConfig,
    private val secrets: SecretsStore,
    private val eventLog: EventLog,
    private val broker: LiveBroker,
    private val spreads: SpreadRecorder,
    private val accountOverride: Long? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : Feed {

    @Volatile
    private var stopped = false

    @Volatile
    private var currentConn: LiveConnection? = null

    /**
     * Set when the loop hits a misconfiguration that retrying cannot fix;
     * rethrown out of [run] so the process exits non-zero (see
     * [FatalConfigException]).
     */
    @Volatile
    private var fatal: FatalConfigException? = null

    /** Set after first successful account auth; used for artifact stamping. */
    @Volatile
    var accountId: Long? = null
        private set

    @Volatile
    var accountLabel: String? = null
        private set

    private val timeframes = listOf(cfg.biasTimeframe, cfg.entryTimeframe).distinct()
    private val lastEmittedOpenMs = HashMap<Pair<String, Timeframe>, Long>()

    override fun run(handler: (Candle) -> Unit) {
        var backoffSec = cfg.live.reconnectMinBackoffSeconds
        while (!stopped) {
            try {
                connectAndPump(handler)
                backoffSec = cfg.live.reconnectMinBackoffSeconds
                if (!stopped) {
                    // Pump returned without an exception but we are not
                    // stopping — treat as disconnect, reconnect after minimum backoff.
                    eventLog.append("live_disconnect", """{"cause":"stream ended"}""")
                    sleepInterruptibly(backoffSec * 1000)
                }
            } catch (e: FatalConfigException) {
                // NOT a disconnect. Reconnecting cannot fix a wrong account
                // id or a missing symbol, and looping on one is worse than
                // failing: each retry appends an event, so the manager's
                // heartbeat reads HEALTHY while nothing trades (31 Jul – 4
                // Aug 2026, four days lost). Record once, stop, and let it
                // out of run() so the process dies visibly.
                fatal = e
                stopped = true
                eventLog.append(
                    "live_fatal",
                    """{"at":"${Instant.ofEpochMilli(clock())}","cause":${jsonString(e.message ?: "FatalConfigException")}}"""
                )
            } catch (e: Exception) {
                if (stopped) break
                eventLog.append(
                    "live_disconnect",
                    """{"cause":${jsonString(e.message ?: e.javaClass.simpleName)},"backoffSeconds":$backoffSec}"""
                )
                sleepInterruptibly(backoffSec * 1000)
                backoffSec = minOf(backoffSec * 2, cfg.live.reconnectMaxBackoffSeconds)
            } finally {
                broker.detach()
                currentConn?.let { runCatching { it.close() } }
                currentConn = null
            }
        }
        fatal?.let { throw it }
    }

    fun stop() {
        stopped = true
        currentConn?.let { runCatching { it.close() } }
    }

    private fun sleepInterruptibly(ms: Long) {
        val end = clock() + ms
        while (!stopped && clock() < end) Thread.sleep(100)
    }

    // ------------------------------------------------------------------

    private fun connectAndPump(handler: (Candle) -> Unit) {
        val host = secrets.host
        val conn = LiveConnection.connect(host, heartbeatIntervalSeconds = cfg.live.heartbeatIntervalSeconds)
        currentConn = conn
        val client = CTraderClient(conn)

        val spotQueue = LinkedBlockingQueue<ProtoOASpotEvent>()

        // Authenticate (with one in-protocol token refresh attempt on failure).
        client.applicationAuth(secrets.clientId, secrets.clientSecret)
        val accounts = try {
            client.accountsByToken(secrets.accessToken)
        } catch (e: OpenApiErrorException) {
            if (!looksLikeTokenError(e) || secrets.refreshToken == null) throw e
            refreshTokens(client)
            client.accountsByToken(secrets.accessToken)
        }
        if (accounts.isEmpty()) throw FatalConfigException(
            "Access token grants no accounts — re-issue the token with the accounts scope."
        )
        val chosen = accountOverride
            ?: secrets.accountId
            ?: accounts.first { !(it.hasIsLive() && it.isLive) }.ctidTraderAccountId
        val acct = accounts.firstOrNull { it.ctidTraderAccountId == chosen }
            ?: throw FatalConfigException(accountGrantHelp(accounts, chosen))
        if ((acct.hasIsLive() && acct.isLive) && host != OpenApiConnection.LIVE_HOST) {
            throw FatalConfigException(
                "Account $chosen is a LIVE account but the configured host is $host — " +
                    "refusing (environments are isolated)."
            )
        }
        try {
            client.accountAuth(chosen, secrets.accessToken)
        } catch (e: OpenApiErrorException) {
            if (!looksLikeTokenError(e) || secrets.refreshToken == null) throw e
            refreshTokens(client)
            client.accountAuth(chosen, secrets.accessToken)
        }
        accountId = chosen
        accountLabel = buildString {
            append(chosen)
            if (acct.hasBrokerTitleShort()) append(" ").append(acct.brokerTitleShort)
            if (acct.hasTraderLogin()) append(" login ").append(acct.traderLogin)
        }
        eventLog.append(
            "live_connected",
            """{"at":"${Instant.ofEpochMilli(clock())}","host":"$host","account":$chosen,"label":${jsonString(accountLabel ?: "")}}"""
        )

        // Symbols for the configured instruments only.
        val allSymbols = client.symbolIdsByName(chosen)
        val symbolIdByName = cfg.instruments.associateWith { instrument ->
            allSymbols[instrument.uppercase()] ?: throw FatalConfigException(
                "Symbol $instrument is not tradable on account $chosen. " +
                    "Available (first 40): " +
                    allSymbols.keys.sorted().take(40).joinToString(", ") +
                    if (allSymbols.size > 40) ", … (${allSymbols.size} total)" else ""
            )
        }
        val symbolNameById = symbolIdByName.entries.associate { (k, v) -> v to k }

        // Broker gateway first, then the event listener, THEN reconcile —
        // an execution event arriving between reconcile and listener wiring
        // would otherwise be lost (e.g. an adopted position closing).
        broker.attach(CTraderGateway(conn, chosen), symbolIdByName)

        // Route events. Spot events are queued for the pump thread (engine
        // stays single-threaded); execution/order-error events go straight to
        // the broker (its methods are synchronized and fast).
        conn.eventListener = { payloadType, payload ->
            when (payloadType) {
                ProtoOAPayloadType.PROTO_OA_SPOT_EVENT_VALUE ->
                    spotQueue.offer(ProtoOASpotEvent.parseFrom(payload))
                ProtoOAPayloadType.PROTO_OA_EXECUTION_EVENT_VALUE ->
                    broker.onExecutionEvent(ProtoOAExecutionEvent.parseFrom(payload))
                ProtoOAPayloadType.PROTO_OA_ORDER_ERROR_EVENT_VALUE ->
                    broker.onOrderError(ProtoOAOrderErrorEvent.parseFrom(payload))
                ProtoOAPayloadType.PROTO_OA_ACCOUNT_DISCONNECT_EVENT_VALUE,
                ProtoOAPayloadType.PROTO_OA_ACCOUNTS_TOKEN_INVALIDATED_EVENT_VALUE -> {
                    eventLog.append("live_account_event", """{"payloadType":$payloadType}""")
                    runCatching { conn.close() } // force reconnect path
                }
                else -> { /* unmodelled event — ignore */ }
            }
        }

        val reconcile = client.reconcile(chosen)
        if (reconcile.positionCount > 0) broker.adoptPositions(reconcile.positionList)
        eventLog.append(
            "live_reconcile",
            """{"positions":${reconcile.positionCount},"pendingOrders":${reconcile.orderCount}}"""
        )

        // Subscribe, then catch up to the last completed boundary.
        client.subscribeSpots(chosen, symbolIdByName.values.toList())
        for ((_, symbolId) in symbolIdByName) {
            for (tf in timeframes) client.subscribeLiveTrendbars(chosen, symbolId, tf)
        }

        catchUp(client, chosen, symbolIdByName, handler)

        // Assemble + sequence + pump.
        val assemblers = HashMap<Pair<String, Timeframe>, BarAssembler>()
        for (symbol in cfg.instruments) {
            for (tf in timeframes) {
                val a = BarAssembler(symbol, tf)
                lastEmittedOpenMs[symbol to tf]?.let { a.markCompletedThrough(it) }
                assemblers[symbol to tf] = a
            }
        }
        val sequencer = CanonicalSequencer(cfg.live.emitSettleMillis, clock) { late ->
            eventLog.append(
                "feed_order_warning",
                """{"symbol":"${late.symbol}","tf":"${late.timeframe}","closeTime":"${late.closeTime}","note":"late candle emitted out of canonical order"}"""
            )
        }
        val graceMs = cfg.live.barGraceSeconds * 1000

        fun emitCompleted(candle: Candle) {
            for (c in sequencer.add(candle)) emitWithGapFill(client, chosen, symbolIdByName, handler, c)
        }

        while (!stopped && conn.isOpen) {
            val ev = spotQueue.poll(200, TimeUnit.MILLISECONDS)
            if (ev != null) {
                val symbol = symbolNameById[ev.symbolId]
                if (symbol != null) {
                    spreads.onQuote(
                        symbol = symbol,
                        bid = if (ev.hasBid()) ev.bid.toDouble() / TrendbarMapper.PRICE_SCALE else null,
                        ask = if (ev.hasAsk()) ev.ask.toDouble() / TrendbarMapper.PRICE_SCALE else null,
                        timestampMs = if (ev.hasTimestamp()) ev.timestamp else clock(),
                    )
                    for (tb in ev.trendbarList) {
                        if (!tb.hasPeriod() || !tb.hasLow() || !tb.hasUtcTimestampInMinutes()) continue
                        val tf = TrendbarMapper.fromPeriod(tb.period) ?: continue
                        val assembler = assemblers[symbol to tf] ?: continue
                        assembler.onUpdate(TrendbarMapper.toRaw(tb))?.let { emitCompleted(it) }
                    }
                }
            }
            val now = clock()
            for (a in assemblers.values) {
                a.flushDue(now, graceMs)?.let { emitCompleted(it) }
            }
            for (c in sequencer.drainReady()) {
                emitWithGapFill(client, chosen, symbolIdByName, handler, c)
            }
            broker.housekeeping()
        }
    }

    /** Fetch and emit everything completed since the last emission (or warmup horizon). */
    private fun catchUp(
        client: CTraderClient,
        accountId: Long,
        symbolIdByName: Map<String, Long>,
        handler: (Candle) -> Unit,
    ) {
        val now = Instant.ofEpochMilli(clock())
        val all = ArrayList<Candle>()
        for ((symbol, symbolId) in symbolIdByName) {
            for (tf in timeframes) {
                val tfMs = tf.duration.toMillis()
                val from = lastEmittedOpenMs[symbol to tf]?.plus(tfMs)
                    ?: now.minus(Duration.ofDays(cfg.live.warmupDays)).toEpochMilli()
                val to = client.lastCompletedBoundary(tf, now).toEpochMilli()
                if (from >= to) continue
                val bars = client.fetchTrendbars(accountId, symbolId, tf, from, to)
                    .filter { TrendbarMapper.isComplete(it, tf, now) }
                all.addAll(bars.map { TrendbarMapper.toCandle(it, symbol, tf) })
            }
        }
        if (all.isEmpty()) {
            eventLog.append("live_catchup", """{"bars":0}""")
            return
        }
        val ordered = all.sortedWith(canonicalOrder)
        var emitted = 0
        for (c in ordered) {
            if (emitDirect(handler, c)) emitted++
        }
        eventLog.append(
            "live_catchup",
            """{"bars":$emitted,"from":"${ordered.first().openTime}","to":"${ordered.last().closeTime}"}"""
        )
    }

    /** Emit one candle if it is new for its series; updates the watermark. */
    private fun emitDirect(handler: (Candle) -> Unit, c: Candle): Boolean {
        val key = c.symbol to c.timeframe
        val last = lastEmittedOpenMs[key]
        val openMs = c.openTime.toEpochMilli()
        if (last != null && openMs <= last) return false
        lastEmittedOpenMs[key] = openMs
        handler(c)
        return true
    }

    /**
     * Emit with per-series continuity check: if bars are missing between the
     * last emission and this candle (missed ticks, micro-disconnects), fetch
     * them first so the engine sees an unbroken series. Weekend/holiday gaps
     * fetch empty and cost one throttled request.
     */
    private fun emitWithGapFill(
        client: CTraderClient,
        accountId: Long,
        symbolIdByName: Map<String, Long>,
        handler: (Candle) -> Unit,
        c: Candle,
    ) {
        val key = c.symbol to c.timeframe
        val tfMs = c.timeframe.duration.toMillis()
        val last = lastEmittedOpenMs[key]
        val openMs = c.openTime.toEpochMilli()
        if (last != null && openMs > last + tfMs) {
            val symbolId = symbolIdByName[c.symbol]
            if (symbolId != null) {
                val fetched = runCatching {
                    client.getTrendbarsWindow(accountId, symbolId, c.timeframe, last + tfMs, openMs)
                        .trendbarList
                        .map { TrendbarMapper.toRaw(it) }
                        .filter { it.openTimeMs > last && it.openTimeMs < openMs }
                        .sortedBy { it.openTimeMs }
                        .map { TrendbarMapper.toCandle(it, c.symbol, c.timeframe) }
                }.getOrElse { emptyList() }
                if (fetched.isNotEmpty()) {
                    eventLog.append(
                        "live_gapfill",
                        """{"symbol":"${c.symbol}","tf":"${c.timeframe}","bars":${fetched.size},"from":"${fetched.first().openTime}","to":"${fetched.last().openTime}"}"""
                    )
                    for (g in fetched) emitDirect(handler, g)
                }
            }
        }
        emitDirect(handler, c)
    }

    // ------------------------------------------------------------------

    private fun looksLikeTokenError(e: OpenApiErrorException): Boolean {
        val code = e.errorCode.uppercase()
        return "TOKEN" in code || "OA_AUTH" in code || "NOT_AUTHENTICATED" in code
    }

    private fun refreshTokens(client: CTraderClient) {
        val refresh = secrets.refreshToken ?: error("no refresh token available")
        val res = client.refreshToken(refresh)
        secrets.persistTokens(res.accessToken, res.refreshToken)
        eventLog.append("live_token_refreshed", """{"expiresInSeconds":${res.expiresIn}}""")
    }
}

/** [LiveBroker.Gateway] over the live transport — fire-and-forget sends; acks arrive as execution events. */
class CTraderGateway(
    private val conn: OpenApiTransport,
    private val accountId: Long,
) : LiveBroker.Gateway {

    override fun sendMarketOrder(
        symbolId: Long,
        side: Side,
        volumeCents: Long,
        clientOrderId: String,
        relativeStopLoss1e5: Long?,
        relativeTakeProfit1e5: Long?,
        comment: String,
    ) {
        val b = ProtoOANewOrderReq.newBuilder()
            .setCtidTraderAccountId(accountId)
            .setSymbolId(symbolId)
            .setOrderType(ProtoOAOrderType.MARKET)
            .setTradeSide(if (side == Side.BUY) ProtoOATradeSide.BUY else ProtoOATradeSide.SELL)
            .setVolume(volumeCents)
            .setClientOrderId(clientOrderId)
            .setLabel("ipda-v1")
            .setComment(comment)
        relativeStopLoss1e5?.let { b.relativeStopLoss = it }
        relativeTakeProfit1e5?.let { b.relativeTakeProfit = it }
        conn.send(ProtoOAPayloadType.PROTO_OA_NEW_ORDER_REQ_VALUE, b.build())
    }

    override fun amendPositionSltp(positionId: Long, stopLoss: Double, takeProfit: Double) {
        val req = ProtoOAAmendPositionSLTPReq.newBuilder()
            .setCtidTraderAccountId(accountId)
            .setPositionId(positionId)
            .setStopLoss(stopLoss)
            .setTakeProfit(takeProfit)
            .build()
        conn.send(ProtoOAPayloadType.PROTO_OA_AMEND_POSITION_SLTP_REQ_VALUE, req)
    }

    override fun closePosition(positionId: Long, volumeCents: Long) {
        val req = ProtoOAClosePositionReq.newBuilder()
            .setCtidTraderAccountId(accountId)
            .setPositionId(positionId)
            .setVolume(volumeCents)
            .build()
        conn.send(ProtoOAPayloadType.PROTO_OA_CLOSE_POSITION_REQ_VALUE, req)
    }
}

/**
 * Minimal JSON string escaper. The old inline `.replace("\"", "'")` was fine
 * for one-line socket errors but silently produces invalid JSON for anything
 * containing a newline — and [FatalConfigException] messages are deliberately
 * multi-line.
 */
private fun jsonString(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
}
