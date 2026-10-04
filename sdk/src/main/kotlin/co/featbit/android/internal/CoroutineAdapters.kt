package co.featbit.android.internal

import co.featbit.android.api.*
import co.featbit.android.kotlin.ClientAdapters
import kotlin.coroutines.resume
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal object CoroutineAdapters : ClientAdapters {
    override suspend fun <T> await(operation: Operation<T>, timeoutMillis: Long): Outcome<T> {
        if (timeoutMillis < 1) return Outcome.invalid("invalid_timeout")
        operation.getResult()?.let {
            return it
        }
        val clock = (operation as? ResultOperation<T>)?.clock
        val deadline = clock?.deadlineAfter(timeoutMillis)
        return withTimeoutOrNull(timeoutMillis) {
            coroutineScope {
                val delivered =
                    async<Outcome<T>> {
                        suspendCancellableCoroutine<Outcome<T>> { continuation ->
                            val observed =
                                operation.observe {
                                    val outcome =
                                        if (
                                            clock != null &&
                                                deadline != null &&
                                                clock.elapsed() >= deadline
                                        )
                                            Outcome.failure<T>(
                                                OutcomeCode.TIMED_OUT,
                                                Diagnostic("wait_deadline"),
                                            )
                                        else it
                                    if (continuation.isActive) continuation.resume(outcome)
                                }
                            if (!observed.isSuccess && continuation.isActive)
                                continuation.resume(
                                    Outcome.failure(observed.code, observed.diagnostic)
                                )
                            continuation.invokeOnCancellation { observed.value?.close() }
                        }
                    }
                val elapsedDeadline = async {
                    if (clock != null && deadline != null) {
                        while (clock.elapsed() < deadline) delay(
                            minOf(50, deadline - clock.elapsed()).coerceAtLeast(1)
                        )
                    } else delay(timeoutMillis)
                    Outcome.failure<T>(OutcomeCode.TIMED_OUT, Diagnostic("wait_deadline"))
                }
                try {
                    select {
                        delivered.onAwait { it }
                        elapsedDeadline.onAwait { it }
                    }
                } finally {
                    delivered.cancel()
                    elapsedDeadline.cancel()
                }
            }
        } ?: Outcome.failure(OutcomeCode.TIMED_OUT, Diagnostic("wait_deadline"))
    }

    override fun changes(client: FeatBitClient): Flow<FlagChange> = changes(client, null)

    override fun flagChanges(client: FeatBitClient, key: String): Flow<FlagChange> =
        changes(client, key)

    private fun changes(client: FeatBitClient, key: String?): Flow<FlagChange> =
        callbackFlow {
                // A full invalidation is safe under downstream conflation: no affected key is lost.
                val listener = ChangeListener {
                    trySend(
                        if (key == null) FlagChange(emptySet(), true)
                        else FlagChange(setOf(key), false)
                    )
                }
                val subscription =
                    if (key == null) client.subscribeChanges(listener)
                    else client.subscribeFlag(key, listener)
                if (!subscription.isSuccess) {
                    if (subscription.code == OutcomeCode.CLOSED) close()
                    else
                        close(
                            IllegalStateException(
                                subscription.diagnostic?.code ?: subscription.code.name
                            )
                        )
                    return@callbackFlow
                }
                val closing =
                    try {
                        (client as? CloseAware)?.onClosed { close() }
                    } catch (e: IllegalStateException) {
                        subscription.value!!.registration.close()
                        close(e)
                        return@callbackFlow
                    }
                awaitClose {
                    subscription.value!!.registration.close()
                    closing?.close()
                }
            }
            .conflate()

    override fun status(client: FeatBitClient): Flow<ConnectionInformation> =
        callbackFlow {
                val subscription = client.subscribeStatus { trySend(it) }
                if (!subscription.isSuccess) {
                    if (subscription.code == OutcomeCode.CLOSED) close()
                    else
                        close(
                            IllegalStateException(
                                subscription.diagnostic?.code ?: subscription.code.name
                            )
                        )
                    return@callbackFlow
                }
                val closing =
                    try {
                        (client as? CloseAware)?.onClosed { close() }
                    } catch (e: IllegalStateException) {
                        subscription.value!!.close()
                        close(e)
                        return@callbackFlow
                    }
                awaitClose {
                    subscription.value!!.close()
                    closing?.close()
                }
            }
            .conflate()
}
