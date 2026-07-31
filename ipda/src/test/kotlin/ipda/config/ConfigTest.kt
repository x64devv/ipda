package ipda.config

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import java.nio.file.Files

class ConfigTest {

    @Test
    fun `default config round-trips and hash is stable across loads`() {
        val dir = createTempDirectory("ipda-config")
        val path = dir.resolve("ipda-config.json")
        val written = ConfigLoader.writeDefault(path)
        val reloaded = ConfigLoader.load(path)

        assertEquals(written.config, reloaded.config)
        assertEquals(written.hash, reloaded.hash)
        assertEquals(64, written.hash.length) // sha-256 hex
    }

    @Test
    fun `hash changes when any byte changes`() {
        val dir = createTempDirectory("ipda-config")
        val path = dir.resolve("ipda-config.json")
        val original = ConfigLoader.writeDefault(path)

        val text = Files.readString(path).replace("\"k1\": 2.0", "\"k1\": 2.1")
        Files.writeString(path, text)
        val modified = ConfigLoader.load(path)

        assertNotEquals(original.hash, modified.hash)
        assertEquals(2.1, modified.config.displacement.k1)
    }

    @Test
    fun `unknown keys fail loudly - config typos must not pass silently`() {
        val dir = createTempDirectory("ipda-config")
        val path = dir.resolve("ipda-config.json")
        ConfigLoader.writeDefault(path)
        val text = Files.readString(path).replaceFirst("{", "{\n  \"speling_mistake\": true,")
        Files.writeString(path, text)

        assertFailsWith<Exception> { ConfigLoader.load(path) }
    }

    @Test
    fun `round-2 entry fields default to v0 behaviour`() {
        val c = IpdaConfig()
        assertEquals(EntryMode.MARKET_NEXT_OPEN, c.baseline.entryMode)
        assertEquals(24, c.baseline.entryExpiryCandles)
        assertEquals(false, c.baseline.pdAtEntry)
        assertEquals(ipda.model.Timeframe.H1, c.execution.executionTimeframe)
    }

    @Test
    fun `v1 context layers default OFF and legacy config files parse unchanged`() {
        val c = IpdaConfig()
        assertEquals(false, c.baseline.requireSweep)
        assertEquals(false, c.baseline.premiumDiscountOnly)
        assertEquals(24, c.baseline.sweepLookbackCandles)

        // A config file written before the v1 fields existed must still parse
        // (same bytes -> same hash -> the control run stays reproducible).
        val dir = createTempDirectory("ipda-config")
        val path = dir.resolve("ipda-config.json")
        ConfigLoader.writeDefault(path)
        val legacy = Files.readString(path)
            .replace(Regex("\\s*\"requireSweep\": false,"), "")
            .replace(Regex("\\s*\"sweepLookbackCandles\": 24,"), "")
            .replace(Regex("\\s*\"premiumDiscountOnly\": false,?"), "")
            .replace(Regex(",(\\s*})"), "$1") // heal trailing comma left in the baseline block
        Files.writeString(path, legacy)
        val loaded = ConfigLoader.load(path)
        assertEquals(false, loaded.config.baseline.requireSweep)
        assertEquals(false, loaded.config.baseline.premiumDiscountOnly)
        assertEquals(24, loaded.config.baseline.sweepLookbackCandles)
    }

    @Test
    fun `defaults match the settled spec`() {
        val c = IpdaConfig()
        assertEquals(listOf("EURUSD", "GBPUSD"), c.instruments)
        assertEquals(2.0, c.displacement.k1)
        assertEquals(0.65, c.displacement.bodyRatio)
        assertEquals(4, c.displacement.speedCapCandles)
        assertTrue(c.displacement.speedCapEnabled)
        assertEquals(20, c.displacement.atrPeriod)
        assertTrue(c.sessions.any { it.name == "LONDON_KZ" && it.zone == "Europe/London" })
        assertTrue(c.sessions.any { it.name == "NY_KZ" && it.zone == "America/New_York" })
    }
}
