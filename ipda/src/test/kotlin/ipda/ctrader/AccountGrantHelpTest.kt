package ipda.ctrader

import com.xtrader.protocol.openapi.v2.model.ProtoOACtidTraderAccount
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression cover for the 4 Aug 2026 failure: the deployment was configured
 * with the cTrader traderLogin instead of the ctidTraderAccountId, and the
 * old error text ("not in token grant list: [48042139]") gave no hint that
 * the two are different identifiers for the SAME account. Four days of demo
 * run were lost to a message that could have diagnosed itself.
 */
class AccountGrantHelpTest {

    private fun account(ctid: Long, login: Long? = null, live: Boolean = false, broker: String? = null) =
        ProtoOACtidTraderAccount.newBuilder()
            .setCtidTraderAccountId(ctid)
            .also { b ->
                if (login != null) b.traderLogin = login
                b.isLive = live
                if (broker != null) b.brokerTitleShort = broker
            }
            .build()

    @Test
    fun `names the login confusion when the configured id is a traderLogin`() {
        val help = accountGrantHelp(listOf(account(48042139L, login = 10644317L, broker = "FxPro")), 10644317L)

        // Must say outright what happened and what to type instead.
        assertContains(help, "traderLogin of ctidTraderAccountId 48042139")
        assertContains(help, "Set accountId=48042139")
        // Both identifiers visible for the granted account.
        assertContains(help, "ctidTraderAccountId=48042139")
        assertContains(help, "traderLogin=10644317")
        assertContains(help, "[demo]")
    }

    @Test
    fun `falls back to the generic identifier hint when nothing matches`() {
        val help = accountGrantHelp(listOf(account(48042139L, login = 10644317L)), 99999999L)

        assertContains(help, "Account 99999999 is not in this access token's grant list.")
        assertContains(help, "must be the ctidTraderAccountId")
        // No false claim that the configured value is a login.
        assertFalse(help.contains("traderLogin of ctidTraderAccountId"))
    }

    @Test
    fun `flags live accounts distinctly and lists every grant`() {
        val help = accountGrantHelp(
            listOf(account(1L, login = 11L), account(2L, login = 22L, live = true)),
            3L,
        )

        assertContains(help, "ctidTraderAccountId=1")
        assertContains(help, "ctidTraderAccountId=2")
        assertContains(help, "[LIVE]")
        assertContains(help, "[demo]")
    }

    @Test
    fun `empty grant list points at the token, not the account id`() {
        val help = accountGrantHelp(emptyList(), 48042139L)

        assertContains(help, "grants no accounts at all")
        assertContains(help, "accounts scope")
        assertFalse(help.contains("ctidTraderAccountId="))
    }

    @Test
    fun `is a fatal config error, not a retryable one`() {
        val e = FatalConfigException(accountGrantHelp(emptyList(), 1L))
        // The reconnect loop's classification is by type: anything that is a
        // FatalConfigException stops the session instead of backing off.
        assertTrue(e is Exception)
        assertFalse(e is OpenApiErrorException)
    }
}
