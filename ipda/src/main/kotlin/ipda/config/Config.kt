package ipda.config

import ipda.model.Timeframe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Single versioned config file. ALL magic numbers live here (standing rule 3).
 * The config hash — SHA-256 of the raw file bytes — is one third of run
 * identity: (config hash, snapshot id, code version).
 */

@Serializable
data class IpdaConfig(
    /** Bump manually on any semantic change; the byte-hash catches everything else. */
    val configVersion: Int = 1,

    /** Phase 1: EURUSD + GBPUSD only. Widening later is a config change, not code. */
    val instruments: List<String> = listOf("EURUSD", "GBPUSD"),

    /** H1 bias -> M15 entry. Validation runs H1-only first. */
    val biasTimeframe: Timeframe = Timeframe.H1,
    val entryTimeframe: Timeframe = Timeframe.M15,

    val displacement: DisplacementConfig = DisplacementConfig(),
    val swings: SwingConfig = SwingConfig(),
    val sessions: List<SessionDef> = defaultSessionTable(),

    /** Tz used to derive daily boundaries (ICT NY-midnight convention). */
    val dailyBoundaryZone: String = "America/New_York",

    val baseline: BaselineStrategyConfig = BaselineStrategyConfig(),
    val execution: ExecutionConfig = ExecutionConfig(),
    val liquidity: LiquidityConfig = LiquidityConfig(),
    val orderBlocks: OrderBlockConfig = OrderBlockConfig(),
    val live: LiveConfig = LiveConfig(),
)

/**
 * Live demo loop parameters (milestone: live demo loop, 29 Jul 2026). All
 * live magic numbers live here (standing rule 3). Every field has a default
 * so the CONTROL config file parses unchanged — same bytes, same config hash
 * (`95231af4…`), unbroken run-identity lineage.
 *
 * These parameters affect only live operation; backtest behaviour is
 * independent of them by construction.
 */
@Serializable
data class LiveConfig(
    /** Default order size per trade, in lots (settled 29 Jul 2026: 0.10 on demo). */
    val volumeLots: Double = 0.10,
    /** Per-symbol overrides of [volumeLots] (management-plane feature, 30 Jul 2026). */
    val volumeLotsBySymbol: Map<String, Double> = emptyMap(),
    /** Units per lot (FX standard 100k base units). */
    val lotUnits: Long = 100_000,
    /** History pulled through the feed seam at startup to warm detectors (ATR, swings, context). */
    val warmupDays: Long = 30,
    /**
     * Wall-clock grace after a bar's close before the forming bar is
     * finalized without a roll tick (covers clock skew + delivery latency;
     * also the only completion path into weekend/holiday silence).
     */
    val barGraceSeconds: Long = 10,
    /**
     * Completed bars are held this long so all series sharing the close time
     * can be emitted together in canonical order (wire jitter is real).
     * Bounded decision latency: signals fire at most this much after the
     * completing tick.
     */
    val emitSettleMillis: Long = 2000,
    /**
     * Staleness guard: intents whose decisionTime is older than this are
     * REJECTED by the live broker (they can only arise from warmup/catch-up
     * replay or severe delays — never trade a stale signal).
     */
    val maxDecisionAgeSeconds: Long = 180,
    /**
     * A pending ENTRY older than this is reaped (market orders fill or error
     * within seconds; a stuck pending would block its symbol's slot forever).
     */
    val pendingEntryTimeoutSeconds: Long = 60,
    /** Reconnect backoff bounds (exponential, doubling). */
    val reconnectMinBackoffSeconds: Long = 5,
    val reconnectMaxBackoffSeconds: Long = 300,
    /** Deadline for correlated request/response calls on the live connection. */
    val requestTimeoutMillis: Long = 30_000,
    /** Outbound ProtoHeartbeatEvent cadence (API requires ≤~10s inactivity). */
    val heartbeatIntervalSeconds: Long = 10,
)

@Serializable
data class LiquidityConfig(
    /** Equal-highs/lows cluster tolerance, in pips (converted per symbol via execution.pipSize). */
    val equalLevelTolerancePips: Double = 1.0,
)

@Serializable
data class OrderBlockConfig(
    /** How far back (candles) to search for the last opposing candle. */
    val lookback: Int = 10,
    /** Zone = candle body instead of full range. */
    val useBodyOnly: Boolean = false,
)

/**
 * v0 BASELINE strategy parameters — a deliberately crude reference strategy
 * (trade qualifying H1 displacements with fixed R-multiple brackets) whose
 * only job is the H1-only baseline measurement (§9.1). NOT a settled design
 * decision; the real entry model comes later and its contribution is measured
 * against this.
 */
@Serializable
data class BaselineStrategyConfig(
    /** Only take displacements classified MSS (vs all qualifying legs). */
    val requireMss: Boolean = true,
    /** Target = rMultiple × initial risk. */
    val rMultiple: Double = 2.0,
    /** Only take signals whose candle opened inside a configured killzone. */
    val killzoneOnly: Boolean = false,
    val killzones: List<String> = listOf("LONDON_KZ", "NY_KZ"),

    // --- v1 context layers (settled 29 Jul 2026, strategy-design-brief D1) ---
    // Each independently toggleable so A -> B -> B+C are measured as deltas
    // against the v0 control on the same snapshot. Defaults OFF: the control
    // config file parses unchanged (same bytes -> same config hash).

    /**
     * Layer B: require an opposing liquidity sweep (pool or prior confirmed
     * swing extreme) within [sweepLookbackCandles] bias candles — buyside
     * taken before a short, sellside taken before a long.
     */
    val requireSweep: Boolean = false,
    /** N for layer B. Continuous candles-since-sweep is logged on every signal, so N stays a query. */
    val sweepLookbackCandles: Int = 24,
    /**
     * Layer C: shorts only from PREMIUM, longs only from DISCOUNT
     * (DealingRangeTracker at signal close). EQUILIBRIUM or no range yet
     * fails both directions. Zero new numbers.
     * REJECTED by measurement 29 Jul 2026 (structurally zero trades at the
     * MSS close) — kept as a toggle for reproducibility.
     */
    val premiumDiscountOnly: Boolean = false,

    // --- M15 retracement entry (settled 29 Jul 2026, round 2) ---

    /**
     * MARKET_NEXT_OPEN = v0 (fill at next execution-candle open).
     * FVG_MIDPOINT_LIMIT = limit at the midpoint of the displacement FVG
     * nearest the signal close; requires execution.executionTimeframe finer
     * than the bias TF to mean anything.
     */
    val entryMode: EntryMode = EntryMode.MARKET_NEXT_OPEN,
    /** Unfilled-limit expiry, in execution-TF candles. */
    val entryExpiryCandles: Int = 24,
    /**
     * Premium/discount evaluated at the LIMIT LEVEL at placement (coherent
     * there, unlike at the MSS close — round-1 finding): longs need the level
     * in DISCOUNT, shorts in PREMIUM. Separate toggle so the entry mechanics
     * and the filter are measured as separate deltas.
     */
    val pdAtEntry: Boolean = false,
)

@Serializable
enum class EntryMode { MARKET_NEXT_OPEN, FVG_MIDPOINT_LIMIT }

@Serializable
data class ExecutionConfig(
    /** Round-trip spread haircut per symbol, in pips. */
    val spreadPips: Map<String, Double> = mapOf("EURUSD" to 0.7, "GBPUSD" to 1.0),
    /** Pip size per symbol (price units per pip). */
    val pipSize: Map<String, Double> = mapOf("EURUSD" to 0.0001, "GBPUSD" to 0.0001),
    /**
     * Timeframe whose candles drive fills and exits (the broker's management
     * TF). H1 = v0 baseline behaviour; M15 = the refinement phase. Finer
     * execution alone changes exit resolution (fewer both-touched candles),
     * so it is measured as its own delta before any entry-mechanics change.
     */
    val executionTimeframe: Timeframe = Timeframe.H1,
) {
    fun spreadPrice(symbol: String): Double =
        (spreadPips[symbol] ?: 0.0) * (pipSize[symbol] ?: 0.0001)
}

@Serializable
data class DisplacementConfig(
    /** Detection timeframe T. Stays a parameter — nothing hard-codes H1. */
    val timeframe: Timeframe = Timeframe.H1,
    /** Condition A: R >= k1 * ATR(atrPeriod), ATR as of the candle BEFORE the leg. */
    val k1: Double = 2.0,
    val atrPeriod: Int = 20,
    /** ATR smoothing: SMA (simple mean of true ranges) or WILDER. v1 default: SMA. */
    val atrMethod: AtrMethod = AtrMethod.SMA,
    /** Condition B: |close(j) - open(i)| / R >= bodyRatio. */
    val bodyRatio: Double = 0.65,
    /** Condition C is structural (>=1 directional FVG in the leg) — no parameter. */
    /** Condition D: leg length <= speedCapCandles. Toggleable, ON by default. */
    val speedCapEnabled: Boolean = true,
    val speedCapCandles: Int = 4,
)

@Serializable
enum class AtrMethod { SMA, WILDER }

@Serializable
data class SwingConfig(
    /** Fractal wing: swing at bar i confirms at bar i+wing. */
    val wing: Int = 2,
)

/**
 * Session table entry. Killzones are defined in EXCHANGE-LOCAL time via the tz
 * database — never fixed UTC offsets (DST would silently shift them ~1/3 of
 * the year). Interval is [start, end) on the local wall clock; a candle is
 * tagged by its OPEN time. start > end means the window crosses local midnight.
 */
@Serializable
data class SessionDef(
    val name: String,
    val zone: String,
    /** "HH:mm" local wall-clock, inclusive. */
    val start: String,
    /** "HH:mm" local wall-clock, exclusive. */
    val end: String,
)

fun defaultSessionTable(): List<SessionDef> = listOf(
    SessionDef(name = "ASIA", zone = "America/New_York", start = "20:00", end = "00:00"),
    SessionDef(name = "LONDON_KZ", zone = "Europe/London", start = "07:00", end = "10:00"),
    SessionDef(name = "NY_KZ", zone = "America/New_York", start = "07:00", end = "10:00"),
    SessionDef(name = "LONDON_CLOSE", zone = "Europe/London", start = "15:00", end = "17:00"),
)

/** Loaded config plus the identity hash of the exact bytes it came from. */
data class LoadedConfig(
    val config: IpdaConfig,
    /** SHA-256 of the raw config file bytes, lowercase hex. */
    val hash: String,
    val path: Path,
)

object ConfigLoader {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false // unknown keys are config typos — fail loudly
    }

    fun load(path: Path): LoadedConfig {
        val bytes = Files.readAllBytes(path)
        val config = json.decodeFromString<IpdaConfig>(String(bytes, Charsets.UTF_8))
        return LoadedConfig(config, sha256Hex(bytes), path)
    }

    /** Write the default config — used to bootstrap a fresh checkout. */
    fun writeDefault(path: Path): LoadedConfig {
        val text = json.encodeToString(IpdaConfig.serializer(), IpdaConfig())
        Files.createDirectories(path.toAbsolutePath().parent)
        Files.write(path, text.toByteArray(Charsets.UTF_8))
        return load(path)
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
