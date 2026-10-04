package co.featbit.consumer.java;

import android.content.Context;
import co.featbit.android.api.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Opt-in device check against the evaluation server's public Fake fixture. */
public final class NetworkSmoke {
    private static <T> Outcome<T> await(Operation<T> operation) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        operation.observe(result -> latch.countDown());
        if (!latch.await(15, TimeUnit.SECONDS))
            throw new AssertionError("network operation deadline");
        Outcome<T> result = operation.getResult();
        if (!result.isSuccess()) throw new AssertionError("network outcome: " + result.getCode());
        return result;
    }

    public static void verify(Context context, RuntimeSmoke.Done done) {
        new Thread(
                        () -> {
                            try {
                                for (SyncMode mode :
                                        new SyncMode[] {SyncMode.STREAMING, SyncMode.POLLING}) {
                                    ClientOptions options =
                                            ClientOptions.builder()
                                                    .user(
                                                            User.builder("java-phase4")
                                                                    .name("Java")
                                                                    .build()
                                                                    .getValue())
                                                    .sdkKey(
                                                            "gpnOV3wI3kKAO9q9viC0wQWdKZrVAf2U6gAnxl4lSH3w")
                                                    .streamingUrl("ws://127.0.0.1:5189")
                                                    .pollingUrl("http://127.0.0.1:5189")
                                                    .disableEvents(true)
                                                    .cacheEnabled(false)
                                                    .requestTimeoutMillis(1000)
                                                    .mode(mode)
                                                    .logger(
                                                            (level, diagnostic) ->
                                                                    android.util.Log.i(
                                                                            "FeatBitConsumer",
                                                                            "NETWORK_DIAGNOSTIC "
                                                                                    + diagnostic
                                                                                            .getCode()))
                                                    .build()
                                                    .getValue();
                                    FeatBitClient client =
                                            await(
                                                            ClientFactory.getDefault()
                                                                    .create(context, options))
                                                    .getValue();
                                    client.subscribeStatus(
                                            status ->
                                                    android.util.Log.i(
                                                            "FeatBitConsumer",
                                                            "NETWORK_STATUS "
                                                                    + mode
                                                                    + " "
                                                                    + status.getStatus()
                                                                    + " "
                                                                    + status.getPauseReasons()));
                                    try {
                                        await(client.awaitReady(10000));
                                        if (!client.boolVariation("returns-true", false))
                                            throw new AssertionError("remote value");
                                        await(
                                                client.identify(
                                                        User.builder("java-phase4-B")
                                                                .name("Java B")
                                                                .build()
                                                                .getValue(),
                                                        10000));
                                        await(client.setOffline(2000));
                                        await(client.setOnline(2000));
                                        await(client.awaitReady(10000));
                                    } finally {
                                        if (!await(client.close()).getValue().getCleanupComplete())
                                            throw new AssertionError("cleanup");
                                    }
                                }
                                done.accept(
                                        "PHASE4_PASS Java Streaming/Polling/Identify/Offline/Close");
                            } catch (Exception | AssertionError failure) {
                                done.accept("PHASE4_FAIL Java " + failure);
                            }
                        },
                        "FeatBit-network-consumer")
                .start();
    }
}
