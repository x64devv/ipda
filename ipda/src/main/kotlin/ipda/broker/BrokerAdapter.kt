package ipda.broker

import java.time.Instant

/**
 * Thin broker seam (§8.1). SimBroker (backtest) and the cTrader Open API
 * adapter (live) implement the SAME interface — the strategy layer cannot
 * tell which one it is talking to.
 *
 * v1 fill model (conservative by policy, §9.2): evaluate at candle close,
 * enter at next candle open with spread applied; exits resolve intra-candle
 * under the stop-first ambiguity rule. This interface will grow with the
 * SimBroker milestone; kept minimal until then.
 */
interface BrokerAdapter {
    fun submit(intent: OrderIntent): OrderAck
}

enum class Side { BUY, SELL }

/**
 * Entry mechanics (v1 + M15 refinement, settled 29 Jul 2026):
 *  - MARKET: fill at the open of the first eligible execution candle (v0).
 *  - LIMIT: rest at [OrderIntent.limitPrice] until filled, expired, or
 *    invalidated (see SimBroker for the conservative fill conventions).
 */
enum class EntryType { MARKET, LIMIT }

/** A bracket order intent: entry + protective stop + target, one unit of decision. */
data class OrderIntent(
    val symbol: String,
    val side: Side,
    val volumeLots: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    /** The candle close that produced this decision — also the eligibility
     *  cut-off: only candles OPENING at/after this instant can fill it. */
    val decisionTime: Instant,
    val entryType: EntryType = EntryType.MARKET,
    /** Resting price for LIMIT entries (BUY below market, SELL above). */
    val limitPrice: Double? = null,
    /** LIMIT lifetime in eligible execution candles; null = no expiry. */
    val expiryCandles: Int? = null,
    /**
     * The price the decision was made against (signal close). Ignored by
     * SimBroker (fills are candle-driven); the LIVE adapter uses it to size
     * the provisional relative bracket on the market order and to measure
     * decision→fill slippage (standing rule 5).
     */
    val decisionPrice: Double? = null,
)

data class OrderAck(
    val accepted: Boolean,
    val brokerRef: String?,
    val reason: String? = null,
)
