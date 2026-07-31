package ipda.live

import com.xtrader.protocol.openapi.v2.ProtoOAExecutionEvent
import com.xtrader.protocol.openapi.v2.ProtoOAOrderErrorEvent
import com.xtrader.protocol.openapi.v2.model.ProtoOAExecutionType
import com.xtrader.protocol.openapi.v2.model.ProtoOAOrderType
import com.xtrader.protocol.openapi.v2.model.ProtoOAPosition
import com.xtrader.protocol.openapi.v2.model.ProtoOATradeSide
import ipda.broker.BrokerAdapter
import ipda.broker.OrderAck
import ipda.broker.OrderIntent
import ipda.broker.Side
import ipda.config.LiveConfig
import ipda.log.EventLog
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * LIVE broker adapter — the same [BrokerAdapter] seam as SimBroker, executed
 * against the cTrader demo account (milestone: live demo loop).
 *
 * Order mechanics (v1, market entry at next open):
 *  - [submit] sends a MARKET order with a PROVISIONAL RELATIVE bracket
 *    derived from the decision price (so the position is never naked), then
 *    on the entry fill amends the position to the EXACT ABSOLUTE stop/target
 *    from the intent (leg-extreme stop, fixed-2R target — v1 semantics are
 *    absolute levels, not fill-relative).
 *  - One position or pending order per symbol, enforced HERE exactly as
 *    SimBroker enforces it (the exposure lock is load-bearing — it selects
 *    first-in-cluster signals).
 *  - STALENESS GUARD: intents whose decisionTime is older than
 *    [LiveConfig.maxDecisionAgeSeconds] are rejected — warmup/catch-up
 *    replay regenerates historical signals through the same pipeline, and
 *    those must never become live orders.
 *  - Exits happen SERVER-SIDE via the bracket; closing fills arrive as
 *    execution events and are recorded as closed trades (real two-sided
 *    prices — no synthetic spread haircut; net == gross by construction).
 *
 * Every ack/execution/error event is logged (standing rule 2 analogue: raw
 * events first, interpretations second). All network sends go through the
 * [Gateway] seam so unit tests stay network-free.
 */
class LiveBroker(
    private val cfg: LiveConfig,
    private val eventLog: EventLog,
    private val spreads: SpreadRecorder,
    private val tradeSink: (ClosedLiveTrade) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : BrokerAdapter {

    /** Network seam — implemented over the live connection; faked in tests. */
    interface Gateway {
        fun sendMarketOrder(
            symbolId: Long,
            side: Side,
            volumeCents: Long,
            clientOrderId: String,
            relativeStopLoss1e5: Long?,
            relativeTakeProfit1e5: Long?,
            comment: String,
        )

        fun amendPositionSltp(positionId: Long, stopLoss: Double, takeProfit: Double)
        fun closePosition(positionId: Long, volumeCents: Long)
    }

    data class ClosedLiveTrade(
        val symbol: String,
        val side: Side,
        val decisionTime: Instant?,
        val entryTime: Instant?,
        val entryPrice: Double,
        val exitTime: Instant,
        val exitPrice: Double,
        val stopLoss: Double?,
        val takeProfit: Double?,
        val reason: String, // STOP | TARGET | OTHER
        val grossMove: Double,
        val netMove: Double,
        val rMultiple: Double?,
        val positionId: Long,
        val adopted: Boolean,
    )

    private sealed class Exposure {
        class PendingEntry(val intent: OrderIntent, val clientOrderId: String, val submittedMs: Long) : Exposure()
        class Open(
            val intent: OrderIntent?,
            val side: Side,
            val positionId: Long,
            val entryPrice: Double,
            val entryTimeMs: Long,
            val volumeCents: Long,
            val adopted: Boolean,
        ) : Exposure()
    }

    @Volatile
    private var gateway: Gateway? = null

    private var symbolIdByName: Map<String, Long> = emptyMap()
    private var symbolNameById: Map<Long, String> = emptyMap()

    private val exposure = HashMap<String, Exposure>()

    /** orderId → symbol for OUR entry orders (learned from ORDER_ACCEPTED).
     *  Lets order errors be matched precisely — with several instruments (or a
     *  second live session on the same account, e.g. gold running under its
     *  own config), an error for someone else's order must never clear ours. */
    private val entryOrderSymbols = HashMap<Long, String>()

    /** (Re)wire the broker after connect/reconnect. */
    @Synchronized
    fun attach(gateway: Gateway, symbolIdByName: Map<String, Long>) {
        this.gateway = gateway
        this.symbolIdByName = symbolIdByName
        this.symbolNameById = symbolIdByName.entries.associate { (k, v) -> v to k }
    }

    @Synchronized
    fun detach() {
        gateway = null
    }

    @Synchronized
    fun hasExposure(symbol: String): Boolean = symbol in exposure

    /** Order volume for [symbol] in protocol cents (per-symbol override, else default). */
    fun volumeCentsFor(symbol: String): Long {
        val lots = cfg.volumeLotsBySymbol[symbol] ?: cfg.volumeLots
        return (lots * cfg.lotUnits * 100).roundToLong()
    }

    @Synchronized
    override fun submit(intent: OrderIntent): OrderAck {
        val gw = gateway
            ?: return reject(intent, "disconnected")
        if (intent.symbol in exposure) {
            return reject(intent, "position or pending order exists for ${intent.symbol}")
        }
        val symbolId = symbolIdByName[intent.symbol]
            ?: return reject(intent, "unknown symbol ${intent.symbol}")

        val ageMs = clock() - intent.decisionTime.toEpochMilli()
        if (ageMs > cfg.maxDecisionAgeSeconds * 1000) {
            return reject(intent, "stale decision (${ageMs / 1000}s old) — warmup/catch-up signals never trade")
        }

        // v1 live loop trades market entries only. A LIMIT intent reaching a
        // live session would be a config error (entryMode was rejected by
        // measurement); refuse loudly rather than approximating.
        if (intent.entryType != ipda.broker.EntryType.MARKET) {
            return reject(intent, "live loop supports MARKET intents only (v1)")
        }

        // Provisional relative bracket from the decision price so the order
        // carries protection from the fill; amended to the exact absolute
        // levels on the entry fill.
        val ref = intent.decisionPrice
        val relSl = ref?.let { r ->
            val d = if (intent.side == Side.BUY) r - intent.stopLoss else intent.stopLoss - r
            if (d > 0) (d * 100_000).roundToLong() else null
        }
        val relTp = ref?.let { r ->
            val d = if (intent.side == Side.BUY) intent.takeProfit - r else r - intent.takeProfit
            if (d > 0) (d * 100_000).roundToLong() else null
        }

        val clientOrderId = "ipda-${intent.symbol}-${intent.decisionTime.epochSecond}"
        exposure[intent.symbol] = Exposure.PendingEntry(intent, clientOrderId, clock())

        val volume = volumeCentsFor(intent.symbol)
        val q = spreads.quote(intent.symbol)
        eventLog.append(
            "live_submit",
            """{"symbol":"${intent.symbol}","side":"${intent.side}","decisionTime":"${intent.decisionTime}","decisionPrice":${intent.decisionPrice},"stop":${intent.stopLoss},"tp":${intent.takeProfit},"volumeCents":$volume,"clientOrderId":"$clientOrderId","relSl":${relSl},"relTp":${relTp},"bid":${q?.bid},"ask":${q?.ask}}"""
        )
        gw.sendMarketOrder(
            symbolId = symbolId,
            side = intent.side,
            volumeCents = volume,
            clientOrderId = clientOrderId,
            relativeStopLoss1e5 = relSl,
            relativeTakeProfit1e5 = relTp,
            comment = "ipda v1 decision ${intent.decisionTime}",
        )
        return OrderAck(accepted = true, brokerRef = clientOrderId)
    }

    private fun reject(intent: OrderIntent, reason: String): OrderAck {
        eventLog.append(
            "live_reject",
            """{"symbol":"${intent.symbol}","side":"${intent.side}","decisionTime":"${intent.decisionTime}","reason":"$reason"}"""
        )
        return OrderAck(accepted = false, brokerRef = null, reason = reason)
    }

    /** Adopt positions found open at startup/reconnect (reconcile). */
    @Synchronized
    fun adoptPositions(positions: List<ProtoOAPosition>) {
        for (p in positions) {
            val symbol = symbolNameById[p.tradeData.symbolId] ?: continue
            val existing = exposure[symbol]
            if (existing is Exposure.Open && existing.positionId == p.positionId) continue
            exposure[symbol] = Exposure.Open(
                intent = null,
                side = if (p.tradeData.tradeSide == ProtoOATradeSide.BUY) Side.BUY else Side.SELL,
                positionId = p.positionId,
                entryPrice = if (p.hasPrice()) p.price else 0.0,
                entryTimeMs = if (p.tradeData.hasOpenTimestamp()) p.tradeData.openTimestamp else 0L,
                volumeCents = p.tradeData.volume,
                adopted = true,
            )
            eventLog.append(
                "live_adopt",
                """{"symbol":"$symbol","positionId":${p.positionId},"side":"${if (p.tradeData.tradeSide == ProtoOATradeSide.BUY) "BUY" else "SELL"}","entry":${if (p.hasPrice()) p.price else null},"stop":${if (p.hasStopLoss()) p.stopLoss else null},"tp":${if (p.hasTakeProfit()) p.takeProfit else null}}"""
            )
        }
    }

    /** Snapshot of current exposure for shutdown logging. */
    @Synchronized
    fun openStateJson(): String = buildString {
        append("[")
        append(exposure.entries.sortedBy { it.key }.joinToString(",") { (symbol, e) ->
            when (e) {
                is Exposure.PendingEntry ->
                    """{"symbol":"$symbol","state":"PENDING_ENTRY","clientOrderId":"${e.clientOrderId}","decisionTime":"${e.intent.decisionTime}"}"""
                is Exposure.Open ->
                    """{"symbol":"$symbol","state":"OPEN","positionId":${e.positionId},"entry":${e.entryPrice},"adopted":${e.adopted},"stop":${e.intent?.stopLoss},"tp":${e.intent?.takeProfit}}"""
            }
        })
        append("]")
    }

    /** Optional flatten (explicit --flatten only; default leaves brackets to resolve). */
    @Synchronized
    fun flattenAll() {
        val gw = gateway ?: return
        for ((symbol, e) in exposure) {
            if (e is Exposure.Open) {
                eventLog.append("live_flatten", """{"symbol":"$symbol","positionId":${e.positionId}}""")
                gw.closePosition(e.positionId, e.volumeCents)
            }
        }
    }

    // ------------------------------------------------------------------
    // Execution / error event handling (reader-thread entry points)
    // ------------------------------------------------------------------

    @Synchronized
    fun onExecutionEvent(ev: ProtoOAExecutionEvent) {
        logRawExecution(ev)
        // Learn our entry orders' ids so later errors/cancels match precisely.
        if (ev.hasOrder() && ev.order.hasClientOrderId()) {
            val symbol = exposure.entries
                .firstOrNull { (_, e) -> e is Exposure.PendingEntry && e.clientOrderId == ev.order.clientOrderId }
                ?.key
            if (symbol != null) entryOrderSymbols[ev.order.orderId] = symbol
        }
        when (ev.executionType) {
            ProtoOAExecutionType.ORDER_FILLED, ProtoOAExecutionType.ORDER_PARTIAL_FILL -> onFilled(ev)
            ProtoOAExecutionType.ORDER_REJECTED,
            ProtoOAExecutionType.ORDER_CANCELLED,
            ProtoOAExecutionType.ORDER_EXPIRED -> onEntryDead(ev)
            else -> { /* accepted/replaced/swap/… — raw log above is enough */ }
        }
    }

    @Synchronized
    fun onOrderError(ev: ProtoOAOrderErrorEvent) {
        eventLog.append(
            "live_order_error",
            """{"errorCode":"${ev.errorCode}","description":"${if (ev.hasDescription()) ev.description.replace("\"", "'") else ""}","orderId":${if (ev.hasOrderId()) ev.orderId else null},"positionId":${if (ev.hasPositionId()) ev.positionId else null}}"""
        )
        // Clear a pending entry ONLY when the error's orderId is provably one
        // of ours (learned from its ACCEPTED event). Errors for unknown orders
        // — another session's instrument, a manual cTrader order — are logged
        // above and otherwise ignored. An entry rejected before its ACCEPTED
        // event never maps here; the pending-entry timeout reaps it instead.
        if (!ev.hasOrderId()) return
        val symbol = entryOrderSymbols.remove(ev.orderId) ?: return
        if (exposure[symbol] is Exposure.PendingEntry) {
            exposure.remove(symbol)
            eventLog.append(
                "live_entry_dead",
                """{"symbol":"$symbol","cause":"ORDER_ERROR ${ev.errorCode}"}"""
            )
        }
    }

    /**
     * Periodic housekeeping (called from the session pump): a pending ENTRY
     * older than [LiveConfig.pendingEntryTimeoutSeconds] is dead weight — a
     * market order either fills or errors within seconds, and a stuck pending
     * would silently block its symbol's exposure slot forever (e.g. an order
     * rejected before we ever learned its orderId).
     */
    @Synchronized
    fun housekeeping() {
        val cutoff = clock() - cfg.pendingEntryTimeoutSeconds * 1000
        val stale = exposure.entries.filter { (_, e) -> e is Exposure.PendingEntry && e.submittedMs < cutoff }
        for ((symbol, _) in stale) {
            exposure.remove(symbol)
            eventLog.append(
                "live_entry_dead",
                """{"symbol":"$symbol","cause":"PENDING_TIMEOUT"}"""
            )
        }
    }

    private fun onFilled(ev: ProtoOAExecutionEvent) {
        if (!ev.hasDeal()) return
        val deal = ev.deal
        val symbol = symbolNameById[deal.symbolId] ?: return

        if (deal.hasClosePositionDetail()) {
            onClosingFill(ev, symbol)
            return
        }

        // Entry fill: match our pending order by clientOrderId.
        val e = exposure[symbol]
        val clientOrderId = if (ev.hasOrder() && ev.order.hasClientOrderId()) ev.order.clientOrderId else null
        if (e is Exposure.PendingEntry && (clientOrderId == null || clientOrderId == e.clientOrderId)) {
            val entryPrice = if (deal.hasExecutionPrice()) deal.executionPrice else e.intent.decisionPrice ?: 0.0
            exposure[symbol] = Exposure.Open(
                intent = e.intent,
                side = e.intent.side,
                positionId = deal.positionId,
                entryPrice = entryPrice,
                entryTimeMs = deal.executionTimestamp,
                volumeCents = deal.filledVolume,
                adopted = false,
            )
            val q = spreads.quote(symbol)
            val slippage = e.intent.decisionPrice?.let { ref ->
                if (e.intent.side == Side.BUY) entryPrice - ref else ref - entryPrice
            }
            eventLog.append(
                "live_fill",
                """{"symbol":"$symbol","side":"${e.intent.side}","positionId":${deal.positionId},"entry":$entryPrice,"decisionPrice":${e.intent.decisionPrice},"slippage":$slippage,"bid":${q?.bid},"ask":${q?.ask},"executionTimestamp":${deal.executionTimestamp}}"""
            )
            // Exact absolute bracket (v1 semantics: leg-extreme stop, 2R from
            // signal close) replaces the provisional relative one.
            gateway?.amendPositionSltp(deal.positionId, e.intent.stopLoss, e.intent.takeProfit)
            eventLog.append(
                "live_bracket",
                """{"symbol":"$symbol","positionId":${deal.positionId},"stop":${e.intent.stopLoss},"tp":${e.intent.takeProfit}}"""
            )
        }
    }

    private fun onClosingFill(ev: ProtoOAExecutionEvent, symbol: String) {
        val deal = ev.deal
        val e = exposure[symbol]
        val open = e as? Exposure.Open
        if (open == null || open.positionId != deal.positionId) {
            // A close for a position we don't track (manual close of an
            // unknown position, partial history) — raw log already captured it.
            return
        }
        // Partial close: reduce tracked volume, keep exposure until flat.
        val closedVolume = if (deal.closePositionDetail.hasClosedVolume()) deal.closePositionDetail.closedVolume else deal.filledVolume
        val remaining = open.volumeCents - closedVolume
        val exitPrice = if (deal.hasExecutionPrice()) deal.executionPrice else 0.0
        val exitTime = Instant.ofEpochMilli(deal.executionTimestamp)
        val entryPrice = if (deal.closePositionDetail.hasEntryPrice() && deal.closePositionDetail.entryPrice > 0)
            deal.closePositionDetail.entryPrice else open.entryPrice
        val intent = open.intent
        val side = open.side
        val dir = if (side == Side.BUY) 1.0 else -1.0
        val gross = dir * (exitPrice - entryPrice)
        val risk = intent?.let { abs(entryPrice - it.stopLoss) }
        val reason = when {
            ev.hasOrder() && ev.order.hasIsStopOut() && ev.order.isStopOut -> "STOP"
            intent == null -> "OTHER"
            ev.hasOrder() && ev.order.orderType == ProtoOAOrderType.STOP_LOSS_TAKE_PROFIT ->
                if (abs(exitPrice - intent.stopLoss) <= abs(exitPrice - intent.takeProfit)) "STOP" else "TARGET"
            else -> "OTHER"
        }
        val trade = ClosedLiveTrade(
            symbol = symbol,
            side = side,
            decisionTime = intent?.decisionTime,
            entryTime = if (open.entryTimeMs > 0) Instant.ofEpochMilli(open.entryTimeMs) else null,
            entryPrice = entryPrice,
            exitTime = exitTime,
            exitPrice = exitPrice,
            stopLoss = intent?.stopLoss,
            takeProfit = intent?.takeProfit,
            reason = reason,
            grossMove = gross,
            netMove = gross, // real two-sided prices — no synthetic spread haircut
            rMultiple = risk?.takeIf { it > 0 }?.let { gross / it },
            positionId = open.positionId,
            adopted = open.adopted,
        )
        if (remaining <= 0) {
            exposure.remove(symbol)
        } else {
            exposure[symbol] = Exposure.Open(intent, side, open.positionId, open.entryPrice, open.entryTimeMs, remaining, open.adopted)
        }
        if (remaining <= 0) {
            tradeSink(trade)
        } else {
            eventLog.append(
                "live_partial_close",
                """{"symbol":"$symbol","positionId":${open.positionId},"closedVolume":$closedVolume,"remaining":$remaining,"exit":$exitPrice}"""
            )
        }
    }

    private fun onEntryDead(ev: ProtoOAExecutionEvent) {
        val clientOrderId = if (ev.hasOrder() && ev.order.hasClientOrderId()) ev.order.clientOrderId else null
        val entry = exposure.entries.firstOrNull { (_, e) ->
            e is Exposure.PendingEntry && (clientOrderId == null || e.clientOrderId == clientOrderId)
        } ?: return
        exposure.remove(entry.key)
        eventLog.append(
            "live_entry_dead",
            """{"symbol":"${entry.key}","cause":"${ev.executionType}"}"""
        )
    }

    private fun logRawExecution(ev: ProtoOAExecutionEvent) {
        val order = if (ev.hasOrder()) ev.order else null
        val deal = if (ev.hasDeal()) ev.deal else null
        eventLog.append(
            "live_exec",
            """{"type":"${ev.executionType}","orderId":${order?.orderId},"clientOrderId":${order?.let { if (it.hasClientOrderId()) "\"${it.clientOrderId}\"" else null }},"positionId":${deal?.positionId ?: order?.let { if (it.hasPositionId()) it.positionId else null }},"dealId":${deal?.dealId},"price":${deal?.let { if (it.hasExecutionPrice()) it.executionPrice else null }},"closing":${deal?.hasClosePositionDetail() ?: false},"errorCode":${if (ev.hasErrorCode()) "\"${ev.errorCode}\"" else null}}"""
        )
    }
}
