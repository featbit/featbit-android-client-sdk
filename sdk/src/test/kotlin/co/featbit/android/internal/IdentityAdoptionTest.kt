package co.featbit.android.internal

import co.featbit.android.api.*
import org.junit.Assert.*
import org.junit.Test

public class IdentityAdoptionTest {
    @Test
    public fun namedIdentifyReturnsBeforeAdoptionAndCompletesOnlyWithNewData() {
        val source = ControlledSource()
        val h = Harness(source)
        try {
            source.sinks.last().full(record("f", "A"))
            val op = h.client.identify(user("B"), 100)
            assertNull(op.getResult())
            assertEquals("A", h.client.stringVariation("f", "fallback"))
            h.workers.drain()
            assertEquals("B", source.contexts.last().user.key)
            assertNull(op.getResult())
            assertEquals("fallback", h.client.stringVariation("f", "fallback"))
            source.sinks.last().full(record("f", "B"))
            assertEquals(ReadyResult.REMOTE_CONFIRMED, op.getResult()!!.value)
        } finally {
            h.close()
        }
    }

    @Test
    public fun namedIdentifyTimeoutBeforeAdoptionRetainsOldUser() {
        val source = ControlledSource()
        val h = Harness(source)
        try {
            source.sinks.last().full(record("f", "A"))
            val op = h.client.identify(user("B"), 10)
            h.advance(10)
            h.workers.drain()
            assertEquals(OutcomeCode.TIMED_OUT, op.getResult()!!.code)
            assertEquals("A", source.contexts.last().user.key)
            assertEquals("A", h.client.stringVariation("f", "fallback"))
        } finally {
            h.close()
        }
    }

    @Test
    public fun namedIdentifyQueueAndReadinessShareOneDeadlineWithoutRollingBack() {
        val source = ControlledSource()
        val h = Harness(source)
        try {
            val op = h.client.identify(user("B"), 100)
            h.advance(60)
            h.workers.drain()
            assertNull(op.getResult())
            h.advance(40)
            assertEquals(OutcomeCode.TIMED_OUT, op.getResult()!!.code)
            assertEquals("B", source.contexts.last().user.key)
            source.sinks.last().full(record("f", "B"))
            assertEquals("B", h.client.stringVariation("f", "fallback"))
            assertEquals(OutcomeCode.TIMED_OUT, op.getResult()!!.code)
        } finally {
            h.close()
        }
    }

    @Test
    public fun pendingNamedIdentifyCanBeSupersededOrClosedBeforeAdoption() {
        val source = ControlledSource()
        val repo = Repository()
        val h = Harness(source, anonymous = repo, anonymousEnabled = true)
        try {
            val named = h.client.identify(user("B"), 100)
            val anon = h.client.identifyAnonymous(100)
            h.workers.drain()
            assertEquals(OutcomeCode.SUPERSEDED, named.getResult()!!.code)
            repo.complete()
            h.workers.drain()
            assertEquals("anon", source.contexts.last().user.key)
            assertNull(anon.getResult())
            val closing = h.client.identify(user("C"), 100)
            h.client.close()
            h.workers.drain()
            assertEquals(OutcomeCode.CLOSED, closing.getResult()!!.code)
            assertFalse(source.contexts.any { it.user.key == "B" || it.user.key == "C" })
        } finally {
            h.close()
        }
    }

    private class Repository : AnonymousRepository {
        val callbacks = ArrayList<(Outcome<AnonymousIdentity>) -> Unit>()
        var current = true
        var beforeAdopt: () -> Unit = {}

        override fun prepare(reset: Boolean, completion: (Outcome<AnonymousIdentity>) -> Unit) {
            callbacks.add(completion)
        }

        override fun withCurrent(identity: AnonymousIdentity, adopt: () -> Unit): Boolean {
            if (!current) return false
            beforeAdopt()
            adopt()
            return true
        }

        fun complete(index: Int = 0) =
            callbacks[index](Outcome.success(AnonymousIdentity("anon", 1)))
    }

    @Test
    public fun namedAdoptionCompletesBeforeRemoteReadinessAndCallbackDeadline() {
        val source = ControlledSource()
        val h = Harness(source)
        try {
            val op = h.client.identifyContext(user("B"), 10)
            assertNull(op.getResult())
            assertEquals("A", source.contexts.last().user.key)
            var delivered: Outcome<IdentityReceipt>? = null
            op.observe { delivered = it }
            h.workers.drain()
            assertEquals(OutcomeCode.SUCCESS, op.getResult()!!.code)
            assertEquals("B", source.contexts.last().user.key)
            val ready = h.client.awaitReady(10)
            assertNull(ready.getResult())
            h.advance(20)
            h.dispatch.drain()
            assertEquals(OutcomeCode.SUCCESS, delivered!!.code)
            assertEquals(OutcomeCode.TIMED_OUT, ready.getResult()!!.code)
            val receipt = op.getResult()!!.value!!
            h.client.identify(user("C"), 10)
            assertSame(receipt, op.getResult()!!.value)
        } finally {
            h.close()
        }
    }

    @Test
    public fun anonymousAdoptionDoesNotWaitButLegacyIdentifyDoes() {
        val repo = Repository()
        val source = ControlledSource()
        val h = Harness(source, anonymous = repo, anonymousEnabled = true)
        try {
            val adoption = h.client.identifyAnonymousContext(100)
            h.workers.drain()
            assertNull(adoption.getResult())
            repo.complete()
            h.workers.drain()
            assertEquals(OutcomeCode.SUCCESS, adoption.getResult()!!.code)
            assertEquals("anon", source.contexts.last().user.key)
            assertNull(h.client.awaitReady(100).getResult())
            val legacy = h.client.identifyAnonymous(100)
            h.workers.drain()
            repo.complete(1)
            assertNull(legacy.getResult())
            h.advance(100)
            assertEquals(OutcomeCode.TIMED_OUT, legacy.getResult()!!.code)
        } finally {
            h.close()
        }
    }

    @Test
    public fun queuedNamedTimeoutNeverAdopts() {
        val source = ControlledSource()
        val h = Harness(source)
        try {
            val op = h.client.identifyContext(user("B"), 10)
            h.advance(10)
            h.workers.drain()
            assertEquals(OutcomeCode.TIMED_OUT, op.getResult()!!.code)
            assertEquals("A", source.contexts.last().user.key)
        } finally {
            h.close()
        }
    }

    @Test
    public fun anonymousDeadlineRejectsLatePreparationEvenBeforeTimerRuns() {
        val repo = Repository()
        val source = ControlledSource()
        val h = Harness(source, anonymous = repo, anonymousEnabled = true)
        try {
            val op = h.client.identifyAnonymousContext(10)
            h.workers.drain()
            h.clock.now = 10
            repo.complete()
            h.workers.drain()
            assertEquals(OutcomeCode.TIMED_OUT, op.getResult()!!.code)
            assertEquals("A", source.contexts.last().user.key)
        } finally {
            h.close()
        }
    }

    @Test
    public fun laterNamedRequestSupersedesAnonymousPreparation() {
        val repo = Repository()
        val source = ControlledSource()
        val h = Harness(source, anonymous = repo, anonymousEnabled = true)
        try {
            val old = h.client.identifyAnonymousContext(100)
            h.workers.drain()
            val next = h.client.identifyContext(user("B"), 100)
            h.workers.drain()
            repo.complete()
            h.workers.drain()
            assertEquals(OutcomeCode.SUPERSEDED, old.getResult()!!.code)
            assertEquals(OutcomeCode.SUCCESS, next.getResult()!!.code)
            assertEquals("B", source.contexts.last().user.key)
        } finally {
            h.close()
        }
    }

    @Test
    public fun rejectionAndCloseCannotAdopt() {
        val h = Harness()
        try {
            assertEquals(
                OutcomeCode.INVALID,
                h.client.identifyContext(user("B"), 0).getResult()!!.code,
            )
            assertEquals(
                OutcomeCode.DISABLED,
                h.client.identifyAnonymousContext(100).getResult()!!.code,
            )
            h.workers.accept = false
            assertEquals(
                OutcomeCode.CAPACITY_EXCEEDED,
                h.client.identifyContext(user("B"), 100).getResult()!!.code,
            )
            h.workers.accept = true
            val pending = h.client.identifyContext(user("C"), 100)
            h.client.close()
            h.workers.drain()
            assertEquals(OutcomeCode.CLOSED, pending.getResult()!!.code)
        } finally {
            h.close()
        }
    }

    @Test
    public fun deadlineIsCheckedInsideAnonymousRevisionArbitration() {
        val repo = Repository()
        val source = ControlledSource()
        val h = Harness(source, anonymous = repo, anonymousEnabled = true)
        try {
            val op = h.client.identifyAnonymousContext(10)
            h.workers.drain()
            repo.beforeAdopt = { h.clock.now = 10 }
            repo.complete()
            h.workers.drain()
            assertEquals(OutcomeCode.TIMED_OUT, op.getResult()!!.code)
            assertEquals("A", source.contexts.last().user.key)
        } finally {
            h.close()
        }
    }

    @Test
    public fun adoptionInvalidatesOldSourceAndReadyStillRequiresNewData() {
        val source = ControlledSource()
        val h = Harness(source)
        try {
            source.sinks.last().full(record("f", "A"))
            val old = source.sinks.last()
            val op = h.client.identifyContext(user("B"), 100)
            op.observe { fail("Detached observer must not be invoked") }.value!!.close()
            h.workers.drain()
            val ready = h.client.awaitReady(100)
            assertEquals(OutcomeCode.SUCCESS, op.getResult()!!.code)
            assertNull(ready.getResult())
            assertEquals(
                co.featbit.android.datasource.SourceUpdateCode.INACTIVE,
                old.full(record("f", "old")).code,
            )
            source.sinks.last().full(record("f", "B"))
            assertEquals(ReadyResult.REMOTE_CONFIRMED, ready.getResult()!!.value)
            assertEquals("B", h.client.stringVariation("f", "fallback"))
            h.dispatch.drain()
        } finally {
            h.close()
        }
    }

    @Test
    public fun legacyNamedIdentifySupersedesPendingNamedAdoption() {
        val source = ControlledSource()
        val h = Harness(source)
        try {
            val pending = h.client.identifyContext(user("B"), 100)
            val legacy = h.client.identify(user("C"), 100)
            h.workers.drain()
            assertEquals(OutcomeCode.SUPERSEDED, pending.getResult()!!.code)
            assertEquals("C", source.contexts.last().user.key)
            assertNull(legacy.getResult())
        } finally {
            h.close()
        }
    }

    @Test
    public fun storageFailureAndStaleAnonymousRevisionKeepOldIdentity() {
        val repo = Repository()
        val source = ControlledSource()
        val h = Harness(source, anonymous = repo, anonymousEnabled = true)
        try {
            val failed = h.client.identifyAnonymousContext(100)
            h.workers.drain()
            repo.callbacks[0](Outcome.failure(OutcomeCode.STORAGE_FAILED, null))
            assertEquals(OutcomeCode.STORAGE_FAILED, failed.getResult()!!.code)
            val stale = h.client.identifyAnonymousContext(100)
            h.workers.drain()
            repo.current = false
            repo.complete(1)
            assertEquals(OutcomeCode.SUPERSEDED, stale.getResult()!!.code)
            assertEquals("A", source.contexts.last().user.key)
        } finally {
            h.close()
        }
    }
}
