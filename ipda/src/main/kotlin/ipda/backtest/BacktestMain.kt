package ipda.backtest

import ipda.config.ConfigLoader
import ipda.data.SnapshotStore
import ipda.detect.SessionTagger
import ipda.feed.FeedReplayer
import ipda.log.JsonlEventLog
import ipda.model.Candle
import ipda.model.Timeframe
import java.nio.file.Files
import java.nio.file.Path

/**
 * H1-only baseline backtest CLI:
 *
 *   ./gradlew backtest --args="--snapshot snap-xxxxxxxxxxxx"
 *
 * Loads the snapshot, replays it through the engine with the v0 baseline
 * strategy, writes runs/<runId>/{events.jsonl, trades.jsonl, summary.txt}.
 * Run identity (config hash, snapshot id, code version) is printed and
 * stamped into the artifacts — any result is reproducible from its triple.
 */
fun main(args: Array<String>) {
    val snapshotId = argValue(args, "--snapshot") ?: error("--snapshot snap-... is required")
    val dbPath = Path.of(argValue(args, "--db") ?: "data/snapshots.db")
    val configPath = Path.of(argValue(args, "--config") ?: "config/ipda-config.json")

    val loaded = ConfigLoader.load(configPath)
    val cfg = loaded.config

    SnapshotStore(dbPath).use { store ->
        check(store.verify(snapshotId)) { "Snapshot $snapshotId missing or failed checksum verification" }

        val series = LinkedHashMap<Pair<String, Timeframe>, List<Candle>>()
        for (instrument in cfg.instruments) {
            for (tf in listOf(cfg.biasTimeframe, cfg.entryTimeframe).distinct()) {
                val candles = store.loadCandles(snapshotId, instrument, tf)
                if (candles.isNotEmpty()) series[instrument to tf] = candles
            }
        }
        require(series.isNotEmpty()) { "Snapshot $snapshotId holds no candles for ${cfg.instruments}" }

        val strategy = BaselineDisplacementStrategy(cfg.baseline, SessionTagger(cfg.sessions))
        val runDirPrep = { runId: String -> Files.createDirectories(Path.of("runs", runId)) }

        // Engine needs the log before the run id is known to callers; run id is
        // deterministic from identity, so compute the same way here.
        val runId = "run-" + ConfigLoader.sha256Hex("${loaded.hash}|$snapshotId|$CODE_VERSION".toByteArray()).take(12)
        val runDir = runDirPrep(runId)

        val result = JsonlEventLog(runDir.resolve("events.jsonl")).use { log ->
            val engine = BacktestEngine(cfg, loaded.hash, snapshotId, strategy, log)
            engine.run(FeedReplayer(series))
        }

        JsonlEventLog(runDir.resolve("trades.jsonl")).use { tlog ->
            for (t in result.trades) {
                tlog.append(
                    "trade",
                    """{"symbol":"${t.symbol}","side":"${t.side}","decision":"${t.decisionTime}","entryTime":"${t.entryTime}","entry":${t.entryPrice},"exitTime":"${t.exitTime}","exit":${t.exitPrice},"stop":${t.stopLoss},"tp":${t.takeProfit},"reason":"${t.reason}","gross":${t.grossMove},"net":${t.netMove},"r":${t.rMultiple}}"""
                )
            }
        }

        val stats = Stats.of(result.trades)
        val b = cfg.baseline
        val summary = buildString {
            appendLine("ipda H1-only baseline backtest")
            appendLine("layers:       requireMss=${b.requireMss} requireSweep=${b.requireSweep}(N=${b.sweepLookbackCandles}) premiumDiscountOnly=${b.premiumDiscountOnly} killzoneOnly=${b.killzoneOnly}")
            appendLine("entry:        mode=${b.entryMode} expiry=${b.entryExpiryCandles} pdAtEntry=${b.pdAtEntry} execTf=${cfg.execution.executionTimeframe}")
            appendLine("run id:       ${result.runId}")
            appendLine("config hash:  ${result.configHash}")
            appendLine("snapshot id:  ${result.snapshotId}")
            appendLine("code version: ${result.codeVersion}")
            appendLine("candles:      ${result.candlesProcessed}")
            appendLine("trades:       ${stats.trades} (${stats.wins}W/${stats.losses}L, winRate=${"%.1f".format(stats.winRate * 100)}%)")
            appendLine("net move:     ${"%.5f".format(stats.netMoveTotal)} price units")
            appendLine("avg R:        ${"%.3f".format(stats.avgR)}")
            appendLine("profitFactor: ${"%.3f".format(stats.profitFactor)}")
            appendLine()
            appendLine("NOTE: v0 baseline reference strategy; demo-quality fills already")
            appendLine("haircut by configured spread; slippage haircut still to be applied")
            appendLine("before believing anything (standing rule 5).")
        }
        Files.writeString(runDir.resolve("summary.txt"), summary)
        println(summary)
        println("artifacts: ${runDir.toAbsolutePath()}")
    }
}

private fun argValue(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
