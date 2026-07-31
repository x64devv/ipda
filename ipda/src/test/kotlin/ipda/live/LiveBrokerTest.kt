package ipda.live

import com.xtrader.protocol.openapi.v2.ProtoOAExecutionEvent
import com.xtrader.protocol.openapi.v2.model.ProtoOAClosePositionDetail
import com.xtrader.protocol.openapi.v2.model.ProtoOADeal
import com.xtrader.protocol.openapi.v2.model.ProtoOADealStatus
import com.xtrader.protocol.openapi.v2.model.ProtoOAExecutionType
import com.xtrader.protocol.openapi.v2.model.ProtoOAOrder
import com.xtrader.protocol.openapi.v2.model.ProtoOAOrderStatus
import com.xtrader.protocol.openapi.v2.model.ProtoOAOrderType
import com.xtrader.protocol.openapi.v2.model.ProtoOAPosition
import com.xtrader.protocol.openapi.v2.model.ProtoOAPositionStatus
import com.xtrader.protocol.openapi.v2.model.ProtoOATradeData
import com.xtrader.protocol.openapi.v2.model.ProtoOATradeSide
import ipda.broker.OrderIntent
import ipda.broker.Side
import ipda.config.LiveConfig
import ipda.log.InMemoryEventLog
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveBrokerTest {

    private class FakeGateway : LiveBroker.Gateway {
        data class Market(
            val symbolId: Long, val side: Side, val volumeCents: Long,
            val clientOrderId: String, val relSl: Long?, val relTp: Long?,
        )

        val markets = ArrayList<Market>()
        val amends = ArrayList<Triple<Long, Double, Double>>()
        val closes = ArrayList<Pair<Long, Long>>()

        override fun sendMarketOrder(
            symbolId: Long, side: Side, volumeCents: Long, clientOrderId: String,
            relativeStopLoss1e5: Long?, relativeTakeProfit1e5: Long?, comment: String,
        ) {
            markets.add(Market(symbolId, side, volumeCents, clientOrderId, relativeStopLoss1e5, relativeTakeProfit1e5))
        }

        override fun amendPositionSltp(positionId: Long, stopLoss: Double, takeProfit: Double) {
            amends.add(Triple(positionId, stopLoss, takeProfit))
        }

        override fun closePosition(positionId: Long, volumeCents: Long) {
            closes.add(positionId to volumeCents)
        }
    }

    private val decision = Instant.parse("2026-07-29T14:00:00Z")
    private var nowMs = decision.toEpochMilli() + 5_000
    private val log = InMemoryEventLog()
    private val spreads = SpreadRecorder()
    private val trades = ArrayList<LiveBroker.ClosedLiveTrade>()
    private val gateway = FakeGateway()

    private fun broker(cfg: LiveConfig = LiveConfig()): LiveBroker {
        val b = LiveBroker(cfg, log, spreads, { trades.add(it) }, clock = { nowMs })
        b.attach(gateway, mapOf("EURUSD" to 1L, "GBPUSD" to 2L))
        return b
    }

    private fun buyIntent() = OrderIntent(
        symbol = "EURUSD",
        side = Side.BUY,
        volumeLots = 0.10,
        stopLoss = 1.0950,
        takeProfit = 1.1100,
        decisionTime = decision,
        decisionPrice = 1.1000,
    )

    private fun entryFill(clientOrderId: String, price: Double, positionId: Long = 100L): ProtoOAExecutionEvent {
        val deal = ProtoOADeal.newBuilder()
            .setDealId(1).setOrderId(10).setPositionId(positionId)
            .setVolume(1_000_000).setFilledVolume(1_000_000).setSymbolId(1L)
            .setCreateTimestamp(nowMs).setExecutionTimestamp(nowMs)
            .setExecutionPrice(price)
            .setTradeSide(ProtoOATradeSide.BUY)
            .setDealStatus(ProtoOADealStatus.FILLED)
            .build()
        val order = ProtoOAOrder.newBuilder()
            .setOrderId(10)
            .setTradeData(
                ProtoOATradeData.newBuilder().setSymbolId(1L).setVolume(1_000_000).setTradeSide(ProtoOATradeSide.BUY)
            )
            .setOrderType(ProtoOAOrderType.MARKET)
            .setOrderStatus(ProtoOAOrderStatus.ORDER_STATUS_FILLED)
            .setClientOrderId(clientOrderId)
            .build()
        return ProtoOAExecutionEvent.newBuilder()
            .setCtidTraderAccountId(42)
            .setExecutionType(ProtoOAExecutionType.ORDER_FILLED)
            .setDeal(deal).setOrder(order)
            .build()
    }

    private fun closingFill(positionId: Long, entryPrice: Double, exitPrice: Double): ProtoOAExecutionEvent {
        val deal = ProtoOADeal.newBuilder()
            .setDealId(2).setOrderId(11).setPositionId(positionId)
            .setVolume(1_000_000).setFilledVolume(1_000_000).setSymbolId(1L)
            .setCreateTimestamp(nowMs).setExecutionTimestamp(nowMs)
            .setExecutionPrice(exitPrice)
            .setTradeSide(ProtoOATradeSide.SELL) // closing a BUY
            .setDealStatus(ProtoOADealStatus.FILLED)
            .setClosePositionDetail(
                ProtoOAClosePositionDetail.newBuilder()
                    .setEntryPrice(entryPrice).setGrossProfit(0).setSwap(0).setCommission(0).setBalance(0)
                    .setClosedVolume(1_000_000)
            )
            .build()
        val order = ProtoOAOrder.newBuilder()
            .setOrderId(11)
            .setTradeData(
                ProtoOATradeData.newBuilder().setSymbolId(1L).setVolume(1_000_000).setTradeSide(ProtoOATradeSide.SELL)
            )
            .setOrderType(ProtoOAOrderType.STOP_LOSS_TAKE_PROFIT)
            .setOrderStatus(ProtoOAOrderStatus.ORDER_STATUS_FILLED)
            .setPositionId(positionId)
            .build()
        return ProtoOAExecutionEvent.newBuilder()
            .setCtidTraderAccountId(42)
            .setExecutionType(ProtoOAExecutionType.ORDER_FILLED)
            .setDeal(deal).setOrder(order)
            .build()
    }

    @Test
    fun `market submit carries volume and provisional relative bracket`() {
        val b = broker()
        val ack = b.submit(buyIntent())
        assertTrue(ack.accepted)
        val m = gateway.markets.single()
        assertEquals(1L, m.symbolId)
        assertEquals(Side.BUY, m.side)
        assertEquals(1_000_000, m.volumeCents) // 0.10 lots × 100k units × 100 cents
        assertEquals(500L, m.relSl)   // (1.1000 − 1.0950) × 1e5
        assertEquals(1000L, m.relTp)  // (1.1100 − 1.1000) × 1e5
        assertTrue(b.hasExposure("EURUSD"))
    }

    @Test
    fun `per-symbol lot override changes the order volume`() {
        val b = broker(LiveConfig(volumeLots = 0.10, volumeLotsBySymbol = mapOf("EURUSD" to 0.25)))
        assertEquals(2_500_000, b.volumeCentsFor("EURUSD")) // 0.25 lots
        assertEquals(1_000_000, b.volumeCentsFor("GBPUSD")) // default 0.10
        b.submit(buyIntent())
        assertEquals(2_500_000, gateway.markets.single().volumeCents)
    }

    @Test
    fun `one position per symbol is enforced exactly like SimBroker`() {
        val b = broker()
        assertTrue(b.submit(buyIntent()).accepted)
        val second = b.submit(buyIntent())
        assertFalse(second.accepted)
        assertEquals("position or pending order exists for EURUSD", second.reason)
    }

    @Test
    fun `stale decisions are rejected — warmup signals never trade`() {
        val b = broker()
        nowMs = decision.toEpochMilli() + 181_000 // beyond maxDecisionAgeSeconds=180
        val ack = b.submit(buyIntent())
        assertFalse(ack.accepted)
        assertTrue(ack.reason!!.startsWith("stale decision"))
        assertFalse(b.hasExposure("EURUSD"))
        assertTrue(gateway.markets.isEmpty())
    }

    @Test
    fun `entry fill amends the position to the exact absolute bracket`() {
        val b = broker()
        val ack = b.submit(buyIntent())
        b.onExecutionEvent(entryFill(ack.brokerRef!!, price = 1.10012))
        val amend = gateway.amends.single()
        assertEquals(100L, amend.first)
        assertEquals(1.0950, amend.second)
        assertEquals(1.1100, amend.third)
        assertTrue(b.hasExposure("EURUSD"))
        // Slippage vs decision price is logged with the fill.
        val fill = log.records.first { it.first == "live_fill" }
        assertTrue("\"slippage\":" in fill.second)
    }

    @Test
    fun `closing fill at the target records a ~2R trade and frees the slot`() {
        val b = broker()
        val ack = b.submit(buyIntent())
        b.onExecutionEvent(entryFill(ack.brokerRef!!, price = 1.1000))
        b.onExecutionEvent(closingFill(positionId = 100L, entryPrice = 1.1000, exitPrice = 1.1100))
        val t = trades.single()
        assertEquals("TARGET", t.reason)
        assertEquals(Side.BUY, t.side)
        assertEquals(1.1000, t.entryPrice)
        assertEquals(1.1100, t.exitPrice)
        assertNotNull(t.rMultiple)
        assertTrue(abs(t.rMultiple!! - 2.0) < 1e-6)
        assertFalse(b.hasExposure("EURUSD"))
        // Next signal can trade again.
        assertTrue(b.submit(buyIntent().copy(decisionTime = Instant.ofEpochMilli(nowMs))).accepted)
    }

    @Test
    fun `closing fill at the stop records a ~minus1R trade`() {
        val b = broker()
        val ack = b.submit(buyIntent())
        b.onExecutionEvent(entryFill(ack.brokerRef!!, price = 1.1000))
        b.onExecutionEvent(closingFill(positionId = 100L, entryPrice = 1.1000, exitPrice = 1.0950))
        val t = trades.single()
        assertEquals("STOP", t.reason)
        assertTrue(abs(t.rMultiple!! + 1.0) < 1e-6)
    }

    @Test
    fun `entry rejection clears exposure`() {
        val b = broker()
        b.submit(buyIntent())
        val order = ProtoOAOrder.newBuilder()
            .setOrderId(10)
            .setTradeData(
                ProtoOATradeData.newBuilder().setSymbolId(1L).setVolume(1_000_000).setTradeSide(ProtoOATradeSide.BUY)
            )
            .setOrderType(ProtoOAOrderType.MARKET)
            .setOrderStatus(ProtoOAOrderStatus.ORDER_STATUS_REJECTED)
            .setClientOrderId("ipda-EURUSD-${decision.epochSecond}")
            .build()
        val ev = ProtoOAExecutionEvent.newBuilder()
            .setCtidTraderAccountId(42)
            .setExecutionType(ProtoOAExecutionType.ORDER_REJECTED)
            .setOrder(order)
            .build()
        b.onExecutionEvent(ev)
        assertFalse(b.hasExposure("EURUSD"))
    }

    @Test
    fun `reconciled positions are adopted and lock the exposure slot`() {
        val b = broker()
        val position = ProtoOAPosition.newBuilder()
            .setPositionId(777)
            .setTradeData(
                ProtoOATradeData.newBuilder()
                    .setSymbolId(1L).setVolume(1_000_000).setTradeSide(ProtoOATradeSide.SELL)
                    .setOpenTimestamp(nowMs - 3_600_000)
            )
            .setPositionStatus(ProtoOAPositionStatus.POSITION_STATUS_OPEN)
            .setSwap(0)
            .setPrice(1.2000)
            .build()
        b.adoptPositions(listOf(position))
        assertTrue(b.hasExposure("EURUSD"))
        assertFalse(b.submit(buyIntent()).accepted)
        // Its close records an adopted trade with no R (no intent to score against).
        nowMs += 1000
        b.onExecutionEvent(closingFill(positionId = 777, entryPrice = 1.2000, exitPrice = 1.1900))
        val t = trades.single()
        assertTrue(t.adopted)
        assertNull(t.rMultiple)
        assertEquals("OTHER", t.reason)
        assertEquals(Side.SELL, t.side)
        assertTrue(t.netMove > 0) // SELL from 1.2000 closed at 1.1900
        assertFalse(b.hasExposure("EURUSD"))
    }

    @Test
    fun `order error for someone else's order does not clear our pending entry`() {
        val b = broker()
        b.submit(buyIntent())
        // Error for an order we never saw ACCEPTED — e.g. a second live
        // session (gold under its own config) erroring on the same account.
        val foreign = com.xtrader.protocol.openapi.v2.ProtoOAOrderErrorEvent.newBuilder()
            .setCtidTraderAccountId(42)
            .setErrorCode("TRADING_BAD_VOLUME")
            .setOrderId(9999)
            .build()
        b.onOrderError(foreign)
        assertTrue(b.hasExposure("EURUSD")) // untouched
    }

    @Test
    fun `order error matched by accepted orderId clears exactly our pending entry`() {
        val b = broker()
        val ack = b.submit(buyIntent())
        // ACCEPTED teaches the broker its orderId…
        val accepted = ProtoOAExecutionEvent.newBuilder()
            .setCtidTraderAccountId(42)
            .setExecutionType(ProtoOAExecutionType.ORDER_ACCEPTED)
            .setOrder(
                ProtoOAOrder.newBuilder()
                    .setOrderId(10)
                    .setTradeData(
                        ProtoOATradeData.newBuilder().setSymbolId(1L).setVolume(1_000_000).setTradeSide(ProtoOATradeSide.BUY)
                    )
                    .setOrderType(ProtoOAOrderType.MARKET)
                    .setOrderStatus(ProtoOAOrderStatus.ORDER_STATUS_ACCEPTED)
                    .setClientOrderId(ack.brokerRef!!)
            )
            .build()
        b.onExecutionEvent(accepted)
        // …so a later error for that orderId clears the right symbol.
        val err = com.xtrader.protocol.openapi.v2.ProtoOAOrderErrorEvent.newBuilder()
            .setCtidTraderAccountId(42)
            .setErrorCode("MARKET_CLOSED")
            .setOrderId(10)
            .build()
        b.onOrderError(err)
        assertFalse(b.hasExposure("EURUSD"))
    }

    @Test
    fun `stuck pending entry is reaped by the housekeeping timeout`() {
        val b = broker()
        b.submit(buyIntent())
        assertTrue(b.hasExposure("EURUSD"))
        b.housekeeping() // fresh — still pending
        assertTrue(b.hasExposure("EURUSD"))
        nowMs += 61_000 // beyond pendingEntryTimeoutSeconds=60
        b.housekeeping()
        assertFalse(b.hasExposure("EURUSD"))
        assertTrue(log.records.any { it.first == "live_entry_dead" && "PENDING_TIMEOUT" in it.second })
    }

    @Test
    fun `flatten closes open positions only`() {
        val b = broker()
        val ack = b.submit(buyIntent())
        b.onExecutionEvent(entryFill(ack.brokerRef!!, price = 1.1000))
        b.flattenAll()
        assertEquals(listOf(100L to 1_000_000L), gateway.closes)
    }
}
