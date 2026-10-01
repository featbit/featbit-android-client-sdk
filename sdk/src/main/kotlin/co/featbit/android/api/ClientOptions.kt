package co.featbit.android.api

import co.featbit.android.datasource.DataSourceFactory
import java.net.URI
import java.util.Collections
import java.util.Locale

/** Immutable creation configuration. No workers, Android observers or network calls are started. */
public class ClientOptions private constructor(builder: Builder) {
    public val sdkKey: String? = builder.sdkKey
    public val initialUser: User? = builder.user
    public val streamingUrl: String? = builder.streamingUrl
    public val pollingUrl: String? = builder.pollingUrl
    public val eventsUrl: String? = builder.eventsUrl
    public val mode: SyncMode = if (builder.source != null) SyncMode.CUSTOM else builder.mode
    public val offline: Boolean = builder.offline
    public val disableEvents: Boolean = builder.disableEvents
    public val anonymousEnabled: Boolean = builder.anonymous
    public val automaticAttributes: Boolean = builder.automaticAttributes
    public val backgroundPolling: Boolean = builder.backgroundPolling
    public val pollingFallback: Boolean = builder.pollingFallback
    public val cacheEnabled: Boolean = builder.cacheEnabled
    public val transitionFlush: Boolean = builder.transitionFlush
    public val flushIntervalMillis: Long = builder.flushIntervalMillis
    public val eventCapacity: Int = builder.eventCapacity
    public val startupWaitMillis: Long = builder.startupWaitMillis
    public val requestTimeoutMillis: Long = builder.requestTimeoutMillis
    public val pollingIntervalMillis: Long = builder.pollingIntervalMillis
    public val backgroundPollingIntervalMillis: Long = builder.backgroundPollingIntervalMillis
    public val closeTimeoutMillis: Long = builder.closeTimeoutMillis
    public val flagGraceMillis: Long = builder.flagGraceMillis
    public val logLevel: LogLevel = builder.logLevel
    public val logger: Logger? = builder.logger
    public val source: DataSourceFactory? = builder.source
    /** Null is absent; an empty list is explicitly configured bootstrap. */
    public val bootstrap: List<BootstrapFlag>? = builder.bootstrap?.let { Collections.unmodifiableList(ArrayList(it)) }
    public val synchronizationHeaders: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(builder.syncHeaders))
    public val eventHeaders: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(builder.eventHeaders))
    public val privateAttributes: Set<String> = Collections.unmodifiableSet(LinkedHashSet(builder.privateAttributes))
    public val allAttributesPrivate: Boolean = builder.allPrivate

    public class Builder public constructor() {
        @get:JvmSynthetic @set:JvmSynthetic
        internal var sdkKey: String? = null
        @get:JvmSynthetic @set:JvmSynthetic
        internal var user: User? = null
        @get:JvmSynthetic @set:JvmSynthetic
        internal var streamingUrl: String? = null
        @get:JvmSynthetic @set:JvmSynthetic
        internal var pollingUrl: String? = null
        @get:JvmSynthetic @set:JvmSynthetic
        internal var eventsUrl: String? = null
        @get:JvmSynthetic @set:JvmSynthetic
        internal var mode: SyncMode = SyncMode.STREAMING
        @get:JvmSynthetic @set:JvmSynthetic
        internal var offline: Boolean = false
        @get:JvmSynthetic @set:JvmSynthetic
        internal var disableEvents: Boolean = false
        @get:JvmSynthetic @set:JvmSynthetic
        internal var anonymous: Boolean = false
        @get:JvmSynthetic @set:JvmSynthetic
        internal var automaticAttributes: Boolean = false
        @get:JvmSynthetic @set:JvmSynthetic
        internal var backgroundPolling: Boolean = false
        @get:JvmSynthetic @set:JvmSynthetic
        internal var pollingFallback: Boolean = false
        @get:JvmSynthetic @set:JvmSynthetic
        internal var cacheEnabled: Boolean = true
        @get:JvmSynthetic @set:JvmSynthetic
        internal var transitionFlush: Boolean = false
        @get:JvmSynthetic @set:JvmSynthetic
        internal var flushIntervalMillis: Long = 30_000
        @get:JvmSynthetic @set:JvmSynthetic
        internal var eventCapacity: Int = 10_000
        @get:JvmSynthetic @set:JvmSynthetic
        internal var startupWaitMillis: Long = 5_000
        @get:JvmSynthetic @set:JvmSynthetic
        internal var requestTimeoutMillis: Long = 10_000
        @get:JvmSynthetic @set:JvmSynthetic
        internal var pollingIntervalMillis: Long = 30_000
        @get:JvmSynthetic @set:JvmSynthetic
        internal var backgroundPollingIntervalMillis: Long = 900_000
        @get:JvmSynthetic @set:JvmSynthetic
        internal var closeTimeoutMillis: Long = 5_000
        @get:JvmSynthetic @set:JvmSynthetic
        internal var flagGraceMillis: Long = 0
        @get:JvmSynthetic @set:JvmSynthetic
        internal var logLevel: LogLevel = LogLevel.WARN
        @get:JvmSynthetic @set:JvmSynthetic
        internal var logger: Logger? = null
        @get:JvmSynthetic @set:JvmSynthetic
        internal var allPrivate: Boolean = false
        @get:JvmSynthetic @set:JvmSynthetic
        internal var source: DataSourceFactory? = null
        @get:JvmSynthetic @set:JvmSynthetic
        internal var bootstrap: List<BootstrapFlag>? = null
        @get:JvmSynthetic
        internal val syncHeaders: MutableMap<String, String> = LinkedHashMap()
        @get:JvmSynthetic
        internal val eventHeaders: MutableMap<String, String> = LinkedHashMap()
        @get:JvmSynthetic
        internal val privateAttributes: MutableSet<String> = LinkedHashSet()
        private var invalid: Boolean = false
        private var explicitTransport: Boolean = false
        public fun sdkKey(value: String?): Builder = apply { sdkKey = value }
        public fun user(value: User?): Builder = apply { user = value }
        public fun streamingUrl(value: String?): Builder = apply { streamingUrl = value }
        public fun pollingUrl(value: String?): Builder = apply { pollingUrl = value }
        public fun eventsUrl(value: String?): Builder = apply { eventsUrl = value }
        public fun mode(value: SyncMode): Builder = apply { mode = value; explicitTransport = true }
        public fun offline(value: Boolean): Builder = apply { offline = value }
        public fun disableEvents(value: Boolean): Builder = apply { disableEvents = value }
        public fun anonymousEnabled(value: Boolean): Builder = apply { anonymous = value }
        public fun automaticAttributes(value: Boolean): Builder = apply { automaticAttributes = value }
        public fun backgroundPolling(value: Boolean): Builder = apply { backgroundPolling = value; explicitTransport = true }
        public fun pollingFallback(value: Boolean): Builder = apply { pollingFallback = value }
        public fun cacheEnabled(value: Boolean): Builder = apply { cacheEnabled = value }
        public fun transitionFlush(value: Boolean): Builder = apply { transitionFlush = value }
        public fun flushIntervalMillis(value: Long): Builder = apply { flushIntervalMillis = value }
        public fun eventCapacity(value: Int): Builder = apply { eventCapacity = value }
        public fun startupWaitMillis(value: Long): Builder = apply { startupWaitMillis = value }
        public fun requestTimeoutMillis(value: Long): Builder = apply { requestTimeoutMillis = value }
        public fun pollingIntervalMillis(value: Long): Builder = apply { pollingIntervalMillis = value; explicitTransport = true }
        public fun backgroundPollingIntervalMillis(value: Long): Builder = apply { backgroundPollingIntervalMillis = value; explicitTransport = true }
        public fun closeTimeoutMillis(value: Long): Builder = apply { closeTimeoutMillis = value }
        public fun flagGraceMillis(value: Long): Builder = apply { flagGraceMillis = value }
        public fun logLevel(value: LogLevel): Builder = apply { logLevel = value }
        public fun logger(value: Logger?): Builder = apply { logger = value }
        public fun allAttributesPrivate(value: Boolean): Builder = apply { allPrivate = value }
        public fun source(value: DataSourceFactory?): Builder = apply { source = value }
        public fun privateAttribute(name: String?): Builder = apply {
            if (name.isNullOrEmpty()) invalid = true else privateAttributes.add(name)
        }
        public fun bootstrap(flags: List<BootstrapFlag?>?): Builder = apply {
            if (flags == null || flags.any { it == null } || flags.filterNotNull().map { it.key }.distinct().size != flags.size) {
                invalid = true
            } else bootstrap = flags.filterNotNull()
        }
        public fun synchronizationHeader(name: String?, value: String?): Builder = apply { header(syncHeaders, name, value) }
        public fun eventHeader(name: String?, value: String?): Builder = apply { header(eventHeaders, name, value) }
        private fun header(target: MutableMap<String, String>, name: String?, value: String?) {
            val lower = name?.lowercase(Locale.ROOT)
            val protected = lower in setOf("authorization", "host", "content-length", "content-type", "connection", "upgrade", "user-agent", "x-user-agent", "transfer-encoding", "accept-encoding") || lower?.startsWith("sec-websocket-") == true
            if (name.isNullOrEmpty() || !name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) || value == null || value.any { it.code < 32 || it.code > 126 } || protected || target.keys.any { it.equals(name, true) }) {
                invalid = true
            } else target[name] = value
        }
        public fun build(): Outcome<ClientOptions> {
            if (invalid) return Outcome.invalid("invalid_configuration")
            if (flushIntervalMillis !in 1_000..86_400_000 || eventCapacity !in 1..100_000 ||
                startupWaitMillis !in 1..300_000 || requestTimeoutMillis !in 1..300_000 ||
                pollingIntervalMillis !in 1_000..86_400_000 || backgroundPollingIntervalMillis !in 900_000..86_400_000 ||
                closeTimeoutMillis !in 1..300_000 || flagGraceMillis !in 0..30_000) return Outcome.invalid("invalid_limit")
            if (user == null && !anonymous) return Outcome.invalid("user_required", "user")
            if (automaticAttributes && user?.attributes?.keys?.any { it.startsWith("featbit.sdk.") } == true) return Outcome.invalid("reserved_attribute_prefix")
            if (source != null && (explicitTransport || mode != SyncMode.STREAMING || streamingUrl != null || pollingUrl != null || pollingFallback || backgroundPolling || syncHeaders.isNotEmpty())) return Outcome.invalid("custom_source_conflict")
            if (source is co.featbit.android.internal.LocalTestData && (!disableEvents || cacheEnabled)) return Outcome.invalid("test_data_configuration_conflict")
            if (source == null && mode == SyncMode.CUSTOM) return Outcome.invalid("source_required")
            if (pollingFallback && mode != SyncMode.STREAMING) return Outcome.invalid("fallback_requires_streaming")
            if (!offline) {
                if ((source == null || !disableEvents) && sdkKey.isNullOrEmpty()) return Outcome.invalid("sdk_key_required", "sdkKey")
                if (source == null && mode == SyncMode.STREAMING && !endpoint(streamingUrl, setOf("ws", "wss"))) return Outcome.invalid("invalid_endpoint", "streamingUrl")
                if (source == null && (mode == SyncMode.POLLING || pollingFallback || backgroundPolling) && !endpoint(pollingUrl, setOf("http", "https"))) return Outcome.invalid("invalid_endpoint", "pollingUrl")
                if (!disableEvents && !endpoint(eventsUrl, setOf("http", "https"))) return Outcome.invalid("invalid_endpoint", "eventsUrl")
            }
            return Outcome.success(ClientOptions(this))
        }
        private fun endpoint(value: String?, schemes: Set<String>): Boolean = try {
            val uri = URI(value ?: "")
            uri.scheme?.lowercase(Locale.ROOT) in schemes && !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.fragment == null && uri.query == null
        } catch (_: Exception) { false }
    }
    public companion object { @JvmStatic public fun builder(): Builder = Builder() }
}
