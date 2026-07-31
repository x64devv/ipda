package ipda.broker

import ipda.model.Candle
import java.time.Instant

/**
 * Simulated broker — implements the SAME BrokerAdapter seam the live cTrader
 * adapter will. Fill model v1 (conservative by policy, §9.2):
 *
 *  - Candle eligibility (lookahead guard): an intent decided at close T can
 *    only interact with candles whose OPEN time is >= T. With M15 execution a
 *    same-close-time M15 candle arrives after the H1 decision but covers
 *    price action BEFORE it — the guard skips it by construction.
 *  - MARKET intents fill at the open of the first eligible candle (v0 rule).
 *  - LIMIT intents (M15 refinement, 29 Jul 2026) fill AT the limit price,
 *    never better, when an eligible candle trades through it (BUY: low <=
 *    limit; SELL: high >= limit) — even if the open gapped past the level.
 *    A candle that opens at/beyond the STOP level while the limit is unfilled
 *    cancels the order (setup invalidated — mirrors the v0 market-order
 *    gap-through-stop rejection). An unfilled limit expires after
 *    [OrderIntent.expiryCandles] eligible candles. Mid-candle
 *    stop-before-fill needs no extra rule: the stop lies beyond the limit,
 *    so any non-gap path to the stop fills the limit first (then stop-first
 *    applies to the same candle).
 *  - Spread haircut: candle prices are treated as one-sided (bid); the full
 *    configured spread is charged once per round trip in the net result.
 *  - Intra-candle ambiguity: if one candle's range touches both stop and
 *    target, ASSUME THE STOP WAS HIT FIRST — always. Pessimistic and
 *    deterministic.
 *  - Gap handling: entry gaps are taken at the open as-is (MARKET) or at the
 *    limit level, never better (LIMIT); a stop gapped through fills at the
 *    (worse) open; a target gapped through fills AT the target level (never
 *    better) — all choices biased against us. The gapped-stop worse-open rule
 *    applies to positions that existed BEFORE the candle; a position entered
 *    intra-candle exits at the stop level itself.
 *  - One position or pending order per symbol; further intents are rejected.
 *
 * Sizing is fixed at the intent's volume; results are reported in price units
 * and R multiples — the baseline measures edge, not money management.
 */
class SimBroker(
    /** Full round-trip spread per symbol, in PRICE units (e.g. 0.00007 = 0.7 pips on EURUSD). */
    private val spreadBySymbol: Map<String, Double>,
) : BrokerAdapter {

    enum class ExitReason { STOP, TARGET, END_OF_DATA }

    data class ClosedTrade(
        val symbol: String,
        val side: Side,
        val decisionTime: Instant,
        val entryTime: Instant,
        val entryPrice: Double,
        val exitTime: Instant,
        val exitPrice: Double,
        val stopLoss: Double,
        val takeProfit: Double,
        val reason: ExitReason,
        /** direction-signed price move, before spread. */
        val grossMove: Double,
        /** grossMove minus the round-trip spread. */
        val netMove: Double,
        /** netMove / initial risk (entry↔stop distance). */
        val rMultiple: Double,
    )

    enum class CancelReason { EXPIRED, INVALIDATED }

    data class CancelledOrder(
        val symbol: String,
        val side: Side,
        val decisionTime: Instant,
        val limitPrice: Double?,
        val cancelTime: Instant,
        val reason: CancelReason,
        /** Eligible candles the order lived through before cancelling. */
        val candlesLived: Int,
    )

    private data class Pending(val intent: OrderIntent, var candlesSeen: Int = 0)
    private data class Open(
        val intent: OrderIntent,
        val entryTime: Instant,
        val entryPrice: Double,
        val risk: Double,
    )

    private val pending = HashMap<String, Pending>()
    private val open = HashMap<String, Open>()
    private val _closed = ArrayList<ClosedTrade>()
    val closedTrades: List<ClosedTrade> get() = _closed
    private val _cancelled = ArrayList<CancelledOrder>()
    /** Limit orders that died unfilled (expiry / invalidation) — logged by the engine. */
    val cancelledOrders: List<CancelledOrder> get() = _cancelled

    fun hasExposure(symbol: String): Boolean = symbol in pending || symbol in open

    override fun submit(intent: OrderIntent): OrderAck {
        if (hasExposure(intent.symbol)) {
            return OrderAck(accepted = false, brokerRef = null, reason = "position or pending order exists for ${intent.symbol}")
        }
        val validBracket = when (intent.side) {
            Side.BUY -> intent.stopLoss < intent.takeProfit
            Side.SELL -> intent.stopLoss > intent.takeProfit
        }
        if (!validBracket) {
            return OrderAck(accepted = false, brokerRef = null, reason = "invalid bracket for ${intent.side}")
        }
        if (intent.entryType == EntryType.LIMIT) {
            val l = intent.limitPrice
                ?: return OrderAck(accepted = false, brokerRef = null, reason = "LIMIT intent without limitPrice")
            val validLimit = when (intent.side) {
                Side.BUY -> intent.stopLoss < l && l < intent.takeProfit
                Side.SELL -> intent.stopLoss > l && l > intent.takeProfit
            }
            if (!validLimit) {
                return OrderAck(accepted = false, brokerRef = null, reason = "limit price outside bracket for ${intent.side}")
            }
        }
        pending[intent.symbol] = Pending(intent)
        return OrderAck(accepted = true, brokerRef = "sim-${intent.symbol}-${intent.decisionTime}")
    }

    /**
     * Process one management-timeframe candle for its symbol. Order inside the
     * candle: (1) fill a pending entry at the open, (2) resolve exits — a
     * position entered at this candle's open is immediately exposed to this
     * candle's range (stop-first).
     */
    fun onCandle(candle: Candle) {
        pending[candle.symbol]?.let { p ->
            // Lookahead guard: only candles opening at/after the decision.
            if (candle.openTime < p.intent.decisionTime) return@let
            when (p.intent.entryType) {
                EntryType.MARKET -> {
                    pending.remove(candle.symbol)
                    val entry = candle.open
                    val risk = when (p.intent.side) {
                        Side.BUY -> entry - p.intent.stopLoss
                        Side.SELL -> p.intent.stopLoss - entry
                    }
                    if (risk <= 0) {
                        // Gapped through the stop before entry — reject rather
                        // than open an instantly-dead position.
                        return@let
                    }
                    open[candle.symbol] = Open(p.intent, candle.openTime, entry, risk)
                }
                EntryType.LIMIT -> {
                    val i = p.intent
                    val l = i.limitPrice!!
                    val openedBeyondStop = when (i.side) {
                        Side.BUY -> candle.open <= i.stopLoss
                        Side.SELL -> candle.open >= i.stopLoss
                    }
                    if (openedBeyondStop) {
                        cancel(p, candle, CancelReason.INVALIDATED)
                        return@let
                    }
                    val touched = when (i.side) {
                        Side.BUY -> candle.low <= l
                        Side.SELL -> candle.high >= l
                    }
                    if (touched) {
                        pending.remove(candle.symbol)
                        // Fill AT the limit, never better, even if the open
                        // gapped past it (conservative by policy).
                        val risk = when (i.side) {
                            Side.BUY -> l - i.stopLoss
                            Side.SELL -> i.stopLoss - l
                        }
                        open[candle.symbol] = Open(i, candle.openTime, l, risk)
                    } else {
                        p.candlesSeen++
                        val expiry = i.expiryCandles
                        if (expiry != null && p.candlesSeen >= expiry) {
                            cancel(p, candle, CancelReason.EXPIRED)
                        }
                        return@let
                    }
                }
            }
        }

        val pos = open[candle.symbol] ?: return
        val i = pos.intent
        val (hitStop, hitTarget) = when (i.side) {
            Side.BUY -> Pair(candle.low <= i.stopLoss, candle.high >= i.takeProfit)
            Side.SELL -> Pair(candle.high >= i.stopLoss, candle.low <= i.takeProfit)
        }
        when {
            hitStop -> { // stop-first, even if the target was also touched
                val gappedThrough = when (i.side) {
                    Side.BUY -> candle.open < i.stopLoss
                    Side.SELL -> candle.open > i.stopLoss
                }
                close(candle, pos, if (gappedThrough) candle.open else i.stopLoss, ExitReason.STOP)
            }
            hitTarget -> close(candle, pos, i.takeProfit, ExitReason.TARGET)
        }
    }

    private fun cancel(p: Pending, candle: Candle, reason: CancelReason) {
        pending.remove(p.intent.symbol)
        _cancelled.add(
            CancelledOrder(
                symbol = p.intent.symbol,
                side = p.intent.side,
                decisionTime = p.intent.decisionTime,
                limitPrice = p.intent.limitPrice,
                cancelTime = candle.openTime,
                reason = reason,
                candlesLived = p.candlesSeen,
            )
        )
    }

    /** Flatten any open/pending exposure at the last seen price (end of replay). */
    fun closeAll(lastCandles: Map<String, Candle>) {
        pending.clear()
        for ((symbol, pos) in open.entries.toList()) {
            val last = lastCandles[symbol] ?: continue
            close(last, pos, last.close, ExitReason.END_OF_DATA)
        }
    }

    private fun close(candle: Candle, pos: Open, exitPrice: Double, reason: ExitReason) {
        val i = pos.intent
        val dir = if (i.side == Side.BUY) 1.0 else -1.0
        val gross = dir * (exitPrice - pos.entryPrice)
        val spread = spreadBySymbol[i.symbol] ?: 0.0
        val net = gross - spread
        _closed.add(
            ClosedTrade(
                symbol = i.symbol,
                side = i.side,
                decisionTime = i.decisionTime,
                entryTime = pos.entryTime,
                entryPrice = pos.entryPrice,
                exitTime = candle.openTime,
                exitPrice = exitPrice,
                stopLoss = i.stopLoss,
                takeProfit = i.takeProfit,
                reason = reason,
                grossMove = gross,
                netMove = net,
                rMultiple = if (pos.risk > 0) net / pos.risk else 0.0,
            )
        )
        open.remove(i.symbol)
    }
}
