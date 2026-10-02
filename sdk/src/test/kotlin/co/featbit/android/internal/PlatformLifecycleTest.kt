package co.featbit.android.internal

import co.featbit.android.api.*
import org.junit.Assert.*
import org.junit.Test

public class PlatformLifecycleTest {
    @Test public fun initialSnapshotPrecedesBindingAndDuplicateAndClosedSignalsAreIgnored() {
        val relay = PlatformStateRelay()
        val values = ArrayList<PlatformState>()
        relay.update { PlatformState(false, false) }
        assertTrue(relay.bind { state -> values.add(state); { next -> values.add(next); Unit } })
        relay.update { it.copy(foreground = true) }
        relay.update { it.copy(foreground = true) }
        relay.close(); relay.update { it.copy(network = true) }
        assertFalse(relay.bind { error("closed binding") })
        assertEquals(listOf(PlatformState(false, false), PlatformState(true, false)), values)
    }
    @Test public fun overlappingNetworksAndDuplicateLossDoNotPauseSurvivingNetwork() {
        val networks = AvailableNetworks<String>()
        networks.initialize(listOf("wifi"))
        assertTrue(networks.available("cell"))
        assertTrue(networks.available("vpn"))
        assertTrue(networks.lost("wifi"))
        assertTrue(networks.lost("wifi"))
        assertTrue(networks.lost("vpn"))
        assertFalse(networks.lost("cell"))
        assertTrue(networks.available("wifi"))
    }
    @Test public fun backgroundCreationNeverConnectsEvenWithGraceAndWaitExpiresBeforeResume() {
        val h = OnlineHarness(grace = 30_000, initial = PlatformState())
        assertTrue(h.transport.requests.isEmpty())
        val wait = h.client.awaitReady(1_000)
        h.clock.now += 1_001
        h.client.lifecycle(true, true)
        assertEquals(OutcomeCode.TIMED_OUT, wait.getResult()!!.code)
        h.workers.drain(); assertEquals(1, h.transport.requests.size)
        h.last.data(); assertTrue(h.client.getConnectionInformation().remoteConfirmed)
        h.close()
    }
    @Test public fun backgroundCreationAndIdentifyUsePollingWithoutStreamingGrace() {
        val h = OnlineHarness(background = true, grace = 30_000, initial = PlatformState())
        assertFalse(h.last.streaming)
        val wait = h.client.identify(user("B"), 5_000)
        h.workers.drain(); assertFalse(h.last.streaming)
        h.last.data(user = "B")
        assertEquals(ReadyResult.REMOTE_CONFIRMED, wait.getResult()!!.value)
        h.client.lifecycle(true, true); h.workers.drain(); assertTrue(h.last.streaming)
        h.close()
    }
    @Test public fun graceRetainsOnlyExistingWorkAndRejectsLateDataBeforeDelayedTick() {
        val h = OnlineHarness(grace = 1_000)
        h.last.data(); val original = h.last
        h.client.lifecycle(false, true)
        h.advance(999); assertFalse(original.canceled)
        h.clock.now += 2
        original.data(value = "expired")
        assertEquals("one", h.client.stringVariation("flag", ""))
        h.advance(0); assertTrue(original.canceled)
        assertEquals(1, h.transport.requests.size)
        h.close()
    }
    @Test public fun graceCannotReconnectOrStartAfterIdentify() {
        val h = OnlineHarness(grace = 60_000)
        h.last.data(); h.client.lifecycle(false, true)
        h.last.listener.failed(null); h.advance(10_000)
        assertEquals(1, h.transport.requests.size)
        h.client.identify(user("B"), 1_000); h.advance(10_000)
        assertEquals(1, h.transport.requests.size)
        h.close()
    }
    @Test public fun queuedDispatchAtBackgroundEntryIsRevokedAndForegroundStartsPromptly() {
        val h = OnlineHarness(grace = 60_000)
        h.client.identify(user("B"), 5_000)
        h.client.lifecycle(false, true); h.workers.drain()
        assertEquals(1, h.transport.requests.size)
        h.client.lifecycle(true, true); h.workers.drain()
        assertEquals(2, h.transport.requests.size)
        h.last.data(user = "B"); assertTrue(h.client.getConnectionInformation().remoteConfirmed)
        h.close()
    }
    @Test public fun foregroundBeforeDelayedTickerCannotReviveExpiredGraceStream() {
        val h = OnlineHarness(grace = 1_000)
        h.last.data(); val old = h.last
        h.client.lifecycle(false, true)
        h.clock.now += 1_001
        h.client.lifecycle(true, true); h.workers.drain()
        assertTrue(old.canceled); assertEquals(2, h.transport.requests.size)
        old.data(value = "stale"); old.listener.ended(4003)
        assertEquals("one", h.client.stringVariation("flag", ""))
        assertNotEquals(SyncStatus.TERMINAL, h.client.getConnectionInformation().status)
        h.close()
    }
    @Test public fun lateStreamDataCannotResetExpiredInactivityBeforeTheFirstResumeTick() {
        val h = OnlineHarness()
        h.last.data(); val old = h.last
        h.clock.now += 36_001
        old.data(value = "stale")
        assertEquals("one", h.client.stringVariation("flag", ""))
        h.advance(0); assertTrue(old.canceled)
        h.advance(1_000); assertEquals(2, h.transport.requests.size)
        h.close()
    }
    @Test public fun backgroundCancelsCandidateImmediatelyEvenWithGrace() {
        val h = OnlineHarness(fallback = true, grace = 30_000)
        h.last.listener.failed(null); h.advance(30_000); h.last.data(); h.advance(60_000)
        val candidate = h.last; assertTrue(candidate.streaming)
        h.client.lifecycle(false, true); h.workers.drain()
        assertTrue(candidate.canceled)
        candidate.data(value = "stale"); candidate.listener.ended(4003)
        assertEquals(SyncMode.POLLING, h.client.getConnectionInformation().effectiveMode)
        assertNotEquals(SyncStatus.TERMINAL, h.client.getConnectionInformation().status)
        h.advance(300_000)
        h.client.lifecycle(true, true); h.workers.drain()
        assertFalse(h.last.streaming) // Resume the effective mode before an overdue recovery probe.
        h.last.data(); h.advance(1); assertTrue(h.last.streaming)
        h.close()
    }
    @Test public fun dozeStopsBackgroundPollingAndDoesNotCatchUpOrOverrideOffline() {
        val h = OnlineHarness(background = true)
        h.client.lifecycle(false, true); h.workers.drain(); val poll = h.last
        h.client.lifecycle(false, true, false); h.workers.drain()
        assertTrue(poll.canceled)
        val count = h.transport.requests.size
        h.advance(3_600_000); assertEquals(count, h.transport.requests.size)
        h.client.lifecycle(false, true, true); h.workers.drain()
        assertEquals(count + 1, h.transport.requests.size)
        h.client.setOffline(1_000)
        h.client.lifecycle(true, true); h.advance(3_600_000)
        assertEquals(count + 1, h.transport.requests.size)
        h.close()
    }
    @Test public fun platformWithdrawalEndsTransitionBudgetWithoutRegrantOnIdleExit() {
        val h = EventHarness(transition = true)
        h.client.track("before"); h.client.lifecycle(false, true); h.pump()
        val old = h.last
        h.client.lifecycle(false, true, false)
        assertTrue(old.canceled)
        old.listener.response(403, "", null)
        h.client.lifecycle(false, true, true); h.pump()
        assertEquals(1, h.transport.requests.size)
        h.client.track("background"); h.client.track("background")
        h.client.lifecycle(true, true); h.client.track("background")
        h.advance(1_000); h.reply()
        val flush = h.flush(); h.reply()
        if (flush.getResult() == null) h.reply()
        assertTrue(flush.getResult()!!.isSuccess)
        val payloads = h.transport.requests.drop(1).joinToString { it.request.payload() }
        assertEquals(2, Regex("\"eventName\":\"background\"").findAll(payloads).count())
        h.close()
    }
}
