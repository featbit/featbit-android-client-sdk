package co.featbit.sample.kotlin

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.featbit.android.api.*
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicitly enabled against samples/tools/protocol_fixture.py; not production service evidence.
 */
@RunWith(AndroidJUnit4::class)
class LiveSampleDeviceTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private fun waitFor(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            instrumentation.runOnMainSync { ok = check() }
            if (ok) {
                instrumentation.waitForIdleSync()
                return
            }
            Thread.sleep(80)
        }
        fail("Live fixture state did not arrive")
    }

    private fun capture(name: String) {
        instrumentation.runOnMainSync {
            (instrumentation.targetContext.applicationContext as CafeApplication)
                .session
                .dismissMessage()
        }
        instrumentation.waitForIdleSync()
        Thread.sleep(700)
        val directory =
            File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply {
                mkdirs()
            }
        val file = File(directory, "$name.png")
        val descriptor =
            instrumentation.uiAutomation.executeShellCommand("screencap -p ${file.absolutePath}")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        assertTrue("Screenshot missing: $name", file.length() > 1000)
    }

    @Test
    fun pollingStreamingTargetingEventsAndTerminalRecovery() {
        assumeTrue(
            "Run with -e live true and the loopback protocol fixture",
            InstrumentationRegistry.getArguments().getString("live") == "true",
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var session: SampleSession
            scenario.onActivity { session = it.session }
            waitFor { session.state.value.available }
            scenario.onActivity {
                session.draft.apply {
                    local = false
                    key = "fictional-sample-key"
                    mode = SyncMode.POLLING
                    polling = "http://10.0.2.2:5198"
                    streaming = "ws://10.0.2.2:5198"
                    events = true
                    eventsUrl = "http://10.0.2.2:5198"
                }
                session.connect()
            }
            waitFor {
                session.state.value.available && session.state.value.status?.remoteConfirmed == true
            }
            assertFalse(session.state.value.business.compact)
            scenario.onActivity { session.identify(1) }
            waitFor {
                session.state.value.available &&
                    session.state.value.user == 1 &&
                    session.state.value.business.compact
            }
            scenario.onActivity {
                session.draft.mode = SyncMode.STREAMING
                session.connect()
            }
            waitFor {
                session.state.value.available &&
                    session.state.value.status?.effectiveMode == SyncMode.STREAMING &&
                    session.state.value.status?.remoteConfirmed == true
            }
            scenario.onActivity {
                session.destination = "Demo"
                session.detailKey = null
                session.formOpen = false
                it.navigate()
            }
            capture("phone-live-demo")
            scenario.onActivity { session.order() }
            assertEquals("ACCEPTED", session.state.value.lastTrack)
            scenario.onActivity { session.flush() }
            waitFor { !session.state.value.flushPending && session.state.value.lastFlush != null }
            assertEquals("ALL_DELIVERED", session.state.value.lastFlush)
            scenario.onActivity {
                session.destination = "Inspect"
                it.navigate()
            }
            capture("phone-live-inspect")
            scenario.onActivity {
                session.destination = "Flags"
                session.evaluate(session.specs[0].key)
                it.navigate()
            }
            capture("phone-live-flags")
            scenario.onActivity { session.offline(true) }
            waitFor {
                session.state.value.available &&
                    session.state.value.status?.pauseReasons?.contains(PauseReason.OFFLINE) == true
            }
            scenario.onActivity { session.order() }
            assertEquals("SUPPRESSED", session.state.value.lastTrack)
            scenario.onActivity { session.offline(false) }
            waitFor {
                session.state.value.available &&
                    session.state.value.status?.pauseReasons?.contains(PauseReason.OFFLINE) == false
            }
            scenario.onActivity {
                session.draft.mode = SyncMode.POLLING
                session.draft.polling = "http://10.0.2.2:5198/denied"
                session.connect()
            }
            waitFor { session.state.value.status?.status == SyncStatus.TERMINAL }
            scenario.onActivity {
                session.destination = "Inspect"
                it.navigate()
            }
            capture("phone-terminal")
            scenario.onActivity {
                session.draft.local = true
                session.connect()
            }
            waitFor { session.state.value.available && session.state.value.local }
            scenario.onActivity { session.identify(0) }
            waitFor { session.state.value.available && session.state.value.user == 0 }
        }
    }
}
