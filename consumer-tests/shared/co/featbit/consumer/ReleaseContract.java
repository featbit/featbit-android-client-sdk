package co.featbit.consumer;

import android.os.StrictMode;
import co.featbit.android.api.*;

/** Shared assertions, compiled independently against Maven artifacts by each consumer. */
public final class ReleaseContract {
    private ReleaseContract() {}

    public static void verifyLocal(FeatBitClient client, String artifactVersion) {
        StrictMode.ThreadPolicy previous = StrictMode.getThreadPolicy();
        StrictMode.setThreadPolicy(
                new StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .detectNetwork()
                        .penaltyDeath()
                        .build());
        try {
            require(artifactVersion.equals(client.getVersion()), "client artifact version");
            require(artifactVersion.equals(SdkInfo.getVersion()), "static artifact version");
            ConnectionInformation info = client.getConnectionInformation();
            require(!info.getRemoteConfirmed(), "local data cannot confirm remote state");
            require(info.getLastSuccessAtMillis() == null, "local data has no remote success time");
            require(info.getLastFailureAtMillis() == null, "local data has no remote failure time");
            try {
                info.getPauseReasons().add(PauseReason.OFFLINE);
                throw new AssertionError("mutable pause reasons");
            } catch (UnsupportedOperationException expected) {
            }
            try {
                client.allVariations().clear();
                throw new AssertionError("mutable allVariations");
            } catch (UnsupportedOperationException expected) {
            }
            for (String key : client.allVariations().keySet()) {
                FbValue fallback = FbValue.jsonNull();
                require(
                        client.variationDetail(key, fallback).getReason() == EvaluationReason.MATCH,
                        "generic detailed read");
                client.variation(key, fallback);
            }
        } finally {
            StrictMode.setThreadPolicy(previous);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
