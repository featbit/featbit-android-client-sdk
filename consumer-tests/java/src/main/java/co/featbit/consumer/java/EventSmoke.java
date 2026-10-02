package co.featbit.consumer.java;

import android.content.Context;
import co.featbit.android.api.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Opt-in real Android AAR event/operation test against the fictional target fixture. */
public final class EventSmoke {
    private static int step;
    private static <T> Outcome<T> await(Operation<T> operation) throws Exception {
        int current = ++step;
        android.util.Log.i("FeatBitConsumer", "EVENT_STEP " + current + " waiting");
        CountDownLatch latch = new CountDownLatch(1);
        Outcome<Registration> observed = operation.observe(result -> latch.countDown());
        if (!observed.isSuccess()) throw new AssertionError("event observer " + current + ": " + observed.getCode());
        if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("event operation " + current + " deadline, settled=" + (operation.getResult() != null));
        android.util.Log.i("FeatBitConsumer", "EVENT_STEP " + current + " " + operation.getResult().getCode());
        return operation.getResult();
    }
    private static void check(boolean value) { if (!value) throw new AssertionError("event contract"); }
    public static void verify(Context context, RuntimeSmoke.Done done) {
        step = 0;
        new Thread(() -> {
            try {
                ClientOptions options = ClientOptions.builder()
                    .user(User.builder("java-phase5").name("Java").attribute("private", AttributeValue.text("hidden").getValue()).build().getValue())
                    .sdkKey("gpnOV3wI3kKAO9q9viC0wQWdKZrVAf2U6gAnxl4lSH3w")
                    .pollingUrl("http://127.0.0.1:5189").mode(SyncMode.POLLING).eventsUrl("http://127.0.0.1:5189")
                    .privateAttribute("private").cacheEnabled(false)
                    .logger((level, diagnostic) -> android.util.Log.i("FeatBitConsumer", "EVENT_DIAGNOSTIC " + diagnostic.getCode())).build().getValue();
                FeatBitClient client = await(ClientFactory.getDefault().create(context, options)).getValue();
                client.subscribeStatus(status -> android.util.Log.i("FeatBitConsumer", "EVENT_STATUS " + status.getStatus() + " " + status.getPauseReasons()));
                try {
                    check(client.track("before_ready").getValue() == TrackResult.ACCEPTED);
                    check(client.track("before_ready").getValue() == TrackResult.DEDUPLICATED);
                    check(await(client.awaitReady(10000)).isSuccess());
                    check(client.boolVariation("returns-true", false));
                    check(await(client.flush()).getValue() == FlushResult.ALL_DELIVERED);
                    client.track("retained"); check(await(client.setOffline(2000)).isSuccess());
                    check(client.track("offline").getValue() == TrackResult.SUPPRESSED);
                    check(await(client.flush()).getCode() == OutcomeCode.DEFERRED);
                    check(await(client.setOnline(2000)).isSuccess());
                    Outcome<FlushResult> replay = await(client.flush());
                    check(replay.getValue() == FlushResult.ALL_DELIVERED || replay.getValue() == FlushResult.EMPTY);
                    client.track("final_close");
                } finally {
                    CloseResult closed = await(client.close()).getValue();
                    check(closed.getCleanupComplete() && closed.getUndeliveredEvents() == 0);
                }
                done.accept("PHASE5_PASS Java Track/Evaluation/Privacy/Flush/Offline/Close");
            } catch (Exception | AssertionError failure) { done.accept("PHASE5_FAIL Java " + failure); }
        }, "FeatBit-event-consumer").start();
    }
}
