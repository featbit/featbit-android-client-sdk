package co.featbit.android.api

public enum class SyncMode {
    STREAMING,
    POLLING,
    CUSTOM,
}

public enum class SyncStatus {
    WAITING,
    READY,
    STALE,
    TERMINAL,
    CLOSED,
}

public enum class PauseReason {
    OFFLINE,
    BACKGROUND,
    NETWORK_UNAVAILABLE,
    CLOSING,
}

public enum class RecoveryStatus {
    NONE,
    COOLING_DOWN,
    PROBING,
}

/** Times are nullable Unix epoch milliseconds for the current context, never timeout clocks. */
public class ConnectionInformation
public constructor(
    public val configuredMode: SyncMode,
    public val effectiveMode: SyncMode,
    public val status: SyncStatus,
    pauseReasons: Set<PauseReason>,
    public val localDataAvailable: Boolean,
    public val remoteConfirmed: Boolean,
    public val lastSuccessAtMillis: Long?,
    public val lastFailureAtMillis: Long?,
    public val failure: Diagnostic?,
    public val recovery: RecoveryStatus,
    public val candidateFailure: Diagnostic?,
) {
    public val pauseReasons: Set<PauseReason> =
        java.util.Collections.unmodifiableSet(LinkedHashSet(pauseReasons))
}

/** Build identity only; does not create a runtime client. */
public object SdkInfo {
    @JvmStatic public fun getVersion(): String = co.featbit.android.BuildConfig.SDK_VERSION
}
