package co.featbit.sample.kotlin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.featbit.android.api.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Delayed results live only in the test APK; no production injection API. */
@RunWith(AndroidJUnit4::class)
class IdentitySwitchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun waitFor(check: () -> Boolean) {
        repeat(200) {
            var done = false
            main { done = check() }
            if (done) return
            Thread.sleep(25)
        }
        fail("Identity operation did not settle")
    }

    private class Pending<T> : Operation<T> {
        private var result: Outcome<T>? = null
        private var callback: Completion<T>? = null

        override fun getResult() = result

        override fun observe(callback: Completion<T>): Outcome<Registration> {
            this.callback = callback
            return Outcome.success(
                object : Registration {
                    override fun close() {
                        this@Pending.callback = null
                    }
                }
            )
        }

        fun complete(outcome: Outcome<T>) {
            result = outcome
            callback?.onComplete(outcome)
        }
    }

    @Test
    fun selectionFollowsAdoptionAndSurvivesReadinessTimeout() {
        lateinit var session: SampleSession
        main {
            session = SampleSession(instrumentation.targetContext)
            session.draft.local = true
            session.connect()
        }
        waitFor { session.state.value.available }
        val field =
            SampleSession::class.java.getDeclaredField("client").apply { isAccessible = true }
        lateinit var original: FeatBitClient
        var adoption = Pending<IdentityReceipt>()
        val ready = Pending<ReadyResult>()
        var readyCalls = 0
        var budget = 0L
        try {
            main {
                original = field.get(session) as FeatBitClient
                val proxy =
                    Proxy.newProxyInstance(
                        FeatBitClient::class.java.classLoader,
                        arrayOf(FeatBitClient::class.java),
                    ) { _, method, args ->
                        when (method.name) {
                            "identifyContext" -> adoption
                            "identify" -> error("Sample must separate adoption from readiness")
                            "awaitReady" -> {
                                readyCalls++
                                budget = args!![0] as Long
                                ready
                            }
                            else -> method.invoke(original, *(args ?: emptyArray()))
                        }
                    } as FeatBitClient
                field.set(session, proxy)
                session.userSheet = true
                session.identify(1)
                assertEquals(0, session.state.value.user)
                assertNotNull(session.state.value.busy)
                session.identify(1) // Conflicting requests remain blocked.
                adoption.complete(Outcome.failure(OutcomeCode.TIMED_OUT, null))
            }
            waitFor { session.state.value.available }
            main {
                assertEquals(0, session.state.value.user)
                assertTrue(session.userSheet)
                assertFalse(session.state.value.waitTimedOut)
                assertEquals(0, readyCalls)
                adoption = Pending()
                session.identify(1)
            }
            lateinit var real: Operation<IdentityReceipt>
            main { real = original.identifyContext(session.people[1].user(), 5000) }
            waitFor { real.getResult() != null }
            main {
                // Even a delayed receipt must not let change notifications relabel the user.
                assertEquals(0, session.state.value.user)
                adoption.complete(real.getResult()!!)
            }
            waitFor { readyCalls == 1 }
            main {
                assertEquals(1, session.state.value.user)
                assertNotNull(session.state.value.busy)
                assertTrue(budget in 1..5000)
                ready.complete(Outcome.failure(OutcomeCode.TIMED_OUT, null))
            }
            waitFor { session.state.value.available }
            main {
                assertEquals(1, session.state.value.user)
                assertTrue(session.state.value.waitTimedOut)
                assertFalse(session.userSheet)
            }
        } finally {
            main {
                field.set(session, original)
                original.close()
            }
        }
    }
}
