package co.featbit.android.kotlin

import co.featbit.android.api.*
import kotlinx.coroutines.flow.Flow

/** Coroutine adapter contract. Phase 2 supplies the implementation over Operation/Registration. */
public interface ClientAdapters {
    public suspend fun <T> await(operation: Operation<T>, timeoutMillis: Long): Outcome<T>
    public fun changes(client: FeatBitClient): Flow<FlagChange>
    public fun flagChanges(client: FeatBitClient, key: String): Flow<FlagChange>
    public fun status(client: FeatBitClient): Flow<ConnectionInformation>
}
