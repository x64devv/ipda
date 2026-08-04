package ipda.ctrader

import com.xtrader.protocol.openapi.v2.model.ProtoOACtidTraderAccount

/**
 * A configuration error that RETRYING CANNOT FIX.
 *
 * Added 4 Aug 2026 after a four-day silent failure: the deployment was
 * configured with the cTrader *traderLogin* (10644317) instead of the
 * *ctidTraderAccountId* (48042139), so every connection attempt died at
 * `accountsByToken` — and the reconnect loop, which cannot tell a wrong
 * account id from a dropped socket, retried it every 300s from 31 Jul to
 * 4 Aug. Zero trades, zero spread samples, and the manager's heartbeat
 * looked *healthy* the whole time because each retry appended an event.
 *
 * The rule this encodes: a failure that a human must fix is not a
 * disconnect. It stops the session, exits the process non-zero, and is
 * surfaced by the manager — loudly, once — instead of being buried in a
 * retry loop.
 *
 * Only genuinely permanent conditions belong here. Network faults, stream
 * death, and in-protocol token expiry stay retryable: those DO heal on
 * their own, and losing detector state to a restart costs real warmup.
 */
class FatalConfigException(message: String) : Exception(message)

/**
 * Human-readable, self-diagnosing description of a grant-list mismatch.
 *
 * Prints every granted account with BOTH of its identifiers, because the
 * whole failure mode is that they are easy to confuse: `traderLogin` is
 * the number cTrader shows in the platform, `ctidTraderAccountId` is the
 * API's internal id, and only the latter is accepted by the protocol.
 * When the configured value matches a login, say so outright.
 */
fun accountGrantHelp(accounts: List<ProtoOACtidTraderAccount>, chosen: Long): String = buildString {
    append("Account $chosen is not in this access token's grant list.\n")
    if (accounts.isEmpty()) {
        append("The token grants no accounts at all — re-issue it with the accounts scope.")
        return@buildString
    }
    append("This token grants:\n")
    for (a in accounts) {
        append("  ctidTraderAccountId=").append(a.ctidTraderAccountId)
        if (a.hasTraderLogin()) append("  traderLogin=").append(a.traderLogin)
        if (a.hasBrokerTitleShort()) append("  broker=").append(a.brokerTitleShort)
        append(if (a.hasIsLive() && a.isLive) "  [LIVE]" else "  [demo]")
        append('\n')
    }
    val byLogin = accounts.firstOrNull { a -> a.hasTraderLogin() && a.traderLogin.toLong() == chosen }
    if (byLogin != null) {
        append("\nHINT: $chosen is the cTrader traderLogin of ctidTraderAccountId ")
        append(byLogin.ctidTraderAccountId)
        append(" — you configured the login. Set accountId=")
        append(byLogin.ctidTraderAccountId)
        append(" instead.")
    } else {
        append("\nHINT: accountId must be the ctidTraderAccountId (the API's internal id), ")
        append("NOT the account number shown in the cTrader platform.")
    }
}
