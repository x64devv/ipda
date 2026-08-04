package ipda.live

import ipda.backtest.BaselineDisplacementStrategy
import ipda.backtest.CODE_VERSION
import ipda.config.ConfigLoader
import ipda.ctrader.FatalConfigException
import ipda.detect.SessionTagger
import ipda.log.JsonlEventLog
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * LIVE demo loop CLI (milestone: live demo loop, 29 Jul 2026):
 *
 *   .\gradlew.bat live                          — demo account from secrets
 *   .\gradlew.bat live --args="--flatten-on-exit"
 *   .\gradlew.bat live --args="--account 48042139 --config config/ipda-config.json"
 *
 * Runs v1 (the measured survivor: every qualifying H1 MSS displacement,
 * market entry next open, leg-extreme stop, fixed 2R target, no filters, one
 * position per symbol) against the cTrader demo account, accumulating true
 * forward out-of-sample results plus real spread/fill measurements.
 *
 * Artifacts: runs/live-<account>-<UTC stamp>/{events.jsonl, trades.jsonl,
 * summary.txt} — the same shape as a backtest run, stamped (config hash,
 * LIVE-<account>, code version), so live vs backtest comparison is a diff.
 *
 * Ctrl-C: positions are LEFT OPEN with their server-side bracket (settled
 * 29 Jul 2026 — time-based exits would pollute the forward R distribution);
 * open state is logged and the next session adopts them via reconcile.
 * Pass --flatten-on-exit to close everything on shutdown instead.
 *
 * NOTE: needs demo.ctraderapi.com — runs on the local Windows machine only.
 */
fun main(args: Array<String>) {
    val configPath = Path.of(argValue(args, "--config") ?: "config/ipda-config.json")
    val secretsPath = Path.of(argValue(args, "--secrets") ?: "secrets.properties")
    val accountOverride = argValue(args, "--account")?.toLong()
    val flattenOnExit = "--flatten-on-exit" in args

    val loaded = ConfigLoader.load(configPath)
    val cfg = loaded.config
    val secrets = SecretsStore(secretsPath)

    // Defense in depth (management plane, 30 Jul 2026): trading REAL money
    // requires the explicit --live flag in addition to the live host in
    // secrets — a config accident alone can never reach live.ctraderapi.com.
    // The manager passes --live only for deployments armed via its
    // confirmation gate.
    val liveFlag = "--live" in args
    if (secrets.host == ipda.ctrader.OpenApiConnection.LIVE_HOST && !liveFlag) {
        System.err.println(
            "REFUSING: secrets host is ${secrets.host} (REAL MONEY) but --live was not passed.\n" +
            "Run with --live only for a deployment you have deliberately armed."
        )
        kotlin.system.exitProcess(2)
    }
    if (liveFlag && secrets.host != ipda.ctrader.OpenApiConnection.LIVE_HOST) {
        System.err.println("NOTE: --live passed but host is ${secrets.host} (demo) — proceeding on demo.")
    }

    val startedAt = Instant.now()
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(startedAt)
    val accountForName = accountOverride ?: secrets.accountId
    val runId = "live-${accountForName ?: "auto"}-$stamp"
    val runDir = Files.createDirectories(Path.of("runs", runId))

    val eventLog = JsonlEventLog(runDir.resolve("events.jsonl"))
    val tradesLog = JsonlEventLog(runDir.resolve("trades.jsonl"))

    val spreads = SpreadRecorder()
    val closedTrades = ArrayList<LiveBroker.ClosedLiveTrade>()

    lateinit var session: LiveSession

    fun writeSummary(openStateJson: String?) {
        val trades = synchronized(closedTrades) { closedTrades.toList() }
        val scored = trades.filter { it.rMultiple != null }
        val wins = trades.count { it.netMove > 0 }
        val losses = trades.count { it.netMove <= 0 }
        val grossWin = trades.filter { it.netMove > 0 }.sumOf { it.netMove }
        val grossLoss = -trades.filter { it.netMove < 0 }.sumOf { it.netMove }
        val pf = if (grossLoss > 0) grossWin / grossLoss else if (grossWin > 0) Double.POSITIVE_INFINITY else 0.0
        val avgR = if (scored.isNotEmpty()) scored.sumOf { it.rMultiple!! } / scored.size else 0.0
        val summary = buildString {
            appendLine("ipda LIVE demo loop (v1 — the measured survivor)")
            appendLine("run id:       $runId")
            appendLine("account:      LIVE-${session.accountId ?: accountForName ?: "?"} (${session.accountLabel ?: "not yet authenticated"})")
            appendLine("config hash:  ${loaded.hash}")
            appendLine("code version: $CODE_VERSION")
            appendLine("started:      $startedAt")
            appendLine("volume:       ${cfg.live.volumeLots} lots/trade default${if (cfg.live.volumeLotsBySymbol.isNotEmpty()) ", overrides ${cfg.live.volumeLotsBySymbol}" else ""}")
            appendLine("trades:       ${trades.size} (${wins}W/${losses}L${if (trades.any { it.adopted }) ", incl. adopted" else ""})")
            appendLine("net move:     ${"%.5f".format(trades.sumOf { it.netMove })} price units")
            appendLine("avg R:        ${"%.3f".format(avgR)} (over ${scored.size} scored)")
            appendLine("profitFactor: ${"%.3f".format(pf)}")
            val stats = spreads.stats()
            if (stats.isNotEmpty()) {
                appendLine()
                appendLine("observed spreads (sampled at execution-TF closes):")
                for ((symbol, s) in stats.entries.sortedBy { it.key }) {
                    appendLine("  $symbol: n=${s.samples} mean=${"%.5f".format(s.meanSpread)} max=${"%.5f".format(s.maxSpread)}")
                }
            }
            if (openStateJson != null) {
                appendLine()
                appendLine("open state at last update: $openStateJson")
            }
            appendLine()
            appendLine("NOTE: forward out-of-sample, real demo fills (two-sided prices, no")
            appendLine("synthetic spread haircut). Standing rule 5 still applies before any")
            appendLine("of this is believed — demo fills are optimistic at liquidity events.")
        }
        Files.writeString(runDir.resolve("summary.txt"), summary)
    }

    val broker = LiveBroker(
        cfg = cfg.live,
        eventLog = eventLog,
        spreads = spreads,
        tradeSink = { t ->
            synchronized(closedTrades) { closedTrades.add(t) }
            tradesLog.append(
                "trade",
                """{"symbol":"${t.symbol}","side":"${t.side}","decision":${t.decisionTime?.let { "\"$it\"" }},"entryTime":${t.entryTime?.let { "\"$it\"" }},"entry":${t.entryPrice},"exitTime":"${t.exitTime}","exit":${t.exitPrice},"stop":${t.stopLoss},"tp":${t.takeProfit},"reason":"${t.reason}","gross":${t.grossMove},"net":${t.netMove},"r":${t.rMultiple},"positionId":${t.positionId},"adopted":${t.adopted}}"""
            )
            runCatching { writeSummary(null) }
        },
    )

    session = LiveSession(
        cfg = cfg,
        secrets = secrets,
        eventLog = eventLog,
        broker = broker,
        spreads = spreads,
        accountOverride = accountOverride,
    )

    val strategy = BaselineDisplacementStrategy(cfg.baseline, SessionTagger(cfg.sessions), volumeLots = cfg.live.volumeLots)
    val engine = LiveEngine(cfg, strategy, eventLog, broker, spreads)

    eventLog.append(
        "live_run_meta",
        """{"runId":"$runId","configHash":"${loaded.hash}","codeVersion":"$CODE_VERSION","startedAt":"$startedAt","volumeLots":${cfg.live.volumeLots},"flattenOnExit":$flattenOnExit,"instruments":[${cfg.instruments.joinToString(",") { "\"$it\"" }}]}"""
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        val openState = broker.openStateJson()
        eventLog.append("live_shutdown", """{"openState":$openState,"flatten":$flattenOnExit}""")
        if (flattenOnExit) runCatching { broker.flattenAll() }
        runCatching { session.stop() }
        runCatching { writeSummary(openState) }
        runCatching { eventLog.close() }
        runCatching { tradesLog.close() }
        println("\nlive loop stopped — artifacts: ${runDir.toAbsolutePath()}")
    })

    println("ipda LIVE demo loop — ${secrets.host}, instruments=${cfg.instruments}, volume=${cfg.live.volumeLots} lots")
    println("run id:       $runId")
    println("config hash:  ${loaded.hash}")
    println("code version: $CODE_VERSION")
    println("shutdown:     Ctrl-C leaves positions open under their bracket${if (flattenOnExit) " (OVERRIDDEN: --flatten-on-exit)" else ""}")
    println("artifacts:    ${runDir.toAbsolutePath()}")
    println()

    writeSummary(null)

    // Blocks until Ctrl-C. The ONE thing that comes back out is a
    // misconfiguration the reconnect loop refused to retry — surface it on
    // stderr and exit non-zero so the container is marked failed rather than
    // sitting in a restart loop that looks alive (see FatalConfigException).
    try {
        engine.run(session)
    } catch (e: FatalConfigException) {
        System.err.println()
        System.err.println("FATAL CONFIGURATION ERROR — the live loop stopped and will not retry:")
        System.err.println()
        System.err.println(e.message)
        System.err.println()
        System.err.println("Fix the deployment configuration, then Restart. Artifacts: ${runDir.toAbsolutePath()}")
        kotlin.system.exitProcess(3)
    }
}

private fun argValue(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
