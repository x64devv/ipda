package ipda.backtest

import ipda.broker.Side
import ipda.broker.SimBroker
import ipda.config.DisplacementConfig
import ipda.config.IpdaConfig
import ipda.detect.SessionTagger
import ipda.detect.TestCandles.h1
import ipda.feed.FeedReplayer
import ipda.log.InMemoryEventLog
import ipda.model.Timeframe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end on synthetic candles: the MSS displacement scenario from
 * DisplacementTest, extended with two more candles so the trade fills at the
 * next open and rides to its target. No network, fully deterministic.
 */
class BacktestEngineTest {

    private val cfg = IpdaConfig(
        instruments = listOf("EURUSD"),
        displacement = DisplacementConfig(atrPeriod = 3),
    )

    // MSS scenario: swing low 1.1985 (bar 2, confirmed bar 4); bearish burst
    // bars 5-6 fires MSS displacement at close of bar 6 (signal close 1.1943,
    // leg extremeHigh = 1.2006 -> stop; tp = 1.1943 - 2*(1.2006-1.1943) = 1.1817).
    private val candles = listOf(
        h1(0, 1.2010, 1.2011, 1.2000, 1.2005),
        h1(1, 1.2005, 1.2006, 1.1995, 1.2000),
        h1(2, 1.2000, 1.2001, 1.1985, 1.1990),
        h1(3, 1.1990, 1.2001, 1.1990, 1.2000),
        h1(4, 1.2000, 1.2006, 1.1999, 1.2005),
        h1(5, 1.2005, 1.2006, 1.1975, 1.1977),
        h1(6, 1.1977, 1.1978, 1.1940, 1.1943), // signal close
        h1(7, 1.1943, 1.1950, 1.1900, 1.1910), // entry fills at 1.1943; bracket untouched
        h1(8, 1.1910, 1.1915, 1.1800, 1.1810), // low crosses tp 1.1817 -> TARGET
    )

    private fun runEngine(): Pair<BacktestResult, InMemoryEventLog> {
        val log = InMemoryEventLog()
        val engine = BacktestEngine(
            cfg = cfg,
            configHash = "cfg-test-hash",
            snapshotId = "snap-test",
            strategy = BaselineDisplacementStrategy(cfg.baseline, SessionTagger(cfg.sessions)),
            eventLog = log,
        )
        val result = engine.run(FeedReplayer(mapOf(("EURUSD" to Timeframe.H1) to candles)))
        return result to log
    }

    @Test
    fun `MSS displacement produces one short trade that rides to target`() {
        val (result, _) = runEngine()
        assertEquals(9, result.candlesProcessed)

        val t = result.trades.single()
        assertEquals(Side.SELL, t.side)
        assertEquals(h1(6, 1.0, 1.0, 1.0, 1.0).closeTime, t.decisionTime)  // decided at close of bar 6
        assertEquals(h1(7, 1.0, 1.0, 1.0, 1.0).openTime, t.entryTime)      // filled next open
        assertEquals(1.1943, t.entryPrice)
        assertEquals(1.2006, t.stopLoss)
        assertEquals(1.1817, t.takeProfit, 1e-9)
        assertEquals(SimBroker.ExitReason.TARGET, t.reason)
        assertEquals(1.1817, t.exitPrice, 1e-9)
        // gross = 1.1943 - 1.1817 = 0.0126; net = gross - 0.7 pip spread
        assertEquals(0.0126, t.grossMove, 1e-9)
        assertEquals(0.0126 - 0.00007, t.netMove, 1e-9)
        assertTrue(t.rMultiple > 1.9)

        val stats = Stats.of(result.trades)
        assertEquals(1, stats.wins)
        assertEquals(1.0, stats.winRate)
    }

    @Test
    fun `run identity is deterministic and every leg evaluation is logged`() {
        val (r1, log1) = runEngine()
        val (r2, log2) = runEngine()
        assertEquals(r1.runId, r2.runId)
        assertTrue(r1.runId.startsWith("run-"))

        val evals = log1.records.count { it.first == "leg_eval" }
        assertTrue(evals > 0)
        assertEquals(log1.records, log2.records) // byte-identical event stream
        assertEquals(1, log1.records.count { it.first == "displacement" })
        assertEquals(1, log1.records.count { it.first == "order_intent" })
    }

    @Test
    fun `M15 candles flow through the stream without affecting the H1 baseline`() {
        val log = InMemoryEventLog()
        val engine = BacktestEngine(
            cfg, "cfg-test-hash", "snap-test",
            BaselineDisplacementStrategy(cfg.baseline, SessionTagger(cfg.sessions)), log,
        )
        // Add an M15 series alongside — same trade must come out.
        val m15 = (0 until 36).map { i ->
            ipda.model.Candle(
                "EURUSD", Timeframe.M15,
                ipda.detect.TestCandles.T0.plusSeconds(900L * i),
                1.2, 1.2, 1.2, 1.2,
            )
        }
        val result = engine.run(
            FeedReplayer(
                mapOf(
                    ("EURUSD" to Timeframe.H1) to candles,
                    ("EURUSD" to Timeframe.M15) to m15,
                )
            )
        )
        assertEquals(45, result.candlesProcessed)
        assertEquals(1, result.trades.size)
        assertEquals(SimBroker.ExitReason.TARGET, result.trades.single().reason)
    }
}
