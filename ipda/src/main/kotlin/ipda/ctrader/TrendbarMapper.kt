package ipda.ctrader

import com.xtrader.protocol.openapi.v2.model.ProtoOATrendbar
import com.xtrader.protocol.openapi.v2.model.ProtoOATrendbarPeriod
import ipda.model.Candle
import ipda.model.Timeframe
import java.time.Instant

/**
 * ProtoOATrendbar → engine model, and the raw integer form kept for storage.
 *
 * Wire semantics (proto comments + help.ctrader.com/open-api/symbol-data):
 *  - prices are int64/uint64 in 1/100000 price units; real price = value / 100000
 *  - low is absolute; open = low + deltaOpen, close = low + deltaClose,
 *    high = low + deltaHigh
 *  - utcTimestampInMinutes is the UTC open time of the bar, in minutes
 */
object TrendbarMapper {

    const val PRICE_SCALE = 100_000L

    fun toPeriod(tf: Timeframe): ProtoOATrendbarPeriod = when (tf) {
        Timeframe.M1 -> ProtoOATrendbarPeriod.M1
        Timeframe.M5 -> ProtoOATrendbarPeriod.M5
        Timeframe.M15 -> ProtoOATrendbarPeriod.M15
        Timeframe.H1 -> ProtoOATrendbarPeriod.H1
        Timeframe.H4 -> ProtoOATrendbarPeriod.H4
        Timeframe.D1 -> ProtoOATrendbarPeriod.D1
    }

    /** Inverse of [toPeriod]; null for periods the engine does not model. */
    fun fromPeriod(period: ProtoOATrendbarPeriod): Timeframe? = when (period) {
        ProtoOATrendbarPeriod.M1 -> Timeframe.M1
        ProtoOATrendbarPeriod.M5 -> Timeframe.M5
        ProtoOATrendbarPeriod.M15 -> Timeframe.M15
        ProtoOATrendbarPeriod.H1 -> Timeframe.H1
        ProtoOATrendbarPeriod.H4 -> Timeframe.H4
        ProtoOATrendbarPeriod.D1 -> Timeframe.D1
        else -> null
    }

    /** Raw storage form — exact integers, no floating point until load time. */
    data class RawBar(
        val openTimeMs: Long,
        val open1e5: Long,
        val high1e5: Long,
        val low1e5: Long,
        val close1e5: Long,
        val volume: Long,
    )

    fun toRaw(tb: ProtoOATrendbar): RawBar {
        require(tb.hasLow() && tb.hasUtcTimestampInMinutes()) { "Trendbar missing low/timestamp" }
        val low = tb.low
        return RawBar(
            openTimeMs = tb.utcTimestampInMinutes.toLong() * 60_000L,
            open1e5 = low + tb.deltaOpen,
            high1e5 = low + tb.deltaHigh,
            low1e5 = low,
            close1e5 = low + tb.deltaClose,
            volume = tb.volume,
        )
    }

    fun toCandle(raw: RawBar, symbol: String, tf: Timeframe): Candle = Candle(
        symbol = symbol,
        timeframe = tf,
        openTime = Instant.ofEpochMilli(raw.openTimeMs),
        open = raw.open1e5.toDouble() / PRICE_SCALE,
        high = raw.high1e5.toDouble() / PRICE_SCALE,
        low = raw.low1e5.toDouble() / PRICE_SCALE,
        close = raw.close1e5.toDouble() / PRICE_SCALE,
        volume = raw.volume,
    )

    /**
     * Completed-candles-only rule (standing): a bar is complete iff its close
     * time is at or before [asOf]. The feed's current forming bar must never
     * reach detection.
     */
    fun isComplete(raw: RawBar, tf: Timeframe, asOf: Instant): Boolean =
        raw.openTimeMs + tf.duration.toMillis() <= asOf.toEpochMilli()
}
