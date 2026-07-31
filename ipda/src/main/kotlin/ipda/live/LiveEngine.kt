package ipda.live

import ipda.backtest.DecisionPipeline
import ipda.backtest.Strategy
import ipda.broker.BrokerAdapter
import ipda.config.IpdaConfig
import ipda.feed.Feed
import ipda.log.EventLog

/**
 * LIVE engine — the same decision path as the backtest ([DecisionPipeline]),
 * fed by the live [Feed] seam, submitting through the live [BrokerAdapter].
 * There is deliberately NO exit management here: exits are the server-side
 * bracket's job; closing fills come back through the broker's execution-event
 * path and land in trades.jsonl.
 *
 * Per candle:
 *  - execution-TF close → spread_sample (the regular bid/ask series that
 *    replaces the fixed-pips spread assumption, standing rule 5);
 *  - bias-TF close → detectors → strategy → submit (identical code to replay).
 */
class LiveEngine(
    private val cfg: IpdaConfig,
    strategy: Strategy,
    private val eventLog: EventLog,
    broker: BrokerAdapter,
    private val spreads: SpreadRecorder,
) {
    private val pipeline = DecisionPipeline(cfg, strategy, eventLog) { intent -> broker.submit(intent) }

    var candlesProcessed: Long = 0
        private set

    /** Blocks until the feed stops (live: until shutdown). */
    fun run(feed: Feed) {
        feed.run { candle ->
            candlesProcessed++
            if (candle.timeframe == cfg.execution.executionTimeframe) {
                spreads.sampleAtClose(candle.symbol)?.let { q ->
                    eventLog.append(
                        "spread_sample",
                        """{"symbol":"${candle.symbol}","closeTime":"${candle.closeTime}","bid":${q.bid},"ask":${q.ask},"spread":${q.spread},"quoteAgeMs":${(candle.closeTime.toEpochMilli() - q.timestampMs)}}"""
                    )
                }
            }
            if (candle.timeframe == cfg.displacement.timeframe) {
                pipeline.onBiasCandle(candle)
            }
        }
    }
}
