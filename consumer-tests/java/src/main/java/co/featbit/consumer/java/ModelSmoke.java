package co.featbit.consumer.java;

import co.featbit.android.api.*;
import co.featbit.android.datasource.*;
import java.util.*;

/** Compiled solely against the Maven-published AAR; no source project dependency. */
public final class ModelSmoke {
    public static void verify() {
        User user =
                User.builder("java-user")
                        .name("Java User")
                        .attribute("plan", AttributeValue.text("test").getValue())
                        .build()
                        .getValue();
        BootstrapFlag flag = BootstrapFlag.create("enabled", "true", ValueType.BOOLEAN).getValue();
        ClientOptions options =
                ClientOptions.builder()
                        .user(user)
                        .offline(true)
                        .bootstrap(Collections.singletonList(flag))
                        .build()
                        .getValue();
        FbValue json =
                FbValue.ofObject(
                                Collections.singletonMap(
                                        "items",
                                        FbValue.ofArray(
                                                        Arrays.asList(
                                                                FbValue.jsonNull(),
                                                                FbValue.ofBoolean(true)))
                                                .getValue()))
                        .getValue();
        if (json.asObject().get("items").asArray().get(0).getKind() != FbValue.Kind.NULL)
            throw new AssertionError("JSON null");
        if (FbValue.ofNumber(Double.NaN).isSuccess()) throw new AssertionError("finite number");
        if (options.getBootstrap().size() != 1 || !options.getOffline())
            throw new AssertionError("options");
        if (!SdkInfo.getVersion().equals(BuildConfig.EXPECTED_SDK_VERSION))
            throw new AssertionError("artifact version");
        FlagRecord record =
                FlagRecord.builder("enabled", "true", "boolean", 1769702003515L)
                        .variationOptions(
                                Collections.singletonList(new VariationOption("on", "true")))
                        .build()
                        .getValue();
        if (record.getTimestamp() != 1769702003515L) throw new AssertionError("flag timestamp");
        if (!record.getVariation().equals("true") || !record.getVariationType().equals("boolean"))
            throw new AssertionError("flag fields");
        if (!record.getVariationOptions().get(0).getId().equals("on"))
            throw new AssertionError("variation metadata");
        if (!FullUpdate.create(Collections.singletonList(record)).isSuccess())
            throw new AssertionError("source model");
        co.featbit.android.testing.TestData data =
                co.featbit.android.testing.TestDataFactory.getDefault()
                        .create(Collections.emptyList())
                        .getValue();
        if (data.update(BootstrapFlag.create("saved", "1", ValueType.NUMBER).getValue())
                        .getResult()
                        .getValue()
                != co.featbit.android.testing.TestDataResult.SAVED_FOR_NEXT_START)
            throw new AssertionError("saved TestData");
        if (!data.clientOptions(user).getValue().getDisableEvents()
                || data.clientOptions(user).getValue().getCacheEnabled())
            throw new AssertionError("TestData gates");
    }

    // Independent Java implementation proves the public extension does not require Kotlin
    // internals.
    public static final class LocalFactory implements DataSourceFactory {
        @Override
        public SourceCapabilities capabilities() {
            return new SourceCapabilities(Provenance.LOCAL, false, null);
        }

        @Override
        public Outcome<SourceValidation> validate() {
            return Outcome.success(SourceValidation.VALID);
        }

        @Override
        public Outcome<DataSource> create(SourceSessionContext context, SourceUpdateSink sink) {
            return Outcome.success(
                    new DataSource() {
                        @Override
                        public void start(Completion<SourceStarted> completion) {
                            sink.submit(FullUpdate.create(Collections.emptyList()).getValue());
                            completion.onComplete(Outcome.success(SourceStarted.STARTED));
                        }

                        @Override
                        public void stop(Completion<SourceStopped> completion) {
                            completion.onComplete(Outcome.success(SourceStopped.STOPPED));
                        }
                    });
        }
    }
}
