package co.featbit.sample.java;

import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import android.app.Instrumentation;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import co.featbit.android.api.*;
import java.io.*;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;

/** Test APK only: the ordinary sample has no exported probe or fixture endpoints. */
@RunWith(AndroidJUnit4.class)
public class SampleDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private SampleSession session;

    private void waitFor(BooleanSupplier check) throws Exception {
        long end = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < end) {
            boolean[] ok = {false};
            instrumentation.runOnMainSync(() -> ok[0] = check.getAsBoolean());
            if (ok[0]) {
                instrumentation.waitForIdleSync();
                return;
            }
            Thread.sleep(80);
        }
        fail("Sample state did not settle");
    }

    private void capture(String name) throws Exception {
        instrumentation.runOnMainSync(session::dismissMessage);
        instrumentation.waitForIdleSync();
        Thread.sleep(700);
        File directory =
                new File(
                        instrumentation.getTargetContext().getExternalFilesDir(null),
                        "screenshots");
        directory.mkdirs();
        File file = new File(directory, name + ".png");
        try (InputStream in =
                new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        instrumentation
                                .getUiAutomation()
                                .executeShellCommand("screencap -p " + file.getAbsolutePath()))) {
            byte[] buffer = new byte[4096];
            while (in.read(buffer) != -1) {}
        }
        assertTrue(file.length() > 1000);
    }

    private ActivityScenario<MainActivity> local() throws Exception {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(a -> session = a.session);
        waitFor(() -> session.state.available());
        scenario.onActivity(
                a -> {
                    session.draft.local = true;
                    session.connect();
                });
        waitFor(() -> session.state.available() && session.state.local);
        scenario.onActivity(a -> session.offline(false));
        waitFor(() -> session.state.available());
        scenario.onActivity(a -> session.edit(null, null, true, ok -> {}));
        waitFor(() -> session.state.available() && session.state.business.compact);
        scenario.onActivity(
                a -> {
                    session.identify(0);
                    session.formOpen = false;
                    session.detailKey = null;
                    session.destination = "Demo";
                    a.navigate();
                });
        waitFor(() -> session.state.available() && session.state.user == 0);
        return scenario;
    }

    @Test
    public void configurationScreens() throws Exception {
        Assume.assumeTrue(
                "Optional visual configuration pass",
                "true".equals(InstrumentationRegistry.getArguments().getString("visual")));
        String prefix = InstrumentationRegistry.getArguments().getString("capturePrefix", "visual");
        try (ActivityScenario<MainActivity> scenario = local()) {
            capture(prefix + "-demo");
            onView(withText(R.string.place_order))
                    .perform(scrollTo())
                    .check(matches(isDisplayed()));
            capture(prefix + "-checkout");
            scenario.onActivity(
                    a -> {
                        session.destination = "Flags";
                        a.navigate();
                    });
            capture(prefix + "-flags");
            scenario.onActivity(
                    a -> {
                        session.destination = "Inspect";
                        a.navigate();
                    });
            capture(prefix + "-inspect");
            scenario.onActivity(
                    a -> {
                        session.draft.local = false;
                        session.draft.pollingFallback = true;
                        session.draft.mode = SyncMode.STREAMING;
                        session.draft.events = true;
                        a.openConnection();
                    });
            capture(prefix + "-connection");
            onView(withText(R.string.apply_reconnect))
                    .perform(scrollTo())
                    .check(matches(isDisplayed()));
            capture(prefix + "-connection-bottom");
        }
    }

    @Test
    public void localEditingIdentityAndRecreation() throws Exception {
        try (ActivityScenario<MainActivity> scenario = local()) {
            assertEquals("4.50", session.state.business.total().toPlainString());
            capture("phone-demo");
            int[] links = {
                R.string.view_checkout_flag,
                R.string.view_promo_flag,
                R.string.view_discount_flag,
                R.string.view_menu_flag
            };
            int[] indexes = {0, 1, 2, 3};
            for (boolean compact : new boolean[] {true, false}) {
                scenario.onActivity(
                        a -> session.edit(session.specs.get(0).key, Boolean.toString(compact)));
                waitFor(
                        () ->
                                session.state.available()
                                        && session.state.business.compact == compact);
                capture(compact ? "phone-compact" : "phone-classic");
                for (int i = 0; i < links.length; i++) {
                    onView(withContentDescription(links[i])).perform(scrollTo(), click());
                    assertEquals(session.specs.get(indexes[i]).key, session.detailKey);
                    androidx.test.espresso.Espresso.pressBack();
                }
            }
            scenario.onActivity(
                    a -> {
                        session.destination = "Flags";
                        a.navigate();
                    });
            onView(withText(R.string.evaluate)).check(doesNotExist());
            capture("phone-flags");
            scenario.onActivity(a -> a.openFlag(session.specs.get(0).key));
            onView(withText(R.string.evaluate)).perform(scrollTo(), click());
            assertFalse(session.state.reads.isEmpty());
            scenario.onActivity(a -> session.edit(session.specs.get(0).key, "true"));
            waitFor(
                    () ->
                            session.state.available()
                                    && session.state.reads.get(session.specs.get(0).key).stale);
            capture("phone-detail");
            for (int i = 0; i < 4; i++) {
                final int index = i;
                scenario.onActivity(
                        a -> {
                            session.editorKey = session.specs.get(index).key;
                            session.editorText = session.localValue(session.editorKey);
                            a.forms.showEditor(session.editorKey);
                        });
                capture("phone-editor-" + i);
                androidx.test.espresso.Espresso.pressBack();
            }
            scenario.onActivity(a -> session.edit(session.specs.get(2).key, "99.9"));
            waitFor(
                    () ->
                            session.state.available()
                                    && session.state
                                            .business
                                            .total()
                                            .toPlainString()
                                            .equals("0.01"));
            scenario.onActivity(a -> session.edit(session.specs.get(2).key, "101"));
            waitFor(() -> session.state.available() && session.state.business.discountInvalid);
            assertEquals("5.00", session.state.business.total().toPlainString());
            scenario.onActivity(a -> session.edit(session.specs.get(3).key, "null"));
            waitFor(() -> session.state.available() && session.state.business.menuInvalid);
            scenario.onActivity(a -> session.edit(session.specs.get(0).key, null));
            waitFor(
                    () ->
                            session.state.available()
                                    && !session.state.snapshot.containsKey(
                                            session.specs.get(0).key));
            scenario.onActivity(a -> session.edit(null, null, true, ok -> {}));
            waitFor(() -> session.state.available() && session.state.snapshot.size() == 4);
            scenario.onActivity(
                    a -> {
                        session.selectSize("large");
                        session.offline(true);
                    });
            waitFor(
                    () ->
                            session.state.available()
                                    && session.state
                                            .status
                                            .getPauseReasons()
                                            .contains(PauseReason.OFFLINE));
            scenario.onActivity(a -> session.edit(session.specs.get(2).key, "20"));
            waitFor(() -> session.state.available() && session.state.message.startsWith("Saved;"));
            assertEquals("4.50", session.state.business.total().toPlainString());
            scenario.onActivity(a -> session.offline(false));
            waitFor(
                    () ->
                            session.state.available()
                                    && session.state
                                            .business
                                            .total()
                                            .toPlainString()
                                            .equals("4.00"));
            scenario.onActivity(
                    a -> {
                        session.userChoice = session.state.user;
                        a.forms.showUsers();
                    });
            capture("phone-users");
            onView(withText(startsWith("Sam\n"))).perform(click());
            onView(
                            allOf(
                                    withText(R.string.switch_user),
                                    isAssignableFrom(
                                            com.google.android.material.button.MaterialButton
                                                    .class)))
                    .perform(click());
            waitFor(
                    () ->
                            session.state.available()
                                    && session.state.user == 1
                                    && !session.userSheet);
            onView(withText(R.string.switch_user)).check(doesNotExist());
            scenario.recreate();
            scenario.onActivity(
                    a -> {
                        assertSame(session, a.session);
                        assertEquals("large", session.state.selectedSize);
                        session.order();
                        session.detailKey = null;
                        session.destination = "Inspect";
                        a.navigate();
                    });
            assertEquals("Events disabled in Local", session.state.lastTrack);
            capture("phone-inspect");
        }
    }

    @Test
    public void fallbackDraftAndValidation() throws Exception {
        try (ActivityScenario<MainActivity> scenario = local()) {
            scenario.onActivity(
                    a -> {
                        session.draft.local = false;
                        session.draft.key = "fictional-key";
                        session.draft.streaming = "wss://evaluation.example.com";
                        session.draft.polling = "";
                        session.draft.events = false;
                        session.draft.mode = SyncMode.STREAMING;
                        session.draft.pollingFallback = false;
                        a.openConnection();
                        assertTrue(session.validateDraft().isEmpty());
                    });
            onView(withText(R.string.polling_fallback))
                    .check(matches(isNotChecked()))
                    .perform(scrollTo(), click());
            onView(withText(R.string.apply_reconnect)).perform(scrollTo(), click());
            onView(withText("Polling URL is required when fallback is enabled"))
                    .check(matches(isDisplayed()));
            assertTrue(session.state.local);
            assertTrue(session.state.available());
            scenario.onActivity(
                    a -> {
                        session.draft.polling = "wss://wrong.example.com";
                        assertTrue(session.validateDraft().containsKey("pollingUrl"));
                        session.draft.polling = "https://evaluation.example.com";
                        a.navigate();
                    });
            scenario.recreate();
            onView(withText(R.string.polling_fallback)).check(matches(isChecked()));
            capture("phone-connection");
            onView(withText(startsWith("Polling\n"))).perform(scrollTo(), click());
            onView(withText(R.string.polling_fallback)).check(doesNotExist());
            scenario.onActivity(
                    a -> {
                        session.draft.streaming = "invalid inactive";
                        assertTrue(session.validateDraft().isEmpty());
                    });
            onView(withText(startsWith("Streaming\n"))).perform(scrollTo(), click());
            onView(withText(R.string.polling_fallback))
                    .check(matches(isChecked()))
                    .perform(scrollTo(), click());
            scenario.onActivity(
                    a -> {
                        session.draft.streaming = "ws://localhost:5100";
                        session.draft.polling = "invalid inactive";
                        assertTrue(session.validateDraft().isEmpty());
                        session.draft.pollingFallback = true;
                        session.draft.events = true;
                        for (String host :
                                new String[] {
                                    "localhost",
                                    "10.0.2.2",
                                    "192.168.1.20",
                                    "evaluation.example.com"
                                }) {
                            assertTrue(
                                    android.security.NetworkSecurityPolicy.getInstance()
                                            .isCleartextTrafficPermitted(host));
                            session.draft.streaming = "ws://" + host;
                            session.draft.polling = "http://" + host;
                            session.draft.eventsUrl = "http://" + host;
                            assertTrue(
                                    session.validateDraft().toString(),
                                    session.validateDraft().isEmpty());
                        }
                    });
        }
    }

    @Test
    public void livePollingStreamingEventsAndTerminalRecovery() throws Exception {
        Assume.assumeTrue(
                "Requires loopback fixture",
                "true".equals(InstrumentationRegistry.getArguments().getString("live")));
        try (ActivityScenario<MainActivity> scenario = local()) {
            scenario.onActivity(
                    a -> {
                        session.draft.local = false;
                        session.draft.key = "fictional-sample-key";
                        session.draft.mode = SyncMode.POLLING;
                        session.draft.pollingFallback = false;
                        session.draft.polling = "http://10.0.2.2:5198";
                        session.draft.streaming = "ws://10.0.2.2:5198";
                        session.draft.events = true;
                        session.draft.eventsUrl = "http://10.0.2.2:5198";
                        session.connect();
                    });
            waitFor(() -> session.state.available() && session.state.status.getRemoteConfirmed());
            assertFalse(session.state.business.compact);
            scenario.onActivity(a -> session.identify(1));
            waitFor(
                    () ->
                            session.state.available()
                                    && session.state.user == 1
                                    && session.state.business.compact);
            scenario.onActivity(
                    a -> {
                        session.draft.mode = SyncMode.STREAMING;
                        session.connect();
                    });
            waitFor(
                    () ->
                            session.state.available()
                                    && session.state.status.getRemoteConfirmed()
                                    && session.state.status.getEffectiveMode()
                                            == SyncMode.STREAMING);
            capture("phone-live-demo");
            scenario.onActivity(a -> session.order());
            assertEquals("ACCEPTED", session.state.lastTrack);
            scenario.onActivity(a -> session.flush());
            waitFor(() -> !session.state.flushPending && session.state.lastFlush != null);
            assertEquals("ALL_DELIVERED", session.state.lastFlush);
            scenario.onActivity(
                    a -> {
                        session.destination = "Inspect";
                        a.navigate();
                    });
            capture("phone-live-inspect");
            scenario.onActivity(a -> session.offline(true));
            waitFor(
                    () ->
                            session.state.available()
                                    && session.state
                                            .status
                                            .getPauseReasons()
                                            .contains(PauseReason.OFFLINE));
            scenario.onActivity(a -> session.order());
            assertEquals("SUPPRESSED", session.state.lastTrack);
            scenario.onActivity(a -> session.offline(false));
            waitFor(
                    () ->
                            session.state.available()
                                    && !session.state
                                            .status
                                            .getPauseReasons()
                                            .contains(PauseReason.OFFLINE));
            scenario.onActivity(
                    a -> {
                        session.draft.mode = SyncMode.POLLING;
                        session.draft.polling = "http://10.0.2.2:5198/denied";
                        session.connect();
                    });
            waitFor(
                    () ->
                            session.state.status != null
                                    && session.state.status.getStatus() == SyncStatus.TERMINAL);
            capture("phone-terminal");
            scenario.onActivity(
                    a -> {
                        session.draft.local = true;
                        session.connect();
                    });
            waitFor(() -> session.state.available() && session.state.local);
        }
    }
}
