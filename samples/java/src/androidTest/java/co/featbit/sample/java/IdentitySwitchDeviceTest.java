package co.featbit.sample.java;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import co.featbit.android.api.*;
import java.lang.reflect.*;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test-APK-only delayed completions; the sample exposes no injection API. */
@RunWith(AndroidJUnit4.class)
public class IdentitySwitchDeviceTest {
    private void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private void waitFor(BooleanSupplier check) throws Exception {
        for (int i = 0; i < 200; i++) {
            boolean[] done = {false};
            main(() -> done[0] = check.getAsBoolean());
            if (done[0]) return;
            Thread.sleep(25);
        }
        fail("Identity operation did not settle");
    }

    private static class Pending<T> implements Operation<T> {
        Outcome<T> result;
        Completion<T> callback;

        public Outcome<T> getResult() {
            return result;
        }

        public Outcome<Registration> observe(Completion<T> callback) {
            this.callback = callback;
            return Outcome.success(() -> this.callback = null);
        }

        void complete(Outcome<T> outcome) {
            result = outcome;
            if (callback != null) callback.onComplete(outcome);
        }
    }

    @Test
    public void selectionFollowsAdoptionAndSurvivesReadinessTimeout() throws Exception {
        SampleSession[] holder = new SampleSession[1];
        main(
                () -> {
                    holder[0] =
                            new SampleSession(
                                    InstrumentationRegistry.getInstrumentation()
                                            .getTargetContext());
                    holder[0].draft.local = true;
                    holder[0].connect();
                });
        SampleSession session = holder[0];
        waitFor(() -> session.state.available());
        Field field = SampleSession.class.getDeclaredField("client");
        field.setAccessible(true);
        FeatBitClient original = (FeatBitClient) field.get(session);
        java.util.List<Pending<IdentityReceipt>> adoption = new java.util.ArrayList<>();
        adoption.add(new Pending<>());
        Pending<ReadyResult> ready = new Pending<>();
        int[] readyCalls = {0};
        long[] budget = {0};
        FeatBitClient proxy =
                (FeatBitClient)
                        Proxy.newProxyInstance(
                                FeatBitClient.class.getClassLoader(),
                                new Class<?>[] {FeatBitClient.class},
                                (obj, method, args) -> {
                                    switch (method.getName()) {
                                        case "identifyContext":
                                            return adoption.get(0);
                                        case "identify":
                                            throw new AssertionError(
                                                    "Separate adoption from readiness");
                                        case "awaitReady":
                                            readyCalls[0]++;
                                            budget[0] = (long) args[0];
                                            return ready;
                                        default:
                                            return method.invoke(original, args);
                                    }
                                });
        try {
            main(
                    () -> {
                        try {
                            field.set(session, proxy);
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                        session.userSheet = true;
                        session.identify(1);
                        assertEquals(0, session.state.user);
                        assertNotNull(session.state.busy);
                        session.identify(1);
                        adoption.get(0).complete(Outcome.failure(OutcomeCode.TIMED_OUT, null));
                    });
            waitFor(() -> session.state.available());
            main(
                    () -> {
                        assertEquals(0, session.state.user);
                        assertTrue(session.userSheet);
                        assertFalse(session.state.waitTimedOut);
                        assertEquals(0, readyCalls[0]);
                        adoption.set(0, new Pending<>());
                        session.identify(1);
                    });
            Operation<IdentityReceipt> real =
                    original.identifyContext(session.people.get(1).user(), 5000);
            waitFor(() -> real.getResult() != null);
            main(
                    () -> {
                        assertEquals(0, session.state.user);
                        adoption.get(0).complete(real.getResult());
                    });
            waitFor(() -> readyCalls[0] == 1);
            main(
                    () -> {
                        assertEquals(1, session.state.user);
                        assertNotNull(session.state.busy);
                        assertTrue(budget[0] > 0 && budget[0] <= 5000);
                        ready.complete(Outcome.failure(OutcomeCode.TIMED_OUT, null));
                    });
            waitFor(() -> session.state.available());
            main(
                    () -> {
                        assertEquals(1, session.state.user);
                        assertTrue(session.state.waitTimedOut);
                        assertFalse(session.userSheet);
                    });
        } finally {
            main(
                    () -> {
                        try {
                            field.set(session, original);
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                        original.close();
                    });
        }
    }
}
