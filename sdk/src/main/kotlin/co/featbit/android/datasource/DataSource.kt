package co.featbit.android.datasource

import co.featbit.android.api.*
import java.util.Collections

public enum class Provenance {
    REMOTE,
    LOCAL,
}

public data class SourceCapabilities
public constructor(
    public val provenance: Provenance,
    public val networkDependent: Boolean,
    /**
     * Stable deployment/source/format identity for REMOTE cache reuse, paired with sdkKey. Custom
     * sources have no built-in endpoint configuration; include the deployment here.
     */
    public val cacheDiscriminator: String? = null,
)

public interface DataSourceFactory {
    public fun capabilities(): SourceCapabilities

    /** Called outside SDK locks. Implementations validate their own immutable configuration. */
    public fun validate(): Outcome<SourceValidation>

    public fun create(context: SourceSessionContext, sink: SourceUpdateSink): Outcome<DataSource>
}

public enum class SourceValidation {
    VALID
}

public enum class SourceStarted {
    STARTED
}

public enum class SourceStopped {
    STOPPED
}

public interface DataSource {
    public fun start(completion: Completion<SourceStarted>): Unit

    public fun stop(completion: Completion<SourceStopped>): Unit
}

public class SourceSessionContext
public constructor(public val user: User, public val baseline: Baseline?)

/** Implemented/issued by the SDK. Identity is opaque and validated by the bound sink. */
public interface Baseline {
    public val records: Map<String, FlagRecord>
    public val cursor: Long
}

public interface SourceUpdateSink {
    public fun submit(update: SourceUpdate): Operation<SourceUpdateResult>

    public fun report(status: SourceStatus): Outcome<StatusAccepted>
}

public enum class StatusAccepted {
    ACCEPTED
}

public enum class SourceState {
    CONNECTING,
    INTERRUPTED,
    TERMINAL,
}

public data class SourceStatus
public constructor(public val state: SourceState, public val error: Diagnostic?)

public enum class SourceUpdateCode {
    COMMITTED,
    INVALID,
    INACTIVE,
    BACKPRESSURED,
    SUPERSEDED,
    CLOSED,
}

public data class SourceUpdateResult
public constructor(
    public val code: SourceUpdateCode,
    public val acceptedRecords: Int,
    public val skippedRecords: Int,
    public val baseline: Baseline?,
    public val error: Diagnostic?,
)

public data class VariationOption
public constructor(public val id: String, public val value: String)

/** Raw variationType is intentional: unknown types remain usable through supported typed reads. */
public class FlagRecord
private constructor(
    public val key: String,
    public val variation: String,
    public val variationType: String,
    /** Last flag change time in Unix milliseconds. */
    public val timestamp: Long,
    public val archived: Boolean,
    public val reason: String?,
    variationOptions: List<VariationOption>?,
) {
    public val variationOptions: List<VariationOption>? =
        variationOptions?.let { Collections.unmodifiableList(ArrayList(it)) }

    public class Builder
    public constructor(
        private val key: String?,
        private val variation: String?,
        private val variationType: String?,
        private val timestamp: Long,
    ) {
        private var archived: Boolean = false
        private var reason: String? = null
        private var variationOptions: List<VariationOption>? = null
        private var invalidOptions = false

        public fun archived(value: Boolean): Builder = apply { archived = value }

        public fun reason(value: String?): Builder = apply { reason = value }

        public fun variationOptions(value: List<VariationOption?>?): Builder = apply {
            invalidOptions = value?.any { it == null } == true
            variationOptions = value?.filterNotNull()
        }

        public fun build(): Outcome<FlagRecord> =
            if (
                key.isNullOrEmpty() ||
                    variation == null ||
                    variationType == null ||
                    timestamp < 0 ||
                    invalidOptions
            )
                Outcome.invalid("invalid_flag_record")
            else
                Outcome.success(
                    FlagRecord(
                        key,
                        variation,
                        variationType,
                        timestamp,
                        archived,
                        reason,
                        variationOptions,
                    )
                )
    }

    public companion object {
        @JvmStatic
        public fun builder(
            key: String?,
            variation: String?,
            variationType: String?,
            timestamp: Long,
        ): Builder = Builder(key, variation, variationType, timestamp)
    }
}

public sealed class SourceUpdate

public class FullUpdate private constructor(public val records: List<FlagRecord>) : SourceUpdate() {
    public companion object {
        @JvmStatic
        public fun create(records: List<FlagRecord?>?): Outcome<FullUpdate> =
            immutableRecords(records)?.let { Outcome.success(FullUpdate(it)) }
                ?: Outcome.invalid("invalid_update_records")
    }
}

public class PatchUpdate private constructor(public val records: List<FlagRecord>) :
    SourceUpdate() {
    public companion object {
        @JvmStatic
        public fun create(records: List<FlagRecord?>?): Outcome<PatchUpdate> =
            immutableRecords(records)?.let { Outcome.success(PatchUpdate(it)) }
                ?: Outcome.invalid("invalid_update_records")
    }
}

public class NoChange public constructor(public val baseline: Baseline) : SourceUpdate()

private fun immutableRecords(records: List<FlagRecord?>?): List<FlagRecord>? {
    if (records == null || records.any { it == null }) return null
    val copy = records.filterNotNull()
    if (copy.map { it.key }.distinct().size != copy.size) return null
    return Collections.unmodifiableList(copy)
}
