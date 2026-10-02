package co.featbit.consumer.java;

import android.content.Context;
import co.featbit.android.api.*;
import co.featbit.android.testing.*;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs against the published AAR, with real Android main-thread completion delivery. */
public final class RuntimeSmoke {
    public interface Done { void accept(String result); }
    public static void verify(Context context, Done completion) {
        User user = User.builder("java-runtime").name("Java Runtime").build().getValue();
        TestData data = TestDataFactory.getDefault().create(Collections.singletonList(
            BootstrapFlag.create("enabled", "true", ValueType.BOOLEAN).getValue())).getValue();
        ClientFactory.getDefault().create(context, data.clientOptions(user).getValue()).observe(created -> {
            if (!created.isSuccess()) { completion.accept("FAIL: create " + created.getCode()); return; }
            FeatBitClient client = created.getValue();
            client.awaitReady(2000).observe(ready -> {
                try {
                    require(ready.getValue() == ReadyResult.CUSTOM_LOCAL, "local readiness");
                    co.featbit.consumer.ReleaseContract.verifyLocal(client, BuildConfig.EXPECTED_SDK_VERSION);
                    require(client.boolVariation("enabled", false), "Boolean evaluation");
                    require(!client.getConnectionInformation().getRemoteConfirmed(), "local provenance");
                    AtomicInteger changes = new AtomicInteger();
                    ChangeSubscription subscription = client.subscribeFlag("enabled", change -> changes.incrementAndGet()).getValue();
                    data.update(BootstrapFlag.create("enabled", "false", ValueType.BOOLEAN).getValue()).observe(updated -> {
                        try {
                            require(updated.getValue() == TestDataResult.COMMITTED, "committed update");
                            require(changes.get() == 1, "main-thread change callback");
                            require(!client.boolVariation("enabled", true), "updated evaluation");
                            subscription.getRegistration().close();
                            client.identify(User.builder("java-B").name("Java B").build().getValue(), 2000).observe(identified -> {
                                try {
                                    require(identified.getValue() == ReadyResult.CUSTOM_LOCAL, "Identify");
                                    require(client.track("disabled").getValue() == TrackResult.SUPPRESSED, "disabled events");
                                    client.setOffline(2000).observe(offline -> {
                                        try {
                                            require(offline.isSuccess() && client.isOffline(), "offline");
                                            require(data.remove("enabled").getResult().getValue() == TestDataResult.SAVED_FOR_NEXT_START, "inactive update");
                                            client.close().observe(closed -> {
                                                try {
                                                    require(closed.isSuccess() && closed.getValue().getCleanupComplete(), "close");
                                                    require(client.getConnectionInformation().getStatus() == SyncStatus.CLOSED, "closed state");
                                                    verifyCustom(context, user, completion);
                                                } catch (AssertionError | RuntimeException e) { completion.accept("FAIL: " + e); }
                                            });
                                        } catch (AssertionError | RuntimeException e) { client.close(); completion.accept("FAIL: " + e); }
                                    });
                                } catch (AssertionError | RuntimeException e) { client.close(); completion.accept("FAIL: " + e); }
                            });
                        } catch (AssertionError | RuntimeException e) { client.close(); completion.accept("FAIL: " + e); }
                    });
                } catch (AssertionError | RuntimeException e) { client.close(); completion.accept("FAIL: " + e); }
            });
        });
    }
    private static void verifyCustom(Context context, User user, Done completion) {
        ClientOptions options = ClientOptions.builder().user(user).source(new ModelSmoke.LocalFactory())
            .disableEvents(true).cacheEnabled(false).build().getValue();
        ClientFactory.getDefault().create(context, options).observe(created -> {
            if (!created.isSuccess()) { completion.accept("FAIL: Java custom create"); return; }
            FeatBitClient client = created.getValue();
            client.awaitReady(2000).observe(ready -> client.close().observe(closed -> {
                if (ready.getValue() != ReadyResult.CUSTOM_LOCAL || !closed.getValue().getCleanupComplete()) completion.accept("FAIL: Java custom lifecycle");
                else completion.accept("PASS · Java runtime: TestData、Custom、读取、订阅、Identify、Offline、Close");
            }));
        });
    }
    private static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
