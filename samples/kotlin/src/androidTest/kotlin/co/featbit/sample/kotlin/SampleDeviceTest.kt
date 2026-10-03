package co.featbit.sample.kotlin

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.*
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.assertion.ViewAssertions.*
import co.featbit.android.api.PauseReason
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Test-only instrumentation: no exported probe or behavior overrides in the sample APK. */
@RunWith(AndroidJUnit4::class)
class SampleDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun waitFor(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < until) {
            var ready = false
            instrumentation.runOnMainSync { ready = check() }
            if (ready) { instrumentation.waitForIdleSync(); return }
            Thread.sleep(80)
        }
        fail("Sample state did not settle within 15 seconds")
    }
    private fun capture(name: String) {
        instrumentation.runOnMainSync { (instrumentation.targetContext.applicationContext as CafeApplication).session.dismissMessage() }
        instrumentation.runOnMainSync {
            (instrumentation.targetContext.applicationContext as CafeApplication).session.dismissMessage()

        }
        instrumentation.waitForIdleSync(); Thread.sleep(700)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val file = File(directory, "$name.png")
        val descriptor = instrumentation.uiAutomation.executeShellCommand("screencap -p ${file.absolutePath}")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        assertTrue("Screenshot missing: $name", file.length() > 1000)
    }
    @Test fun flagListKeepsEvaluationInDetails() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var session: SampleSession
            scenario.onActivity { session = it.session }
            waitFor { session.state.value.available }
            scenario.onActivity { session.draft.local = true; session.connect() }
            waitFor { session.state.value.available && session.state.value.local }
            scenario.onActivity { session.destination = "Flags"; session.detailKey = null; it.navigate() }
            onView(withText(R.string.last_evaluation)).check(doesNotExist())
            onView(withText(R.string.evaluate)).check(doesNotExist())
            capture("flags-list-without-evaluation")
            val promo = session.specs[1].key
            onView(withText(promo)).perform(click())
            onView(withText(R.string.evaluate)).perform(scrollTo(), click())
            scenario.onActivity { assertTrue(session.state.value.reads.containsKey(promo)) }
            androidx.test.espresso.Espresso.pressBack()
            onView(withText(R.string.last_evaluation)).check(doesNotExist())
            onView(withText(R.string.evaluate)).check(doesNotExist())
        }
    }
    @Test fun demoFlagButtonsOpenMatchingDetails() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var session: SampleSession
            scenario.onActivity { session = it.session }
            waitFor { session.state.value.available }
            scenario.onActivity { session.draft.local = true; session.connect() }
            waitFor { session.state.value.available && session.state.value.local }
            val links = listOf(R.string.view_checkout_flag to 0, R.string.view_promo_flag to 1,
                R.string.view_discount_flag to 2, R.string.view_menu_flag to 3)
            for (compact in listOf(true, false)) {
                scenario.onActivity { session.edit(session.specs[0].key, compact.toString()); session.destination = "Demo"; session.detailKey = null; it.navigate() }
                waitFor { session.state.value.available && session.state.value.business.compact == compact }
                capture(if (compact) "flag-links-compact" else "flag-links-classic")
                for ((description, index) in links) {
                    onView(withContentDescription(description)).perform(scrollTo(), click())
                    scenario.onActivity { assertEquals(session.specs[index].key, session.detailKey) }
                    androidx.test.espresso.Espresso.pressBack()
                }
            }
        }
    }
    @Test fun completedUserSwitchClosesSheet() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var session: SampleSession
            scenario.onActivity { session = it.session }
            waitFor { session.state.value.available }
            scenario.onActivity { session.draft.local = true; session.connect() }
            waitFor { session.state.value.available && session.state.value.local }
            val target = 1 - session.state.value.user
            scenario.onActivity { session.userChoice = session.state.value.user; it.forms.showUsers() }
            onView(withText(org.hamcrest.Matchers.startsWith(session.people[target].name + "\n"))).perform(click())
            onView(org.hamcrest.Matchers.allOf(withText(R.string.switch_user), isAssignableFrom(com.google.android.material.button.MaterialButton::class.java))).perform(click())
            waitFor { session.state.value.available && session.state.value.user == target && !session.userSheet }
            onView(withText(R.string.switch_user)).check(doesNotExist())
            scenario.recreate()
            onView(withText(R.string.switch_user)).check(doesNotExist())
        }
    }
    @Test fun localBusinessEditingIdentityOfflineAndRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var session: SampleSession
            scenario.onActivity { session = it.session }
            waitFor { session.state.value.available }
            scenario.onActivity { session.draft.local = true; session.connect() }
            waitFor { session.state.value.available && session.state.value.local }
            scenario.onActivity { session.edit(null, restoreAll = true) }
            waitFor { session.state.value.available && session.state.value.business.compact }
            scenario.onActivity { session.identify(0); session.detailKey = null; session.formOpen = false; session.destination = "Demo"; it.navigate() }
            waitFor { session.state.value.available && session.state.value.user == 0 }
            onView(withText("Your daily coffee.")).check(matches(isDisplayed()))
            capture("phone-demo")
            scenario.onActivity { session.userChoice = session.state.value.user; it.forms.showUsers() }
            capture("phone-users")
            androidx.test.espresso.Espresso.pressBack()
            scenario.onActivity {
                assertEquals("4.50", session.state.value.business.total.toPlainString())
                session.selectSize("large")
                session.edit(session.specs[0].key, "false")
            }
            waitFor { session.state.value.available && !session.state.value.business.compact }
            capture("phone-classic")
            scenario.onActivity { assertEquals("large", session.state.value.selectedSize); session.edit(session.specs[0].key, "true") }
            waitFor { session.state.value.available && session.state.value.business.compact }
            scenario.onActivity { session.dismissMessage() }
            onView(org.hamcrest.Matchers.allOf(withId(R.id.nav_flags), isDisplayed())).perform(click())
            onView(withText("4 demo flags")).check(matches(isDisplayed()))
            scenario.onActivity { it.openFlag(session.specs[0].key) }
            onView(withText("Evaluate flag")).perform(scrollTo(), click())
            androidx.test.espresso.Espresso.pressBack()
            waitFor { session.state.value.reads.isNotEmpty() }
            capture("phone-flags")
            scenario.onActivity { session.edit(session.specs[0].key, "false") }
            waitFor { session.state.value.available && session.state.value.reads[session.specs[0].key]?.stale == true }
            scenario.onActivity { it.openFlag(session.specs[0].key) }
            capture("phone-detail-stale")
            onView(withText("Edit local value")).perform(scrollTo(), click())
            capture("phone-editor-boolean")
            androidx.test.espresso.Espresso.pressBack()
            scenario.onActivity {
                session.edit(session.specs[2].key, "101")
            }
            waitFor { session.state.value.available && session.state.value.business.discountInvalid }
            assertEquals("5.00", session.state.value.business.total.toPlainString())
            scenario.onActivity {
                session.editorKey = session.specs[2].key; session.editorText = "101"; it.forms.showEditor(session.specs[2].key)
            }
            capture("phone-editor-number")
            androidx.test.espresso.Espresso.pressBack()
            scenario.onActivity { session.editorKey = session.specs[1].key; session.editorText = session.localValue(session.specs[1].key); it.forms.showEditor(session.specs[1].key) }
            capture("phone-editor-string")
            androidx.test.espresso.Espresso.pressBack()
            scenario.onActivity { session.editorKey = session.specs[3].key; session.editorText = session.localValue(session.specs[3].key); it.forms.showEditor(session.specs[3].key) }
            capture("phone-editor-json")
            androidx.test.espresso.Espresso.pressBack()
            scenario.onActivity { session.edit(session.specs[3].key, "null") }
            waitFor { session.state.value.available && session.state.value.business.menuInvalid }
            scenario.onActivity { session.edit(session.specs[0].key) }
            waitFor { session.state.value.available && !session.state.value.snapshot.containsKey(session.specs[0].key) }
            scenario.onActivity { session.edit(null, restoreAll = true) }
            waitFor { session.state.value.available && session.state.value.snapshot.size == 4 && session.state.value.business.total.toPlainString() == "4.50" }
            scenario.onActivity { session.offline(true) }
            waitFor { session.state.value.available && session.state.value.status?.pauseReasons?.contains(PauseReason.OFFLINE) == true }
            scenario.onActivity { session.edit(session.specs[2].key, "20") }
            waitFor { session.state.value.available && session.state.value.message?.startsWith("Saved;") == true }
            assertEquals("4.50", session.state.value.business.total.toPlainString())
            scenario.onActivity { session.offline(false) }
            waitFor { session.state.value.available && session.state.value.business.total.toPlainString() == "4.00" }
            scenario.onActivity { session.identify(1) }
            waitFor { session.state.value.available && session.state.value.user == 1 }
            assertEquals("4.00", session.state.value.business.total.toPlainString())
            scenario.recreate()
            scenario.onActivity { assertSame(session, it.session); assertEquals(1, it.session.state.value.user); session.detailKey = null; session.destination = "Demo"; it.navigate(); session.order() }
            assertEquals("Events disabled in Local", session.state.value.lastTrack)
            onView(org.hamcrest.Matchers.allOf(withId(R.id.nav_inspect), isDisplayed())).perform(click())
            capture("phone-inspect")
            scenario.onActivity { session.draft.key = ""; session.draft.streaming = ""; session.draft.polling = ""; session.draft.eventsUrl = ""; it.openConnection(); session.draft.local = false; it.navigate() }
            onView(withText("Apply and reconnect")).perform(scrollTo(), click())
            assertTrue(session.state.value.local)
            capture("phone-connection-errors")
            // Draft survives Activity recreation but is never stored in a bundle.
            scenario.onActivity { session.draft.key = "fictional-draft" }
            scenario.recreate()
            scenario.onActivity { assertEquals("fictional-draft", it.session.draft.key) }
            scenario.onActivity {
                session.formOpen = false; session.detailKey = null; session.destination = "Demo"; session.draft.local = true
                session.draft.key = ""; session.edit(null, restoreAll = true); it.navigate()
            }
            waitFor { session.state.value.available && session.state.value.business.compact }
        }
    }
}
