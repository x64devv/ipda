package ipda.log

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Append-only, queryable event log (§9.2). Every detector emission — leg
 * evaluations above all — lands here so parameter sweeps are queries over
 * logged data, not re-runs of detection (standing rule 2).
 *
 * v1: JSON-lines flat file. Each record is one line: {"type": ..., ...payload}.
 * The serialization of specific record types will be wired in with the
 * backtest harness milestone; the interface is fixed now so detectors and
 * harness code compile against the seam.
 */
interface EventLog : AutoCloseable {
    fun append(type: String, jsonPayload: String)
    override fun close()
}

class JsonlEventLog(path: Path) : EventLog {
    private val writer = Files.newBufferedWriter(
        path,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND,
    )

    @Synchronized
    override fun append(type: String, jsonPayload: String) {
        writer.write("""{"type":"$type","payload":$jsonPayload}""")
        writer.newLine()
    }

    override fun close() = writer.close()
}

/** Test double / dry-run sink. */
class InMemoryEventLog : EventLog {
    val records = ArrayList<Pair<String, String>>()
    override fun append(type: String, jsonPayload: String) {
        records.add(type to jsonPayload)
    }
    override fun close() {}
}
