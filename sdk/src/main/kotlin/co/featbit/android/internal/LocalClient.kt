package co.featbit.android.internal

import co.featbit.android.api.*
import co.featbit.android.datasource.*
import java.net.URI

internal data class View(
    val records: Map<String, FlagRecord>, val defaults: Map<String, FlagRecord>,
    val available: Boolean, val confirmed: Boolean = false, val localReady: Boolean = false,
    val baseline: Baseline? = null,
) {
    fun visible(): Map<String, FlagRecord> = defaults + records
    fun record(key: String): FlagRecord? = records[key] ?: defaults[key]
}
internal class IssuedBaseline(override val records: Map<String, FlagRecord>, override val cursor: Long) : Baseline

internal interface AnonymousRepository {
    fun prepare(reset: Boolean, completion: (Outcome<AnonymousIdentity>) -> Unit)
    /** Metadata-only check; never invokes disk or client code. */
    fun withCurrent(identity: AnonymousIdentity, adopt: () -> Unit): Boolean
}
internal data class AnonymousIdentity(val key: String, val revision: Long)
internal interface CloseAware { fun onClosed(action: () -> Unit): Registration }

internal class LocalClient(
    private val options: ClientOptions,
    initialUser: User,
    private val capabilities: SourceCapabilities?,
    private val dispatch: Dispatch,
    private val clock: Clock,
    private val workers: Workers,
    private val ticker: Ticker,
    private val diagnostics: Diagnostics,
    private val anonymous: AnonymousRepository? = null,
    private val enrich: (User) -> Outcome<User> = { Outcome.success(it) },
    private val releaseBinding: () -> Unit = {},
    private val cache: DiskCoordinator? = null,
    transportFactory: () -> SyncTransport = { OkHttpSyncTransport() },
    random: () -> Double = { kotlin.random.Random.nextDouble() },
    eventTransportFactory: () -> SyncTransport = { OkHttpSyncTransport(readResponseBody = false) },
    initialPlatformState: PlatformState = PlatformState(foreground = true),
    private val releasePlatform: () -> Unit = {},
) : FeatBitClient, CloseAware {
    private val gate = Any()
    private val callbacks = CallbackBudget()
    private val closeCallbacks = CallbackBudget(32)
    private val subscriptionBudget = CallbackBudget()
    private val closeOperation = ResultOperation<CloseResult>(dispatch, closeCallbacks, clock)
    private val events = Events(gate, options, clock, workers, diagnostics, eventTransportFactory, random)
    private var user = initialUser
    private var cacheKey = contextKey(initialUser)
    private var loadId = 0L
    private var localPending = false
    private var generation = 0L
    private var identityId = 0L
    private var modeId = 0L
    private var offline = options.offline
    private var foreground = initialPlatformState.foreground
    private var network = initialPlatformState.network
    private var executionAllowed = initialPlatformState.executionAllowed
    private var closed = false
    private var terminal = false
    private var successAt: Long? = null
    private var failureAt: Long? = null
    private var failure: Diagnostic? = null
    private var active: Session? = null
    private val sessions = LinkedHashSet<Session>()
    private val waits = LinkedHashSet<Wait<*>>()
    private val subscriptions = LinkedHashSet<Subscription>()
    private val closeListeners = LinkedHashSet<CloseListener>()
    private var closeDeadline = Long.MAX_VALUE
    private var cleanupFailed = false
    private var cleanupSettled = false
    private val bootstrapRecords = frozen(BootstrapRecords.create(options.bootstrap ?: emptyList())!!.associateBy { it.key })
    @Volatile private var view = initialView()
    private val online = if (options.source != null) null else OnlineSync(gate, options, clock, workers, transportFactory,
        { SourceSessionContext(user, view.baseline) },
        { update, valid, committed -> commitUpdate(update, true, valid, committed).getResult()?.value?.code == SourceUpdateCode.COMMITTED },
        ::sourceFailure, ::publishStatus, diagnostics::report, random)
    @Volatile private var information = status()

    private fun initialView(): View {
        return View(emptyMap(), bootstrapRecords, options.bootstrap != null || offline)
    }
    fun start() { ticker.start(::tick); synchronized(gate) {
        events.permissions(offline, foreground, network, executionAllowed)
        if (options.bootstrap == null) loadCache()
        reconcile()
    } }
    private fun loadCache() {
        val repository = cache ?: return
        val id = ++loadId
        val expected = repository.epoch()
        localPending = true
        if (!repository.load(cacheKey, expected, diagnostics::report) { snapshot ->
            synchronized(gate) {
                if (closed || id != loadId || !localPending) return@synchronized
                repository.withEpoch(expected) {
                    val before = view
                    view = if (snapshot == null) initialView() else View(snapshot.records, emptyMap(), true,
                        baseline = IssuedBaseline(snapshot.records, snapshot.cursor))
                    localPending = false
                    changed(before, view); publishStatus()
                }
            }
        }) {
            localPending = false
            view = initialView()
            diagnostics.report("cache_load_capacity")
        }
    }
    private fun permitted(): Boolean = !closed && !offline && !terminal && foreground && executionAllowed && (network || capabilities?.networkDependent == false)
    private fun status(): ConnectionInformation {
        val pauses = linkedSetOf<PauseReason>()
        if (offline) pauses.add(PauseReason.OFFLINE)
        if (!foreground) pauses.add(PauseReason.BACKGROUND)
        if (!network && capabilities?.networkDependent != false) pauses.add(PauseReason.NETWORK_UNAVAILABLE)
        if (closed) pauses.add(PauseReason.CLOSING)
        return ConnectionInformation(options.mode, online?.effective ?: options.mode, when {
            closed -> SyncStatus.CLOSED
            terminal -> SyncStatus.TERMINAL
            failure != null -> SyncStatus.STALE
            view.confirmed || view.localReady || offline -> SyncStatus.READY
            else -> SyncStatus.WAITING
        }, pauses, view.available, view.confirmed, successAt, failureAt, failure,
            online?.recovery ?: RecoveryStatus.NONE, online?.candidateFailure)
    }
    private fun publishStatus() {
        information = status()
        subscriptions.filter { it.statusListener != null }.forEach { it.enqueueStatus(information) }
    }
    private fun <T> operation(): ResultOperation<T> = ResultOperation(dispatch, callbacks, clock)
    private fun <T> done(outcome: Outcome<T>): Operation<T> = operation<T>().apply { settle(outcome) }
    private fun <T> unavailable(code: String): Operation<T> = done(Outcome.failure(OutcomeCode.DISABLED, Diagnostic(code)))
    override fun getVersion(): String = SdkInfo.getVersion()
    override fun getConnectionInformation(): ConnectionInformation = information
    override fun isOffline(): Boolean = synchronized(gate) { offline }

    private inner class Wait<T>(val operation: ResultOperation<T>, val deadline: Long,
        val kind: String, val generation: Long, val mode: Long, val success: () -> T) {
        fun finish(code: OutcomeCode, diagnostic: String? = null) {
            val finalCode = if (code == OutcomeCode.SUCCESS && clock.elapsed() >= deadline) OutcomeCode.TIMED_OUT else code
            operation.settle(if (finalCode == OutcomeCode.SUCCESS) Outcome.success(success() as Any).let {
                @Suppress("UNCHECKED_CAST") (it as Outcome<T>)
            } else Outcome.failure(finalCode, diagnostic?.let(::Diagnostic)))
            waits.remove(this)
        }
    }
    private fun validTimeout(value: Long): Boolean = value > 0
    private fun readiness(): ReadyResult? = when {
        offline -> ReadyResult.OFFLINE_LOCAL
        view.confirmed -> ReadyResult.REMOTE_CONFIRMED
        view.localReady -> ReadyResult.CUSTOM_LOCAL
        else -> null
    }
    private fun readyWait(timeout: Long): Operation<ReadyResult> {
        if (closed) return done(Outcome.failure(OutcomeCode.CLOSED, null))
        if (offline) return done(Outcome.success(ReadyResult.OFFLINE_LOCAL))
        if (terminal) return done(Outcome.failure(OutcomeCode.TERMINAL_FAILURE, failure))
        readiness()?.let { return done(Outcome.success(it)) }
        if (waits.size + events.waiting >= 256) return done(Outcome.failure(OutcomeCode.CAPACITY_EXCEEDED, Diagnostic("operation_capacity")))
        val op = operation<ReadyResult>()
        waits.add(Wait(op, clock.deadlineAfter(timeout), "ready", generation, modeId) { readiness()!! })
        return op
    }
    override fun awaitReady(timeoutMillis: Long): Operation<ReadyResult> = synchronized(gate) {
        if (!validTimeout(timeoutMillis)) done(Outcome.invalid("invalid_timeout")) else readyWait(timeoutMillis)
    }
    override fun identify(user: User, timeoutMillis: Long): Operation<ReadyResult> {
        if (!validTimeout(timeoutMillis)) return done(Outcome.invalid("invalid_timeout"))
        val prepared = enrich(user)
        if (!prepared.isSuccess) return done(Outcome.failure(prepared.code, prepared.diagnostic))
        val key = contextKey(prepared.value!!)
        return synchronized(gate) {
            if (closed) return@synchronized done(Outcome.failure(OutcomeCode.CLOSED, null))
            if (waits.size + events.waiting >= 256) return@synchronized done(Outcome.failure(OutcomeCode.CAPACITY_EXCEEDED, Diagnostic("operation_capacity")))
            identityId++
            waits.filter { it.kind == "identity" }.toList().forEach { it.finish(OutcomeCode.SUPERSEDED) }
            adopt(prepared.value, key)
            readyWait(timeoutMillis)
        }
    }
    private fun adopt(next: User, key: String) {
        val before = view
        generation++
        user = next
        cacheKey = key
        loadId++; localPending = false
        invalidate()
        waits.filter { it.kind == "ready" }.toList().forEach { it.finish(OutcomeCode.SUPERSEDED) }
        successAt = null; failureAt = null; failure = null
        view = if (cache == null) initialView() else View(emptyMap(), emptyMap(), offline)
        loadCache()
        changed(before, view)
        publishStatus()
        reconcile()
    }
    override fun identifyAnonymous(timeoutMillis: Long): Operation<ReadyResult> = anonymous(false, timeoutMillis)
    override fun resetAnonymousIdentity(timeoutMillis: Long): Operation<ReadyResult> = anonymous(true, timeoutMillis)
    private fun anonymous(reset: Boolean, timeout: Long): Operation<ReadyResult> {
        if (!validTimeout(timeout)) return done(Outcome.invalid("invalid_timeout"))
        synchronized(gate) { if (closed) return done(Outcome.failure(OutcomeCode.CLOSED, null)) }
        if (!options.anonymousEnabled) return unavailable("anonymous_disabled")
        val repository = anonymous ?: return unavailable("anonymous_persistence_unavailable")
        val op = operation<ReadyResult>()
        val id: Long
        val wait: Wait<ReadyResult>
        synchronized(gate) {
            if (closed) return done(Outcome.failure(OutcomeCode.CLOSED, null))
            if (waits.size + events.waiting >= 256) return done(Outcome.failure(OutcomeCode.CAPACITY_EXCEEDED, Diagnostic("operation_capacity")))
            id = ++identityId
            waits.filter { it.kind == "identity" }.toList().forEach { it.finish(OutcomeCode.SUPERSEDED) }
            wait = Wait(op, clock.deadlineAfter(timeout), "identity", generation, modeId) { readiness()!! }
            waits.add(wait)
        }
        if (!workers.execute {
            try {
                repository.prepare(reset) { result ->
                    if (!result.isSuccess) diagnostics.report("anonymous_storage_failed")
                    val prepared = result.value?.let { enrich(User.builder(it.key).name("Anonymous").build().value!!) }
                    val key = prepared?.value?.let(::contextKey)
                    synchronized(gate) {
                        if (id != identityId || closed || wait !in waits) return@synchronized
                        if (clock.elapsed() >= wait.deadline) { wait.finish(OutcomeCode.TIMED_OUT); return@synchronized }
                        if (!result.isSuccess || prepared?.isSuccess != true) {
                            op.settle(Outcome.failure(prepared?.code ?: result.code, Diagnostic("identity_preparation_failed")))
                            waits.remove(wait)
                        } else if (!repository.withCurrent(result.value) {
                            waits.remove(wait)
                            adopt(prepared.value!!, key!!)
                            val ready = readiness()
                            if (terminal && !offline) op.settle(Outcome.failure(OutcomeCode.TERMINAL_FAILURE, failure))
                            else if (ready != null) op.settle(Outcome.success(ready))
                            else waits.add(Wait(op, wait.deadline, "ready", generation, modeId) { readiness()!! })
                        }) wait.finish(OutcomeCode.SUPERSEDED)
                    }
                }
            } catch (_: Exception) { synchronized(gate) { wait.finish(OutcomeCode.STORAGE_FAILED, "identity_preparation_failed") } }
        }) synchronized(gate) { wait.finish(OutcomeCode.CAPACITY_EXCEEDED, "extension_capacity") }
        return op
    }

    override fun setOffline(timeoutMillis: Long): Operation<ModeResult> = mode(true, timeoutMillis)
    override fun setOnline(timeoutMillis: Long): Operation<ModeResult> = mode(false, timeoutMillis)
    private fun mode(nextOffline: Boolean, timeout: Long): Operation<ModeResult> {
        if (!validTimeout(timeout)) return done(Outcome.invalid("invalid_timeout"))
        val error = if (!nextOffline) onlineError(options) else null
        return synchronized(gate) {
            if (closed) return@synchronized done(Outcome.failure(OutcomeCode.CLOSED, null))
            error?.let { return@synchronized done(Outcome.failure(it.first, Diagnostic(it.second))) }
            if (waits.size + events.waiting >= 256) return@synchronized done(Outcome.failure(OutcomeCode.CAPACITY_EXCEEDED, Diagnostic("operation_capacity")))
            if (offline != nextOffline) {
                modeId++
                waits.filter { it.kind == "mode" }.toList().forEach { it.finish(OutcomeCode.SUPERSEDED) }
                offline = nextOffline
                events.permissions(offline, foreground, network, executionAllowed)
                invalidate()
                if (offline) waits.filter { it.kind == "ready" }.toList().forEach { it.finish(OutcomeCode.DEFERRED, "offline_transition") }
                else view = view.copy(confirmed = false, localReady = false)
                publishStatus()
                reconcile()
            }
            val result = if (offline) ModeResult.OFFLINE else ModeResult.ONLINE
            if (!offline || sessions.isEmpty() && online?.idle != false) done(Outcome.success(result))
            else {
                val op = operation<ModeResult>()
                waits.add(Wait(op, clock.deadlineAfter(minOf(timeout, 2_000)), "mode", generation, modeId) { result })
                op
            }
        }
    }
    /** Initial state and subsequent platform signals share the same authority gate. */
    fun lifecycle(isForeground: Boolean, networkAvailable: Boolean, canExecute: Boolean = true) = synchronized(gate) {
        if (closed) return@synchronized
        expireWaits()
        foreground = isForeground; network = networkAvailable; executionAllowed = canExecute
        events.permissions(offline, foreground, network, executionAllowed)
        events.tick(deliverNow = false)
        if (online != null) reconcile()
        else if (!permitted()) invalidate() else reconcile()
        publishStatus()
    }
    private inner class Session(val generation: Long) {
        var valid = true
        var busy = true
        var source: DataSource? = null
        var stopping = false
        val sink: SourceUpdateSink = object : SourceUpdateSink {
            override fun submit(update: SourceUpdate): Operation<SourceUpdateResult> = commit(this@Session, update)
            override fun report(status: SourceStatus): Outcome<StatusAccepted> = synchronized(gate) {
                if (!authorized(this@Session)) return@synchronized Outcome.failure(if (closed) OutcomeCode.CLOSED else OutcomeCode.SUPERSEDED, Diagnostic("inactive_source"))
                when (status.state) {
                    SourceState.CONNECTING -> Unit
                    SourceState.INTERRUPTED -> sourceFailure("custom_interrupted", false)
                    SourceState.TERMINAL -> sourceFailure("custom_terminal", true)
                }
                // Extension-supplied diagnostic strings are untrusted and intentionally not forwarded.
                Outcome.success(StatusAccepted.ACCEPTED)
            }
        }
    }
    private fun authorized(session: Session): Boolean = session.valid && active === session && session.generation == generation && permitted()
    private fun reconcile() {
        if (online != null) {
            online.permissions(!closed && !offline && !terminal && network && executionAllowed, foreground)
            online.tick()
            return
        }
        if (!permitted() || active != null || options.source == null) return
        if (sessions.size >= 66) { sourceFailure("extension_capacity", false); return }
        val session = Session(generation)
        val context = SourceSessionContext(user, view.baseline)
        active = session; sessions.add(session)
        if (!workers.execute {
            try {
                if (synchronized(gate) { authorized(session) }) {
                    val created = options.source.create(context, session.sink)
                    synchronized(gate) { session.source = created.value }
                    if (!created.isSuccess || created.value == null) synchronized(gate) {
                        if (authorized(session)) sourceFailure("custom_create_failed", false)
                    } else if (synchronized(gate) { authorized(session) }) {
                        created.value.start { result -> synchronized(gate) {
                            if (!result.isSuccess && authorized(session)) sourceFailure("custom_start_failed", false)
                        } }
                    }
                }
            } catch (_: Exception) { synchronized(gate) { if (authorized(session)) sourceFailure("custom_exception", false) } }
            finally {
                val stop = synchronized(gate) {
                    session.busy = false
                    if (session.source == null) { sessions.remove(session); if (active === session) active = null; false }
                    else if (!session.valid && !session.stopping) { session.stopping = true; true } else false
                }
                if (stop) stopSource(session)
                tick()
            }
        }) {
            session.busy = false; sessions.remove(session); active = null
            sourceFailure("extension_capacity", false)
        }
    }
    private fun invalidate() {
        online?.invalidate()
        val session = active ?: return
        active = null; session.valid = false
        if (!session.busy && !session.stopping) {
            session.stopping = true
            if (!workers.execute { stopSource(session) }) { cleanupFailed = true; sessions.remove(session) }
        }
    }
    private fun stopSource(session: Session) {
        try {
            val source = session.source
            if (source == null) synchronized(gate) { sessions.remove(session) }
            else source.stop { result -> synchronized(gate) {
                if (session in sessions) { if (!result.isSuccess) cleanupFailed = true; sessions.remove(session) }
            }; tick() }
        } catch (_: Exception) { synchronized(gate) { cleanupFailed = true; sessions.remove(session) }; tick() }
    }
    private fun sourceFailure(code: String, fatal: Boolean) {
        failure = Diagnostic(code); failureAt = clock.wall()
        if (fatal) {
            terminal = true; invalidate()
            waits.filter { it.kind == "ready" }.toList().forEach { it.finish(OutcomeCode.TERMINAL_FAILURE, code) }
        }
        publishStatus(); diagnostics.report(code)
    }

    private fun commit(session: Session, update: SourceUpdate): Operation<SourceUpdateResult> =
        commitUpdate(update, capabilities?.provenance == Provenance.REMOTE, { authorized(session) }) {}
    private fun commitUpdate(update: SourceUpdate, remote: Boolean, authorized: () -> Boolean,
        committed: (Baseline?) -> Unit): Operation<SourceUpdateResult> {
        val records = when (update) { is FullUpdate -> update.records; is PatchUpdate -> update.records; is NoChange -> emptyList() }
        // Preparation may enumerate large snapshots; commits retry if another view won meanwhile.
        while (true) {
            val before = view
            var accepted = 0; var skipped = 0
            val newRecords = if (update is FullUpdate) LinkedHashMap() else LinkedHashMap(before.records)
            val defaults = LinkedHashMap(before.defaults)
            if (update !is NoChange) for (record in records) {
                if (update is PatchUpdate && (newRecords[record.key]?.timestamp ?: -1) > record.timestamp) { skipped++; continue }
                newRecords[record.key] = record
                defaults.remove(record.key)
                accepted++
            }
            val cursor = if (!remote) 0 else maxOf(
                if (update is FullUpdate) 0 else before.baseline?.cursor ?: 0,
                newRecords.values.maxOfOrNull { it.timestamp } ?: 0)
            val frozenRecords = frozen(newRecords)
            val baseline = if (remote) IssuedBaseline(frozenRecords, cursor) else null
            val after = if (update is NoChange) before.copy(confirmed = true)
                else View(frozenRecords, frozen(defaults), true, remote, !remote, baseline)
            val changes = changeKeys(before, after)
            synchronized(gate) {
                if (!authorized()) return sourceResult(if (closed) SourceUpdateCode.CLOSED else SourceUpdateCode.INACTIVE)
                if (update is NoChange && (!remote || update.baseline !== before.baseline))
                    return sourceResult(SourceUpdateCode.INVALID, error = "invalid_baseline")
                if (view !== before) return@synchronized
                loadId++; localPending = false
                if (remote && after.baseline != null && cache != null &&
                    !cache.commit(cacheKey, CachedSnapshot(after.records, after.baseline.cursor), diagnostics::report))
                    diagnostics.report("cache_write_capacity")
                view = after
                committed(after.baseline)
                if (remote) successAt = clock.wall()
                failure = null
                notifyChanges(changes)
                publishStatus()
                waits.filter { it.kind == "ready" }.toList().forEach { it.finish(OutcomeCode.SUCCESS) }
                return sourceResult(SourceUpdateCode.COMMITTED, accepted, skipped, after.baseline)
            }
        }
    }
    private fun sourceResult(code: SourceUpdateCode, accepted: Int = 0, skipped: Int = 0, baseline: Baseline? = null, error: String? = null): Operation<SourceUpdateResult> =
        done(Outcome.success(SourceUpdateResult(code, accepted, skipped, baseline, error?.let(::Diagnostic))))

    private fun <T> evaluate(key: String, fallback: T, convert: (FlagRecord) -> T?): EvaluationDetail<T> {
        if (key.isEmpty()) return EvaluationDetail(key, fallback, EvaluationReason.ERROR)
        while (true) {
            val snapshot = view
            val record = snapshot.record(key)
            if (record == null) return EvaluationDetail(key, fallback, if (snapshot.available) EvaluationReason.FLAG_NOT_FOUND else EvaluationReason.CLIENT_NOT_READY)
            if (record.archived) return EvaluationDetail(key, fallback, EvaluationReason.FLAG_NOT_FOUND)
            // JSON conversion can be large. Never hold the state gate while parsing it.
            val converted = convert(record) ?: return EvaluationDetail(key, fallback, EvaluationReason.WRONG_TYPE)
            val accepted = synchronized(gate) {
                if (view !== snapshot) false else {
                    if (snapshot.confirmed && snapshot.records[key] === record && !closed) events.evaluation(user, record)
                    true
                }
            }
            if (accepted) return EvaluationDetail(key, converted, EvaluationReason.MATCH, record.reason)
        }
    }
    override fun boolVariation(key: String, fallback: Boolean): Boolean = boolVariationDetail(key, fallback).value
    override fun boolVariationDetail(key: String, fallback: Boolean): EvaluationDetail<Boolean> = evaluate(key, fallback) { Conversion.boolean(it.variation) }
    override fun numberVariation(key: String, fallback: Double): Double = numberVariationDetail(key, fallback).value
    override fun numberVariationDetail(key: String, fallback: Double): EvaluationDetail<Double> = evaluate(key, fallback) { Conversion.number(it.variation) }
    override fun stringVariation(key: String, fallback: String): String = stringVariationDetail(key, fallback).value
    override fun stringVariationDetail(key: String, fallback: String): EvaluationDetail<String> = evaluate(key, fallback) { it.variation }
    override fun variation(key: String, fallback: FbValue): FbValue = variationDetail(key, fallback).value
    override fun variationDetail(key: String, fallback: FbValue): EvaluationDetail<FbValue> = evaluate(key, fallback, Conversion::generic)
    override fun jsonVariation(key: String, fallback: FbValue): FbValue = jsonVariationDetail(key, fallback).value
    override fun jsonVariationDetail(key: String, fallback: FbValue): EvaluationDetail<FbValue> = evaluate(key, fallback) { Conversion.json(it.variation) }
    override fun jsonTextVariation(key: String, fallback: String): String = jsonTextVariationDetail(key, fallback).value
    override fun jsonTextVariationDetail(key: String, fallback: String): EvaluationDetail<String> = evaluate(key, fallback) { it.variation }
    private fun all(snapshot: View): Map<String, EvaluationDetail<String>> = frozen(snapshot.visible().filterValues { !it.archived }
        .mapValues { (key, r) -> EvaluationDetail(key, r.variation, EvaluationReason.MATCH, r.reason) })
    override fun allVariations(): Map<String, EvaluationDetail<String>> = all(view)

    override fun track(name: String): Outcome<TrackResult> = track(name, 1.0)
    override fun track(name: String, numericValue: Double): Outcome<TrackResult> = synchronized(gate) {
        when {
            name.isEmpty() || !numericValue.isFinite() -> Outcome.invalid("invalid_track")
            closed -> Outcome.failure(OutcomeCode.CLOSED, null)
            else -> events.track(user, name, numericValue)
        }
    }
    override fun flush(): Operation<FlushResult> = synchronized(gate) {
        if (closed) done(Outcome.failure(OutcomeCode.CLOSED, null))
        else operation<FlushResult>().also { events.flush(it, waits.size + events.waiting < 256) }
    }
    override fun clearCache(scope: CacheScope, timeoutMillis: Long): Operation<CacheClearResult> = synchronized(gate) {
        when {
            !validTimeout(timeoutMillis) -> done(Outcome.invalid("invalid_timeout"))
            closed -> done(Outcome.failure(OutcomeCode.CLOSED, null))
            cache == null -> unavailable("cache_unavailable")
            waits.size + events.waiting >= 256 -> done(Outcome.failure(OutcomeCode.CAPACITY_EXCEEDED, Diagnostic("operation_capacity")))
            else -> {
                val op = operation<CacheClearResult>()
                val wait = Wait(op, clock.deadlineAfter(timeoutMillis), "cache", generation, modeId) { CacheClearResult.CLEARED }
                waits.add(wait)
                if (!cache.clear(if (scope == CacheScope.CURRENT_CONTEXT) cacheKey else null, diagnostics::report) { ok ->
                    synchronized(gate) {
                        if (wait in waits) wait.finish(if (ok) OutcomeCode.SUCCESS else OutcomeCode.STORAGE_FAILED, if (ok) null else "cache_clear_failed")
                    }
                }) wait.finish(OutcomeCode.CAPACITY_EXCEEDED, "storage_capacity")
                op
            }
        }
    }

    private inner class Subscription(val key: String?, val listener: ChangeListener?, val statusListener: StatusListener?) : Registration {
        var valid = true
        var queued = false
        var allChanged = false
        val keys = LinkedHashSet<String>()
        var latest: ConnectionInformation? = null
        var released = false
        fun release() { if (!released) { released = true; subscriptionBudget.release() } }
        fun enqueueChanges(changed: Set<String>) {
            if (listener == null || !valid) return
            if (key != null) { if (key !in changed) return; keys.add(key) }
            else if (!allChanged) {
                if (keys.size + changed.size > 1024) { keys.clear(); allChanged = true } else keys.addAll(changed)
            }
            enqueue()
        }
        fun enqueueStatus(status: ConnectionInformation) { if (valid) { latest = status; enqueue() } }
        private fun enqueue() {
            if (queued) return
            queued = true
            dispatch.post {
                val value = synchronized(gate) {
                    queued = false
                    if (!valid || closed) { release(); return@post }
                    val change = FlagChange(keys, allChanged)
                    keys.clear(); allChanged = false
                    change to latest
                }
                try { listener?.onChange(value.first); value.second?.let { statusListener?.onStatus(it) } }
                catch (_: Exception) { diagnostics.report("listener_exception") }
            }
        }
        override fun close() = synchronized(gate) {
            valid = false; keys.clear(); latest = null; subscriptions.remove(this)
            if (!queued) release()
        }
    }
    private fun changed(before: View, after: View) {
        notifyChanges(changeKeys(before, after))
    }
    private fun changeKeys(before: View, after: View): Set<String> {
        val a = before.visible(); val b = after.visible()
        return (a.keys + b.keys).filterTo(LinkedHashSet()) { key ->
            val x = a[key]?.takeUnless { it.archived }; val y = b[key]?.takeUnless { it.archived }
            x?.variation != y?.variation || x?.variationType != y?.variationType
        }
    }
    private fun notifyChanges(keys: Set<String>) {
        if (keys.isNotEmpty()) subscriptions.forEach { it.enqueueChanges(keys) }
    }
    override fun subscribeChanges(listener: ChangeListener): Outcome<ChangeSubscription> = subscribe(null, listener)
    override fun subscribeFlag(key: String, listener: ChangeListener): Outcome<ChangeSubscription> =
        if (key.isEmpty()) Outcome.invalid("invalid_flag_key") else subscribe(key, listener)
    private fun subscribe(key: String?, listener: ChangeListener): Outcome<ChangeSubscription> {
        val captured = synchronized(gate) {
            if (closed) return Outcome.failure(OutcomeCode.CLOSED, null)
            if (subscriptions.size + closeListeners.size >= 256 || !subscriptionBudget.acquire())
                return Outcome.failure(OutcomeCode.CAPACITY_EXCEEDED, Diagnostic("subscription_capacity"))
            val sub = Subscription(key, listener, null)
            subscriptions.add(sub)
            sub to view
        }
        val initial = all(captured.second).let { if (key == null) it else it.filterKeys { k -> k == key } }
        return Outcome.success(ChangeSubscription(captured.first, initial))
    }
    override fun subscribeStatus(listener: StatusListener): Outcome<Registration> = synchronized(gate) {
        if (closed) return@synchronized Outcome.failure(OutcomeCode.CLOSED, null)
        if (subscriptions.size + closeListeners.size >= 256) return@synchronized Outcome.failure(OutcomeCode.CAPACITY_EXCEEDED, Diagnostic("subscription_capacity"))
        if (!subscriptionBudget.acquire()) return@synchronized Outcome.failure(OutcomeCode.CAPACITY_EXCEEDED, Diagnostic("subscription_capacity"))
        val sub = Subscription(null, null, listener)
        subscriptions.add(sub); sub.enqueueStatus(information)
        Outcome.success(sub)
    }
    private inner class CloseListener(val action: () -> Unit) : Registration {
        var valid = true
        override fun close() = synchronized(gate) { valid = false; closeListeners.remove(this); Unit }
        fun deliver() { dispatch.post { val run = synchronized(gate) { val run = valid; valid = false; run }; if (run) action() } }
    }
    override fun onClosed(action: () -> Unit): Registration = synchronized(gate) {
        val listener = CloseListener(action)
        if (closed) listener.deliver()
        else {
            check(subscriptions.size + closeListeners.size < 256) { "subscription_capacity" }
            closeListeners.add(listener)
        }
        listener
    }
    override fun close(): Operation<CloseResult> {
        synchronized(gate) {
            if (closed) return closeOperation
            closed = true; identityId++; modeId++
            closeDeadline = clock.deadlineAfter(options.closeTimeoutMillis)
            events.beginClose(closeDeadline)
            invalidate()
            online?.close()
            waits.toList().forEach { it.finish(OutcomeCode.CLOSED) }
            subscriptions.toList().forEach { it.close() }
            closeListeners.forEach { it.deliver() }; closeListeners.clear()
            information = status()
        }
        releasePlatform()
        tick()
        return closeOperation
    }
    private fun expireWaits() {
        val now = clock.elapsed()
        waits.toList().filter { now >= it.deadline }.forEach {
            it.finish(if (it.kind == "mode") OutcomeCode.CLEANUP_FAILED else OutcomeCode.TIMED_OUT, "operation_deadline")
        }
    }
    private fun tick() {
        var result: CloseResult? = null
        synchronized(gate) {
            expireWaits()
            val now = clock.elapsed()
            events.tick()
            if (!closed && online != null) reconcile()
            waits.toList().forEach {
                if (it.kind == "mode" && sessions.isEmpty() && online?.idle != false) it.finish(if (cleanupFailed) OutcomeCode.CLEANUP_FAILED else OutcomeCode.SUCCESS)
                else if (now >= it.deadline) it.finish(if (it.kind == "mode") OutcomeCode.CLEANUP_FAILED else OutcomeCode.TIMED_OUT, "operation_deadline")
            }
            if (closed && !cleanupSettled && (sessions.isEmpty() && online?.closedCleanly != false && events.pending == 0 && events.idle || now >= closeDeadline)) {
                cleanupSettled = true
                val eventIdle = events.idle
                result = CloseResult(events.shutdown(), sessions.isEmpty() && !cleanupFailed && online?.closedCleanly != false && eventIdle)
            }
        }
        result?.let {
            releaseBinding(); ticker.close(); workers.close(); diagnostics.close()
            closeOperation.settle(Outcome.success(it))
        }
    }
}

internal fun onlineError(options: ClientOptions): Pair<OutcomeCode, String>? {
    fun endpoint(raw: String?, schemes: Set<String>): Boolean = try {
        val uri = URI(raw ?: "")
        uri.scheme in schemes && !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.fragment == null && uri.query == null
    } catch (_: Exception) { false }
    if ((options.source == null || !options.disableEvents) && options.sdkKey.isNullOrEmpty()) return OutcomeCode.INVALID to "sdk_key_required"
    if (options.source == null) {
        if (options.mode == SyncMode.STREAMING && !endpoint(options.streamingUrl, setOf("ws", "wss"))) return OutcomeCode.INVALID to "invalid_streaming_endpoint"
        if ((options.mode == SyncMode.POLLING || options.backgroundPolling || options.pollingFallback) && !endpoint(options.pollingUrl, setOf("http", "https"))) return OutcomeCode.INVALID to "invalid_polling_endpoint"
    }
    if (!options.disableEvents && !endpoint(options.eventsUrl, setOf("http", "https"))) return OutcomeCode.INVALID to "invalid_events_endpoint"
    if (options.source == null && options.sdkKey?.trimEnd('=')?.length !in 2..999) return OutcomeCode.INVALID to "invalid_sdk_key"
    return null
}
