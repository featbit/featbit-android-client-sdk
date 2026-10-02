package co.featbit.android.internal

import co.featbit.android.api.*
import co.featbit.android.datasource.*
import kotlin.random.Random

/** Metadata is protected by the owning client's gate. Effects and decoding run outside it. */
internal class OnlineSync(
    private val gate: Any, private val options: ClientOptions, private val clock: Clock,
    private val workers: Workers, private val transportFactory: () -> SyncTransport,
    private val context: () -> SourceSessionContext,
    private val commit: (SourceUpdate, () -> Boolean, (Baseline?) -> Unit) -> Boolean,
    private val failure: (String, Boolean) -> Unit, private val changed: () -> Unit,
    private val diagnostic: (String) -> Unit, private val random: () -> Double = { Random.nextDouble() },
) {
    var effective: SyncMode = options.mode; private set
    var recovery: RecoveryStatus = RecoveryStatus.NONE; private set
    var candidateFailure: Diagnostic? = null; private set
    private var allowed = false
    private var foreground = true
    private var background = false
    private var backgroundAt: Long? = null
    private var stopped = false
    private var terminated = false
    private var fallback = false
    private var nextAt = 0L
    private var failures = 0
    private var failureSince: Long? = null
    private var recoveryAt = Long.MAX_VALUE
    private var recoveryFailures = 0
    private var stableSince: Long? = null
    private var resumeFirst = false
    private var forceFull = false
    private var active: Attempt? = null
    private var transport: SyncTransport? = null
    private var cleanupDone = false
    private var pendingDispatch = 0
    private var pendingCancellation = 0
    private var cleanupFailed = false
    val idle: Boolean get() = pendingDispatch == 0 && pendingCancellation == 0 && !cleanupFailed
    val closedCleanly: Boolean get() = cleanupDone && idle
    private val transportLock = Any()

    private inner class Attempt(val streaming: Boolean, var candidate: Boolean, val context: SourceSessionContext) {
        var baseline = context.baseline
        var valid = true
        var dispatched = false
        var opened = false
        var syncSent = false
        var synchronized = false
        var handle: SyncHandle? = null
        var deadline = clock.deadlineAfter(if (candidate) 15_000 else options.requestTimeoutMillis)
        var activityAt = clock.elapsed()
        var pingAt = clock.deadlineAfter(18_000)
    }
    private fun eligible(a: Attempt): Boolean = !stopped && allowed && active === a && a.valid &&
        (foreground || options.backgroundPolling ||
            a.dispatched && !a.candidate && clock.elapsed() < (backgroundAt ?: 0L))
    private fun eligibleBeforeDeadline(a: Attempt): Boolean = eligible(a) &&
        ((a.streaming && a.synchronized && !a.candidate) || clock.elapsed() < a.deadline) &&
        (!a.streaming || !a.opened || clock.elapsed() - a.activityAt < 36_000)
    private fun effect(action: () -> Unit): Boolean = workers.execute(action)
    private fun revoke() {
        val old = active ?: return
        active = null; old.valid = false
        old.handle?.let { handle ->
            pendingCancellation++
            if (!effect { try { handle.cancel() } finally { synchronized(gate) { pendingCancellation-- } } }) {
                pendingCancellation--; cleanupFailed = true
            }
        }
    }
    /** Called for Identify, offline and lifecycle boundaries. Never reset retained fallback/backoff. */
    fun invalidate() {
        if (active?.candidate == true) coolDown()
        revoke(); nextAt = clock.elapsed(); failureSince = null; stableSince = null
    }
    fun permissions(enabled: Boolean, isForeground: Boolean) {
        val previousMode = effective
        val previousRecovery = recovery
        // A foreground signal can beat the first ticker after sleep. Expire the old grace
        // before clearing its deadline, otherwise that signal would revive an expired stream.
        if (!foreground && !options.backgroundPolling && active != null &&
            clock.elapsed() >= (backgroundAt ?: Long.MAX_VALUE)) invalidate()
        if (foreground != isForeground) {
            foreground = isForeground
            if (!foreground) {
                failureSince = null; stableSince = null
                if (active?.candidate == true) { coolDown(); revoke() }
                else if (active?.dispatched == false) revoke()
            } else {
                if (fallback) resumeFirst = true
                if (active == null) nextAt = clock.elapsed()
            }
            backgroundAt = if (foreground) null else clock.deadlineAfter(maxOf(1, options.flagGraceMillis))
            if (options.flagGraceMillis == 0L && !foreground) backgroundAt = clock.elapsed()
        }
        val bg = !foreground && (options.backgroundPolling || clock.elapsed() >= (backgroundAt ?: Long.MAX_VALUE))
        val permitted = enabled && !terminated && (!bg || options.backgroundPolling)
        if (allowed != permitted || background != bg) invalidate()
        allowed = permitted; background = bg
        effective = if (bg && options.backgroundPolling || fallback) SyncMode.POLLING else options.mode
        recovery = if (fallback && !terminated && !stopped) RecoveryStatus.COOLING_DOWN else RecoveryStatus.NONE
        if (active?.candidate == true) recovery = RecoveryStatus.PROBING
        if (effective != previousMode || recovery != previousRecovery) changed()
    }
    fun close() {
        stopped = true; allowed = false; invalidate()
        recovery = RecoveryStatus.NONE
        if (!effect {
            synchronized(transportLock) { transport?.close(); transport = null }
            synchronized(gate) { cleanupDone = true }
        }) cleanupFailed = true
    }
    fun tick() {
        if (!allowed || stopped) return
        val now = clock.elapsed()
        if (stableSince?.let { now - it >= 60_000 } == true) { recoveryFailures = 0; stableSince = null }
        val a = active
        if (a != null) {
            if ((!a.synchronized || a.candidate || !a.streaming) && now >= a.deadline) { failed(a, "sync_timeout", false); return }
            if (a.streaming && a.opened && now - a.activityAt >= 36_000) { failed(a, "stream_inactive", false); return }
            if (a.streaming && a.opened && now >= a.pingAt) {
                a.pingAt = clock.deadlineAfter(18_000)
                send(a, SyncProtocol.PING)
            }
        }
        if (!fallback && options.pollingFallback && foreground && failureSince?.let { now - it >= 30_000 } == true) {
            revoke(); fallback = true; effective = SyncMode.POLLING; coolDown(); nextAt = now; changed()
        }
        if (fallback && foreground && !resumeFirst && active?.candidate != true && now >= recoveryAt) {
            revoke(); begin(true, true); return
        }
        if (active == null && now >= nextAt && (foreground || background && options.backgroundPolling)) begin(effective == SyncMode.STREAMING, false)
    }
    private fun begin(streaming: Boolean, candidate: Boolean) {
        val current = context()
        val a = Attempt(streaming, candidate, if (forceFull && !candidate) SourceSessionContext(current.user, null) else current)
        active = a
        if (candidate) { recovery = RecoveryStatus.PROBING; candidateFailure = null; changed() }
        pendingDispatch++
        if (!effect {
            try {
                val request = SyncProtocol.request(options, streaming, a.baseline?.cursor ?: 0, a.context.user, clock.wall(), random())
                val io = synchronized(transportLock) {
                    if (synchronized(gate) { stopped }) null else transport ?: transportFactory().also { transport = it }
                } ?: return@effect
                // Dispatch arbitration: invalidation after this point cancels late native attachment.
                val granted = synchronized(gate) { if (eligibleBeforeDeadline(a)) { a.dispatched = true; true } else false }
                if (!granted) return@effect
                val handle = io.start(request, streaming, listener(a))
                val cancel = synchronized(gate) { a.handle = handle; !eligible(a) }
                if (cancel) handle.cancel()
                else synchronized(gate) { initialSync(a) }
            } catch (_: Exception) { synchronized(gate) { failed(a, "sync_request_failed", false) } }
            finally { synchronized(gate) { pendingDispatch-- } }
        }) { pendingDispatch--; failed(a, "sync_capacity", false) }
    }
    private fun initialSync(a: Attempt) {
        if (a.opened && a.handle != null && !a.syncSent && eligibleBeforeDeadline(a)) {
            a.syncSent = true
            // Snapshot serialization is performed on the effect worker, never under the client gate.
            val cursor = if (a.candidate) 0 else a.context.baseline?.cursor ?: 0
            effect { send(a, SyncProtocol.sync(a.context.user, cursor)) }
        }
    }
    private fun send(a: Attempt, text: String) {
        // Queue the send outside the state gate; native WebSocket queue admission is fenced.
        effect {
            val handle = synchronized(gate) { if (eligibleBeforeDeadline(a)) a.handle else null }
            if (handle != null && !handle.send(text)) synchronized(gate) { failed(a, "stream_send_failed", false) }
        }
    }
    private fun listener(a: Attempt): SyncListener = object : SyncListener {
        override fun opened() {
            synchronized(gate) {
                if (!eligibleBeforeDeadline(a)) return
                a.opened = true; a.activityAt = clock.elapsed()
                initialSync(a)
            }
        }
        override fun message(text: String) {
            if (!synchronized(gate) { eligibleBeforeDeadline(a) }) return
            val decoded = SyncProtocol.decode(text, a.context.user.key)
            synchronized(gate) { if (eligibleBeforeDeadline(a)) a.activityAt = clock.elapsed() else return }
            when (decoded) {
                is WireMessage.Update -> {
                    if (decoded.skipped > 0) diagnostic("sync_records_skipped")
                    accept(a, decoded.update)
                }
                WireMessage.Invalid -> {
                    diagnostic("sync_invalid_message")
                    if (a.candidate || !a.streaming) synchronized(gate) {
                        if (eligible(a) && !a.streaming) forceFull = true
                        failed(a, "sync_invalid_message", false)
                    }
                }
                else -> if (!a.streaming) synchronized(gate) { failed(a, "sync_unrelated_response", false) }
            }
        }
        override fun response(status: Int, body: String, retryAfter: String?) {
            if (!synchronized(gate) { eligibleBeforeDeadline(a) }) return
            when (status) {
                200 -> message(body)
                304 -> {
                    val baseline = a.baseline
                    if (baseline != null) accept(a, NoChange(baseline))
                    else synchronized(gate) { if (eligible(a)) forceFull = true; failed(a, "sync_unmatched_304", false) }
                }
                else -> failed(status, retryAfter)
            }
        }
        override fun failed(status: Int?, retryAfter: String?) = synchronized(gate) {
            failed(a, if (status != null) "sync_http_$status" else "sync_connection_failed",
                status?.let(SyncProtocol::terminal) == true, SyncProtocol.retryAfter(retryAfter, clock.wall()))
        }
        override fun ended(code: Int) = synchronized(gate) { failed(a, if (code == 4003) "sync_rejected" else "stream_disconnected", code == 4003) }
    }
    private fun accept(a: Attempt, update: SourceUpdate) {
        val accepted = commit(update, {
            eligibleBeforeDeadline(a) && (!a.candidate || context().baseline === a.baseline) &&
                (update is FullUpdate || context().baseline === a.baseline)
        }) { baseline ->
            a.baseline = baseline; a.synchronized = true; forceFull = false
            resumeFirst = false
            failures = 0; failureSince = null
            if (a.candidate) {
                a.candidate = false; fallback = false; effective = SyncMode.STREAMING
                recovery = RecoveryStatus.NONE; candidateFailure = null; stableSince = clock.elapsed()
            }
            if (!a.streaming) {
                a.valid = false; active = null
                nextAt = clock.deadlineAfter(if (background) options.backgroundPollingIntervalMillis else options.pollingIntervalMillis)
            }
        }
        if (!accepted) synchronized(gate) { failed(a, "sync_baseline_changed", false) }
    }
    private fun coolDown() {
        recovery = RecoveryStatus.COOLING_DOWN
        val delay = minOf(300_000L, 60_000L * (1L shl minOf(recoveryFailures, 3)))
        recoveryAt = clock.deadlineAfter(delay)
        recoveryFailures = minOf(recoveryFailures + 1, 4)
    }
    private fun failed(a: Attempt, code: String, fatal: Boolean, retryAfter: Long? = null) {
        if (!eligible(a)) return
        if (fatal && !eligibleBeforeDeadline(a)) { failed(a, "sync_timeout", false); return }
        if (code == "sync_timeout" && !a.synchronized && !a.candidate) forceFull = true
        val candidate = a.candidate
        resumeFirst = false
        revoke(); stableSince = null
        if (fatal) { allowed = false; terminated = true; recovery = RecoveryStatus.NONE; failure(code, true); return }
        if (candidate) {
            candidateFailure = Diagnostic(code); coolDown(); nextAt = clock.elapsed(); changed()
        } else {
            if (a.streaming && failureSince == null) failureSince = clock.elapsed()
            val cap = minOf(60_000L, 1_000L * (1L shl minOf(failures, 6)))
            failures = minOf(failures + 1, 7)
            nextAt = clock.deadlineAfter(maxOf(1, (cap * random()).toLong(), retryAfter ?: 0))
            failure(code, false)
        }
    }
}
