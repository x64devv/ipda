package ipda

import ipda.config.ConfigLoader
import java.nio.file.Path

/**
 * Entry point. For now: load (or bootstrap) the config and print run-identity
 * components. The data fetcher and backtest harness hang off this later.
 */
fun main(args: Array<String>) {
    val configPath = Path.of(args.getOrElse(0) { "config/ipda-config.json" })
    val loaded = if (configPath.toFile().exists()) {
        ConfigLoader.load(configPath)
    } else {
        println("No config at $configPath — writing defaults.")
        ConfigLoader.writeDefault(configPath)
    }
    println("ipda v0.1.0")
    println("config:      ${loaded.path}")
    println("config hash: ${loaded.hash}")
    println("instruments: ${loaded.config.instruments}")
    println("bias/entry:  ${loaded.config.biasTimeframe}/${loaded.config.entryTimeframe}")
    println("displacement: ${loaded.config.displacement}")
}
