package co.featbit.android.datasource

import co.featbit.android.api.*
import java.util.Collections

public enum class Provenance { REMOTE, LOCAL }
public data class SourceCapabilities public constructor(
    public val provenance: Provenance,
    public val networkDependent: Boolean,
    public val cacheDiscriminator: String? = null,
)
public interface DataSourceFactory {
    public fun capabilities(): SourceCapabilities
    /** Called outside SDK locks. Implementations validate their own immutable configuration. */
    public fun validate(): Outcome<SourceValidation>
    public fun create(context: SourceSessionContext, sink: SourceUpdateSink): Outcome<DataSource>
}
public enum class SourceValidation { VALID }
public enum class SourceStarted { STARTED }
public enum class SourceStopped { STOPPED }
public interface DataSource {
    public fun start(completion: Completion<SourceStarted>): Unit
    public fun stop(completion: Completion<SourceStopped>): Unit
}
public class SourceSessionContext public constructor(public val user: User, public val baseline: Baseline?)
/** Implemented/issued by the SDK. Identity is opaque and validated by the bound sink. */
public interface Baseline {
    public val records: Map<String, FlagRecord>
    public val cursor: Long
}
public interface SourceUpdateSink {
    public fun submit(update: SourceUpdate): Operation<SourceUpdateResult>
    public fun report(status: SourceStatus): Outcome<StatusAccepted>
}
public enum class StatusAccepted { ACCEPTED }
public enum class SourceState { CONNECTING, INTERRUPTED, TERMINAL }
public data class SourceStatus public constructor(public val state: SourceState, public val error: Diagnostic?)
public enum class SourceUpdateCode { COMMITTED, INVALID, INACTIVE, BACKPRESSURED, SUPERSEDED, CLOSED }
public data class SourceUpdateResult public constructor(
    public val code: SourceUpdateCode,
    public val acceptedRecords: Int,
    public val skippedRecords: Int,
    public val baseline: Baseline?,
    public val error: Diagnostic?,
)
public data class VariationOption public constructor(public val id: String, public val value: String)
public class AnalyticsMetadata public constructor(options: List<VariationOption>, public val sendToExperiment: Boolean?) {
    public val options: List<VariationOption> = Collections.unmodifiableList(ArrayList(options))
}
/** Raw declaredType is intentional: unknown types remain usable through supported typed reads. */
public class FlagRecord private constructor(
    public val key: String,
    public val value: String,
    public val declaredType: String,
    public val version: Long,
    public val archived: Boolean,
    public val reason: String?,
    public val analytics: AnalyticsMetadata?,
) {
    public class Builder public constructor(private val key: String?, private val value: String?, private val declaredType: String?, private val version: Long) {
        private var archived: Boolean = false
        private var reason: String? = null
        private var analytics: AnalyticsMetadata? = null
        public fun archived(value: Boolean): Builder = apply { archived = value }
        public fun reason(value: String?): Builder = apply { reason = value }
        public fun analytics(value: AnalyticsMetadata?): Builder = apply { analytics = value }
        public fun build(): Outcome<FlagRecord> =
            if (key.isNullOrEmpty() || value == null || declaredType == null || version < 0) Outcome.invalid("invalid_flag_record")
            else Outcome.success(FlagRecord(key, value, declaredType, version, archived, reason, analytics))
    }
    public companion object {
        @JvmStatic public fun builder(key: String?, value: String?, declaredType: String?, version: Long): Builder = Builder(key, value, declaredType, version)
    }
}

public sealed class SourceUpdate
public class FullUpdate private constructor(public val records: List<FlagRecord>) : SourceUpdate() {
    public companion object {
        @JvmStatic public fun create(records: List<FlagRecord?>?): Outcome<FullUpdate> =
            immutableRecords(records)?.let { Outcome.success(FullUpdate(it)) } ?: Outcome.invalid("invalid_update_records")
    }
}
public class PatchUpdate private constructor(public val records: List<FlagRecord>) : SourceUpdate() {
    public companion object {
        @JvmStatic public fun create(records: List<FlagRecord?>?): Outcome<PatchUpdate> =
            immutableRecords(records)?.let { Outcome.success(PatchUpdate(it)) } ?: Outcome.invalid("invalid_update_records")
    }
}
public class NoChange public constructor(public val baseline: Baseline) : SourceUpdate()

private fun immutableRecords(records: List<FlagRecord?>?): List<FlagRecord>? {
    if (records == null || records.any { it == null }) return null
    val copy = records.filterNotNull()
    if (copy.map { it.key }.distinct().size != copy.size) return null
    return Collections.unmodifiableList(copy)
}
