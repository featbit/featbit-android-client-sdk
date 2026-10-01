package co.featbit.android.internal

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import co.featbit.android.api.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal interface Clock {
    fun elapsed(): Long
    fun wall(): Long
}
internal object AndroidClock : Clock {
    override fun elapsed() = SystemClock.elapsedRealtime()
    override fun wall() = System.currentTimeMillis()
}
internal fun interface Dispatch { fun post(action: () -> Unit) }
internal interface Workers {
    fun execute(action: () -> Unit): Boolean
    fun close()
}
internal class BoundedWorkers(threads: Int = 2, capacity: Int = 64) : Workers {
    private val executor = ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(capacity), { r -> Thread(r, "FeatBit-worker").apply { isDaemon = true } })
    override fun execute(action: () -> Unit): Boolean = try { executor.execute(action); true }
        catch (_: java.util.concurrent.RejectedExecutionException) { false }
    // Do not discard admitted cleanup; hung extensions retain their physical slots.
    override fun close() { executor.shutdown() }
}
internal interface Ticker { fun start(action: () -> Unit); fun close() }
internal class DeadlineTicker : Ticker {
    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "FeatBit-deadlines").apply { isDaemon = true } }
    override fun start(action: () -> Unit) { executor.scheduleWithFixedDelay(action, 0, 50, TimeUnit.MILLISECONDS) }
    override fun close() { executor.shutdown() }
}
internal class CallbackBudget(private val limit: Int = 256) {
    private var count = 0
    @Synchronized fun acquire(): Boolean = if (count == limit) false else { count++; true }
    @Synchronized fun release() { count-- }
}

/** Handles own only bounded registrations; final results outlive the runtime. */
internal class ResultOperation<T>(private val dispatch: Dispatch, private val budget: CallbackBudget,
    val clock: Clock? = null) : Operation<T> {
    private val lock = Any()
    private var result: Outcome<T>? = null
    private val observers = LinkedHashSet<Observer>()
    override fun getResult(): Outcome<T>? = synchronized(lock) { result }
    override fun observe(callback: Completion<T>): Outcome<Registration> {
        if (!budget.acquire()) return Outcome.failure(OutcomeCode.CAPACITY_EXCEEDED, Diagnostic("callback_capacity"))
        val observer = Observer(callback)
        val ready = synchronized(lock) { observers.add(observer); result }
        if (ready != null) observer.enqueue(ready)
        return Outcome.success(observer)
    }
    fun settle(value: Outcome<T>) {
        val targets = synchronized(lock) {
            if (result != null) return
            result = value
            observers.toList()
        }
        targets.forEach { it.enqueue(value) }
    }
    private inner class Observer(private var callback: Completion<T>?) : Registration {
        private var queued = false
        private var released = false
        private fun release() { if (!released) { released = true; budget.release() } }
        fun enqueue(value: Outcome<T>) {
            synchronized(lock) { if (queued || callback == null) return; queued = true }
            dispatch.post {
                val action = synchronized(lock) {
                    val saved = callback
                    callback = null; observers.remove(this); release()
                    saved
                }
                try { action?.onComplete(value) } catch (_: Exception) { /* application isolation */ }
            }
        }
        override fun close() = synchronized(lock) {
            if (callback != null) { callback = null; observers.remove(this); if (!queued) release() }
        }
    }
}
internal fun mainDispatch(): Dispatch {
    val handler = Handler(Looper.getMainLooper())
    return Dispatch { handler.post(it) }
}
internal class Diagnostics(private val options: ClientOptions, private val clock: Clock,
    private val worker: Workers = BoundedWorkers(1, 128)) {
    private val times = LinkedHashMap<String, Long>()
    fun report(code: String) {
        if (options.logger == null || options.logLevel == LogLevel.NONE || options.logLevel.ordinal < LogLevel.WARN.ordinal) return
        synchronized(times) {
            val now = clock.elapsed()
            if (times[code]?.let { now - it < 60_000 } == true) return
            if (times.size >= 128) times.remove(times.keys.first())
            times[code] = now
        }
        worker.execute { try { options.logger.log(LogLevel.WARN, Diagnostic(code)) } catch (_: Exception) { } }
    }
    fun close() = worker.close()
}
