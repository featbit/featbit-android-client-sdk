package co.featbit.android.api

/** Creates the SDK runtime with local evaluation, synchronization, persistence and analytics. */
public interface ClientFactory {
    /**
     * Implementations retain only applicationContext; creation never waits for network readiness.
     */
    public fun create(
        applicationContext: android.content.Context,
        options: ClientOptions,
    ): Operation<FeatBitClient>

    public companion object {
        @JvmStatic
        public fun getDefault(): ClientFactory = co.featbit.android.internal.RuntimeFactory
    }
}

public interface FeatBitClient {
    public fun getVersion(): String

    public fun getConnectionInformation(): ConnectionInformation

    public fun isOffline(): Boolean

    public fun awaitReady(timeoutMillis: Long): Operation<ReadyResult>

    /**
     * Prepares and adopts a named user on the worker, then waits for flag readiness (or the
     * offline/local readiness policy). Receiving the Operation does not mean the user has changed;
     * reads may still use the previous user until adoption. Wait for SUCCESS before using new data.
     * Unlike [identifyContext], completion includes readiness, not just adoption. The timeout
     * includes worker queueing, preparation and readiness. Expiry before adoption prevents this
     * request from adopting later; expiry afterwards does not roll back the user or stop
     * synchronization. A failed result alone does not prove whether adoption occurred.
     */
    public fun identify(user: User, timeoutMillis: Long): Operation<ReadyResult>

    /**
     * Prepares and adopts the persisted anonymous user on the worker, then waits for flag
     * readiness. Like [identify], receiving the Operation does not mean the user has changed, and
     * the timeout includes worker queueing, preparation and readiness. Unlike
     * [identifyAnonymousContext], a timeout may happen before adoption or while waiting for data
     * afterwards. Use the split APIs when that distinction matters; do not infer adoption from a
     * timeout. This does not reset the anonymous key.
     */
    public fun identifyAnonymous(timeoutMillis: Long): Operation<ReadyResult>

    /**
     * Prepares and adopts [user] asynchronously, without waiting for flag data.
     *
     * Unlike [FeatBitClient.identify], SUCCESS confirms only identity adoption. Call
     * [FeatBitClient.awaitReady] afterwards to wait for data. Receiving the Operation does not
     * confirm adoption. A failed operation cannot later adopt its target identity.
     *
     * [timeoutMillis] covers queued preparation and adoption, not remote readiness. Detaching an
     * observer or timing out an external coroutine wait does not cancel this operation: inspect its
     * retained result. A later identity request can supersede this one.
     */
    public fun identifyContext(user: User, timeoutMillis: Long): Operation<IdentityReceipt>

    /**
     * Prepares the persisted anonymous identity and adopts it, without waiting for flag data.
     *
     * Unlike [FeatBitClient.identifyAnonymous], SUCCESS means only that the identity was adopted;
     * use [FeatBitClient.awaitReady] separately. The completion, timeout and supersession rules of
     * [identifyContext] apply. This does not reset the persisted anonymous key. Preparation may
     * persist a key even if adoption is subsequently superseded or times out.
     */
    public fun identifyAnonymousContext(timeoutMillis: Long): Operation<IdentityReceipt>

    public fun resetAnonymousIdentity(timeoutMillis: Long): Operation<ReadyResult>

    public fun setOffline(timeoutMillis: Long): Operation<ModeResult>

    public fun setOnline(timeoutMillis: Long): Operation<ModeResult>

    public fun boolVariation(key: String, fallback: Boolean): Boolean

    public fun boolVariationDetail(key: String, fallback: Boolean): EvaluationDetail<Boolean>

    public fun numberVariation(key: String, fallback: Double): Double

    public fun numberVariationDetail(key: String, fallback: Double): EvaluationDetail<Double>

    public fun stringVariation(key: String, fallback: String): String

    public fun stringVariationDetail(key: String, fallback: String): EvaluationDetail<String>

    public fun variation(key: String, fallback: FbValue): FbValue

    public fun variationDetail(key: String, fallback: FbValue): EvaluationDetail<FbValue>

    public fun jsonVariation(key: String, fallback: FbValue): FbValue

    public fun jsonVariationDetail(key: String, fallback: FbValue): EvaluationDetail<FbValue>

    public fun jsonTextVariation(key: String, fallback: String): String

    public fun jsonTextVariationDetail(key: String, fallback: String): EvaluationDetail<String>

    public fun allVariations(): Map<String, EvaluationDetail<String>>

    /** Named metric with a non-empty name. Group-deduplicated. */
    public fun track(name: String): Outcome<TrackResult>

    public fun track(name: String, numericValue: Double): Outcome<TrackResult>

    /**
     * Covers outstanding accepted events; waits at most requestTimeoutMillis. Timeout does not
     * cancel delivery.
     */
    public fun flush(): Operation<FlushResult>

    public fun clearCache(scope: CacheScope, timeoutMillis: Long): Operation<CacheClearResult>

    public fun subscribeChanges(listener: ChangeListener): Outcome<ChangeSubscription>

    public fun subscribeFlag(key: String, listener: ChangeListener): Outcome<ChangeSubscription>

    public fun subscribeStatus(listener: StatusListener): Outcome<Registration>

    public fun close(): Operation<CloseResult>
}

public enum class EvaluationReason {
    MATCH,
    CLIENT_NOT_READY,
    FLAG_NOT_FOUND,
    WRONG_TYPE,
    ERROR,
}

public data class EvaluationDetail<T>
public constructor(
    public val key: String,
    public val value: T,
    public val reason: EvaluationReason,
    public val explanation: String? = null,
)

public enum class ReadyResult {
    REMOTE_CONFIRMED,
    OFFLINE_LOCAL,
    CUSTOM_LOCAL,
}

public enum class ModeResult {
    ONLINE,
    OFFLINE,
}

public enum class TrackResult {
    ACCEPTED,
    DEDUPLICATED,
    SUPPRESSED,
}

public enum class FlushResult {
    EMPTY,
    ALL_DELIVERED,
    PROCESSED_WITH_LOSS,
}

public enum class CacheScope {
    CURRENT_CONTEXT,
    NAMESPACE,
}

public enum class CacheClearResult {
    CLEARED
}

public data class CloseResult
public constructor(public val undeliveredEvents: Long, public val cleanupComplete: Boolean)

/** Detaches this registration only; does not cancel the underlying operation. */
public interface Registration {
    public fun close(): Unit
}

public fun interface Completion<T> {
    public fun onComplete(result: Outcome<T>): Unit
}

public interface Operation<T> {
    /** Null means pending; the settled result remains queryable after client shutdown. */
    public fun getResult(): Outcome<T>?

    public fun observe(callback: Completion<T>): Outcome<Registration>
}

public fun interface ChangeListener {
    public fun onChange(change: FlagChange): Unit
}

public fun interface StatusListener {
    public fun onStatus(status: ConnectionInformation): Unit
}

public class FlagChange public constructor(keys: Set<String>, public val allFlagsChanged: Boolean) {
    public val keys: Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(keys))
}

public class ChangeSubscription
public constructor(
    public val registration: Registration,
    initialValues: Map<String, EvaluationDetail<String>>,
) {
    public val initialValues: Map<String, EvaluationDetail<String>> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(initialValues))
}
