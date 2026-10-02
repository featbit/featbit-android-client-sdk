package co.featbit.android.internal

import co.featbit.android.api.*
import co.featbit.android.datasource.FlagRecord

/** All metadata operations share LocalClient's gate; one bounded effect per HTTP attempt. */
internal class Events(
    private val gate: Any, private val options: ClientOptions, private val clock: Clock,
    private val workers: Workers, private val diagnostics: Diagnostics,
    private val transportFactory: () -> SyncTransport, private val random: () -> Double,
) {
    private class Entry(val id: Long, val group: Long, val payload: String, val bytes: Int, val expires: Long)
    private class Batch(val entries: List<Entry>) {
        val payload = entries.joinToString(",", "[", "]") { it.payload }
        var attempts = 0
        var nextAt = 0L
    }
    private class Attempt(val batch: Batch, val deadline: Long) {
        var valid = true
        var dispatched = false
        var completed = false
        var handle: SyncHandle? = null
    }
    private class Waiting(val operation: ResultOperation<FlushResult>, val high: Long, var remaining: Int, val deadline: Long) {
        var loss = false
    }
    private val entries = LinkedHashMap<Long, Entry>()
    private val duplicates = HashSet<String>()
    private val groups = HashMap<Long, Int>()
    private var group = 0L
    private val waits = LinkedHashSet<Waiting>()
    private var sequence = 0L
    private var sealed = 0L
    private var bytes = 0L
    private var dedupBytes = 0L
    private var batch: Batch? = null
    private var physical: Attempt? = null
    private var transport: SyncTransport? = null
    private var offline = options.offline
    private var foreground = true
    private var network = true
    private var closing = false
    private var closeDeadline = Long.MAX_VALUE
    private var stopped = false
    private var terminal = false
    private var transitionHigh = 0L
    private var transitionDeadline = 0L
    private var periodic = clock.deadlineAfter(options.flushIntervalMillis)
    private var finalLoss = 0L
    val pending: Int get() = entries.size
    val waiting: Int get() = waits.size
    val idle: Boolean get() = physical == null
    private fun collect() = !options.disableEvents && !offline && !terminal && !closing && !stopped
    private fun deliver(): Boolean = !options.disableEvents && !offline && !terminal && !stopped && network && clock.elapsed() < closeDeadline &&
        (foreground || closing || clock.elapsed() < transitionDeadline)
    fun track(user: User, name: String, value: Double): Outcome<TrackResult> {
        if (name.isEmpty() || !value.isFinite()) return Outcome.invalid("invalid_track")
        if (!collect()) return Outcome.success(TrackResult.SUPPRESSED)
        return admit(user, "metrics", EventProtocol.metric(name, value))
    }
    fun evaluation(user: User, record: FlagRecord) {
        if (!collect()) return
        val event = EventProtocol.evaluation(record)
        if (event == null) { diagnostics.loss("event_invalid_metadata"); return }
        admit(user, "variations", event)
    }
    private fun admit(user: User, kind: String, event: kotlinx.serialization.json.JsonObject): Outcome<TrackResult> {
        val filtered = EventProtocol.user(options, user)
        val key = EventProtocol.payload(filtered, kind, event, null)
        if (key in duplicates) return Outcome.success(TrackResult.DEDUPLICATED)
        val payload = EventProtocol.payload(filtered, kind, event, clock.wall())
        val size = payload.toByteArray(Charsets.UTF_8).size
        val keySize = key.toByteArray(Charsets.UTF_8).size
        // Include the open-group dedup keys and reserve one physical batch's encoded bytes.
        if (entries.size >= options.eventCapacity || size > 262_142 || bytes + size + keySize + 262_144 > 8_388_608 || group !in groups && groups.size >= 256) {
            diagnostics.loss("event_capacity")
            return Outcome.failure(OutcomeCode.CAPACITY_EXCEEDED, Diagnostic("event_capacity"))
        }
        val entry = Entry(++sequence, group, payload, size, clock.deadlineAfter(86_400_000))
        groups[group] = (groups[group] ?: 0) + 1
        entries[entry.id] = entry; duplicates.add(key); bytes += size + keySize; dedupBytes += keySize
        return Outcome.success(TrackResult.ACCEPTED)
    }
    private fun seal() {
        sealed = sequence; if (duplicates.isNotEmpty()) group++
        duplicates.clear(); bytes -= dedupBytes; dedupBytes = 0
    }
    fun flush(op: ResultOperation<FlushResult>, capacity: Boolean = true) {
        seal()
        when {
            options.disableEvents -> op.settle(Outcome.failure(OutcomeCode.DISABLED, Diagnostic("events_disabled")))
            terminal -> op.settle(Outcome.failure(OutcomeCode.TERMINAL_FAILURE, Diagnostic("event_terminal")))
            entries.isEmpty() -> op.settle(Outcome.success(FlushResult.EMPTY))
            offline || !foreground -> op.settle(Outcome.failure(OutcomeCode.DEFERRED, Diagnostic("events_paused")))
            !capacity || waits.size >= 256 -> op.settle(Outcome.failure(OutcomeCode.CAPACITY_EXCEEDED, Diagnostic("operation_capacity")))
            else -> waits.add(Waiting(op, sequence, entries.size, clock.deadlineAfter(options.requestTimeoutMillis)))
        }
        tick()
    }
    fun permissions(nextOffline: Boolean, nextForeground: Boolean, nextNetwork: Boolean) {
        if (nextOffline != offline || nextForeground != foreground) seal()
        if (foreground && !nextForeground) {
            transitionHigh = sealed
            transitionDeadline = if (options.transitionFlush) clock.deadlineAfter(2_000) else 0
        }
        // Cancel transition attempts on foreground resume; never extend their original budget.
        if (!foreground && nextForeground) { revoke(); transitionDeadline = 0; periodic = clock.deadlineAfter(options.flushIntervalMillis) }
        offline = nextOffline; foreground = nextForeground; network = nextNetwork
        if (offline || !network || !foreground && transitionDeadline == 0L) revoke()
        if (offline) waits.toList().forEach { settle(it, Outcome.failure(OutcomeCode.DEFERRED, Diagnostic("offline_transition"))) }
    }
    private fun settle(wait: Waiting, result: Outcome<FlushResult>) { waits.remove(wait); wait.operation.settle(result) }
    private fun finish(items: List<Entry>, loss: String?) {
        for (entry in items) {
            if (entries.remove(entry.id) == null) continue
            bytes -= entry.bytes
            val remaining = groups.getValue(entry.group) - 1
            if (remaining == 0) groups.remove(entry.group) else groups[entry.group] = remaining
            if (loss != null) { diagnostics.loss(loss); if (closing) finalLoss++ }
            waits.toList().forEach { wait ->
                if (entry.id <= wait.high) { wait.remaining--; if (loss != null) wait.loss = true }
            }
        }
        waits.toList().forEach { wait ->
            if (wait.remaining == 0) settle(wait, if (clock.elapsed() >= wait.deadline)
                Outcome.failure(OutcomeCode.TIMED_OUT, Diagnostic("flush_deadline"))
            else Outcome.success(if (wait.loss) FlushResult.PROCESSED_WITH_LOSS else FlushResult.ALL_DELIVERED))
        }
    }
    private fun revoke() {
        val attempt = physical ?: return
        if (!attempt.valid) return
        attempt.valid = false
        if (attempt.dispatched) attempt.batch.nextAt = clock.deadlineAfter(if (attempt.batch.attempts <= 1) 1_000 else 2_000)
        attempt.handle?.cancel()
        // Keep the physical slot until callback or cancellation acknowledgement.
    }
    fun beginClose(deadline: Long) {
        closing = true; closeDeadline = deadline; seal()
        waits.toList().forEach { settle(it, Outcome.failure(OutcomeCode.CLOSED, null)) }
        if (offline || options.disableEvents || terminal || !network) finish(entries.values.toList(), "event_close_loss")
    }
    fun shutdown(): Long {
        stopped = true; revoke()
        finish(entries.values.toList(), "event_close_loss")
        transport?.close()
        batch = null; physical = null; transport = null; seal()
        return finalLoss
    }
    fun tick() {
        if (options.disableEvents || stopped) return
        val now = clock.elapsed()
        waits.toList().filter { now >= it.deadline }.forEach { settle(it, Outcome.failure(OutcomeCode.TIMED_OUT, Diagnostic("flush_deadline"))) }
        if (!closing && foreground && !offline && now >= periodic) { seal(); periodic = clock.deadlineAfter(options.flushIntervalMillis) }
        if (!foreground && !closing && transitionDeadline != 0L && now >= transitionDeadline) { transitionDeadline = 0; revoke() }
        physical?.let { if (it.valid && now >= it.deadline) revoke() }
        // Expiry is a final outcome even while delivery is paused. Revoke its old authority first.
        if (entries.values.any { now >= it.expires }) {
            seal()
            if (batch?.entries?.any { now >= it.expires } == true) { revoke(); finish(batch!!.entries, "event_expired"); batch = null }
            finish(entries.values.filter { now >= it.expires }, "event_expired")
        }
        if (!deliver() || physical != null) return
        var current = batch
        if (current == null) {
            val limit = if (!foreground && !closing) minOf(sealed, transitionHigh) else sealed
            val items = ArrayList<Entry>(); var size = 2
            for (entry in entries.values) {
                if (entry.id > limit || items.size == 50 || size + entry.bytes + (if (items.isEmpty()) 0 else 1) > 262_144) break
                items.add(entry); size += entry.bytes + (if (items.size == 1) 0 else 1)
            }
            if (items.isEmpty()) return
            current = Batch(items); batch = current
        }
        if (current.attempts >= 3) { finish(current.entries, "event_retry_exhausted"); batch = null; return }
        if (now < current.nextAt) return
        val selected = current
        val deadline = minOf(closeDeadline, clock.deadlineAfter(options.requestTimeoutMillis),
            if (!foreground && !closing) transitionDeadline else Long.MAX_VALUE)
        val attempt = Attempt(selected, deadline)
        physical = attempt
        if (!workers.execute { dispatch(attempt) }) { physical = null; selected.nextAt = clock.deadlineAfter(1_000) }
    }
    private fun dispatch(attempt: Attempt) {
        try {
            val request = EventProtocol.request(options, attempt.batch.payload)
            val io = synchronized(gate) {
                if (!attempt.valid || stopped || !deliver() || clock.elapsed() >= attempt.deadline) {
                    if (physical === attempt) physical = null
                    return
                }
                val io = transport ?: transportFactory().also { transport = it }
                attempt.dispatched = true; attempt.batch.attempts++
                io
            }
            val handle = io.start(request, false, object : SyncListener {
                override fun opened() = Unit
                override fun message(text: String) = Unit
                override fun ended(code: Int) { complete(attempt, null, null) }
                override fun failed(status: Int?, retryAfter: String?) { complete(attempt, status, retryAfter) }
                override fun response(status: Int, body: String, retryAfter: String?) { complete(attempt, status, retryAfter) }
            })
            synchronized(gate) { attempt.handle = handle; if (!attempt.valid || attempt.completed) handle.cancel() }
        } catch (_: Exception) {
            synchronized(gate) {
                // Request construction or transport creation can fail before native dispatch.
                // Such failures must still consume the finite batch budget.
                if (!attempt.dispatched && attempt.valid && !attempt.completed) attempt.batch.attempts++
            }
            complete(attempt, null, null)
        }
    }
    private fun complete(attempt: Attempt, status: Int?, retryAfter: String?) = synchronized(gate) {
        if (attempt.completed) return@synchronized
        attempt.completed = true
        if (physical === attempt) physical = null
        if (!attempt.valid || stopped || batch !== attempt.batch) return@synchronized
        val authorityDeadline = minOf(attempt.deadline, closeDeadline,
            if (!foreground && !closing) transitionDeadline else Long.MAX_VALUE,
            attempt.batch.entries.minOf { it.expires })
        if (clock.elapsed() >= authorityDeadline) { retry(attempt.batch, null); return@synchronized }
        when {
            status != null && status in 200..299 -> { finish(attempt.batch.entries, null); batch = null }
            status != null && status in 400..499 && status !in setOf(400, 408, 429) -> {
                terminal = true
                waits.toList().forEach { settle(it, Outcome.failure(OutcomeCode.TERMINAL_FAILURE, Diagnostic("event_terminal"))) }
                seal(); finish(entries.values.toList(), "event_terminal_loss"); batch = null
            }
            else -> retry(attempt.batch, retryAfter)
        }
    }
    private fun retry(batch: Batch, retryAfter: String?) {
        if (batch.attempts >= 3) { finish(batch.entries, "event_retry_exhausted"); this.batch = null; return }
        val delay = maxOf(1L, ((if (batch.attempts <= 1) 1_000 else 2_000) * random().coerceIn(0.0, 1.0)).toLong())
        val server = SyncProtocol.retryAfter(retryAfter, clock.wall())?.coerceAtMost(86_400_000) ?: 0
        batch.nextAt = clock.deadlineAfter(maxOf(delay, server))
    }
}
