package ipda.live

import ipda.ctrader.OpenApiConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * secrets.properties access for the live loop (same file/keys the fetcher
 * uses — gitignored, never committed; rotate after dev):
 *
 *   clientId=… clientSecret=… accessToken=… refreshToken=… accountId=… host=…
 *
 * The live loop can run for weeks, so refreshed tokens (access ≈30 days,
 * refresh non-expiring, `ProtoOARefreshTokenReq` in-protocol) are PERSISTED
 * back here by targeted line replacement — comments and unrelated keys are
 * preserved, and an unattended session survives its first token expiry.
 */
class SecretsStore(private val path: Path) {

    private val props = Properties()

    init {
        require(Files.exists(path)) {
            "Missing $path — copy secrets.properties.example and fill in credentials."
        }
        Files.newBufferedReader(path).use { props.load(it) }
    }

    val clientId: String get() = required("clientId")
    val clientSecret: String get() = required("clientSecret")

    @Volatile
    private var accessTokenOverride: String? = null

    @Volatile
    private var refreshTokenOverride: String? = null

    val accessToken: String get() = accessTokenOverride ?: required("accessToken")
    val refreshToken: String?
        get() = refreshTokenOverride
            ?: props.getProperty("refreshToken")?.takeIf { it.isNotBlank() && !it.startsWith("CHANGE_ME") }

    val accountId: Long? get() = props.getProperty("accountId")?.toLongOrNull()
    val host: String get() = props.getProperty("host", OpenApiConnection.DEMO_HOST)

    /** Persist refreshed tokens by line-editing the file (preserves comments). */
    @Synchronized
    fun persistTokens(newAccessToken: String, newRefreshToken: String) {
        accessTokenOverride = newAccessToken
        refreshTokenOverride = newRefreshToken
        val lines = Files.readAllLines(path).toMutableList()
        var sawAccess = false
        var sawRefresh = false
        for (i in lines.indices) {
            val t = lines[i].trimStart()
            when {
                t.startsWith("accessToken=") -> { lines[i] = "accessToken=$newAccessToken"; sawAccess = true }
                t.startsWith("refreshToken=") -> { lines[i] = "refreshToken=$newRefreshToken"; sawRefresh = true }
            }
        }
        if (!sawAccess) lines.add("accessToken=$newAccessToken")
        if (!sawRefresh) lines.add("refreshToken=$newRefreshToken")
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(tmp, lines)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun required(key: String): String =
        props.getProperty(key)?.takeIf { it.isNotBlank() && !it.startsWith("CHANGE_ME") }
            ?: error("secrets.properties is missing '$key'")
}
