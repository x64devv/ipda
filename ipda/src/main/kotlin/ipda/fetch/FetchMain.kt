package ipda.fetch

import ipda.config.ConfigLoader
import ipda.ctrader.CTraderClient
import ipda.ctrader.FatalConfigException
import ipda.ctrader.accountGrantHelp
import ipda.ctrader.OpenApiConnection
import ipda.data.SnapshotStore
import ipda.model.Timeframe
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Properties

/**
 * History fetcher CLI — milestone 1B.
 *
 *   ./gradlew fetch --args="--days 730"
 *
 * Reads credentials from secrets.properties (gitignored — never commit it):
 *   clientId=...          from openapi.ctrader.com
 *   clientSecret=...
 *   accessToken=...       from the app's Playground/Sandbox ("Get token", scope=accounts is enough for data)
 *   refreshToken=...      optional; printed replacement tokens on refresh
 *   accountId=...         optional; defaults to the first DEMO account on the token
 *   host=...              optional; defaults to demo.ctraderapi.com
 *
 * Fetches H1 + M15 (bias + entry timeframes from config) for the configured
 * instruments, completed bars only, and writes one immutable checksummed
 * snapshot to data/snapshots.db. Prints the snapshot id — backtests record it.
 */
fun main(args: Array<String>) {
    val days = argValue(args, "--days")?.toLong() ?: 730L
    val dbPath = Path.of(argValue(args, "--db") ?: "data/snapshots.db")
    val secretsPath = Path.of(argValue(args, "--secrets") ?: "secrets.properties")
    val configPath = Path.of(argValue(args, "--config") ?: "config/ipda-config.json")

    val secrets = Properties().apply {
        require(Files.exists(secretsPath)) {
            "Missing $secretsPath — copy secrets.properties.example and fill in credentials."
        }
        Files.newBufferedReader(secretsPath).use { load(it) }
    }
    val clientId = secrets.required("clientId")
    val clientSecret = secrets.required("clientSecret")
    val accessToken = secrets.required("accessToken")
    val host = secrets.getProperty("host", OpenApiConnection.DEMO_HOST)

    val loaded = ConfigLoader.load(configPath)
    val cfg = loaded.config
    val timeframes = listOf(cfg.biasTimeframe, cfg.entryTimeframe).distinct()

    println("ipda fetch — $host, instruments=${cfg.instruments}, timeframes=$timeframes, days=$days")
    println("config hash: ${loaded.hash}")

    OpenApiConnection.connect(host).use { rawConn ->
        val client = CTraderClient(rawConn)
        client.applicationAuth(clientId, clientSecret)
        println("application authenticated")

        val accounts = client.accountsByToken(accessToken)
        require(accounts.isNotEmpty()) {
            "Access token grants no accounts — generate a token in the app Playground with your account approved."
        }
        val chosen = argValue(args, "--account")?.toLong()
            ?: secrets.getProperty("accountId")?.toLongOrNull()
            ?: accounts.first { !(it.hasIsLive() && it.isLive) }.ctidTraderAccountId
        val acct = accounts.firstOrNull { it.ctidTraderAccountId == chosen }
            ?: throw FatalConfigException(accountGrantHelp(accounts, chosen))
        require(!(acct.hasIsLive() && acct.isLive) || host == OpenApiConnection.LIVE_HOST) {
            "Account $chosen is LIVE but host is demo — refusing (environments are isolated)."
        }
        client.accountAuth(chosen, accessToken)
        println("account $chosen authenticated (${if (acct.hasBrokerTitleShort()) acct.brokerTitleShort else "?"}, login ${if (acct.hasTraderLogin()) acct.traderLogin else "?"})")

        val symbolIds = client.symbolIdsByName(chosen)
        val now = Instant.now()
        val series = LinkedHashMap<Pair<String, Timeframe>, List<ipda.ctrader.TrendbarMapper.RawBar>>()

        for (instrument in cfg.instruments) {
            val symbolId = symbolIds[instrument.uppercase()]
                ?: error("Symbol $instrument not found on account $chosen. Available FX-looking names: ${symbolIds.keys.filter { it.length == 6 }.sorted().take(30)}")
            for (tf in timeframes) {
                val to = client.lastCompletedBoundary(tf, now)
                val from = to.minus(Duration.ofDays(days))
                print("fetching $instrument $tf ${from} -> ${to} ")
                val bars = client.fetchTrendbars(
                    chosen, symbolId, tf, from.toEpochMilli(), to.toEpochMilli(),
                    onProgress = { done, total -> print("\rfetching $instrument $tf: ${done * 100 / total}%   ") },
                ).filter { ipda.ctrader.TrendbarMapper.isComplete(it, tf, now) }
                println("\r$instrument $tf: ${bars.size} completed bars                    ")
                series[instrument to tf] = bars
            }
        }

        SnapshotStore(dbPath).use { store ->
            val info = store.writeSnapshot(source = "ctrader:$host", series = series)
            println()
            println(if (info.alreadyExisted) "snapshot already existed (identical content):" else "snapshot written:")
            println("  id:       ${info.id}")
            println("  checksum: ${info.checksum}")
            println("  bars:     ${info.barCount}")
            println("  db:       ${dbPath.toAbsolutePath()}")
            check(store.verify(info.id)) { "post-write checksum verification failed" }
            println("  verified: true")
        }
    }
}

private fun argValue(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

private fun Properties.required(key: String): String =
    getProperty(key)?.takeIf { it.isNotBlank() && !it.startsWith("CHANGE_ME") }
        ?: error("secrets.properties is missing '$key'")
