package co.featbit.android.testing

import co.featbit.android.api.BootstrapFlag
import co.featbit.android.api.ClientOptions
import co.featbit.android.api.Operation
import co.featbit.android.api.Outcome
import co.featbit.android.api.User
import co.featbit.android.datasource.DataSourceFactory

/** In-memory source for local API tests; no server targeting or analytics. */
public interface TestDataFactory {
    public fun create(initialFlags: List<BootstrapFlag>): Outcome<TestData>

    public companion object {
        @JvmStatic
        public fun getDefault(): TestDataFactory = co.featbit.android.internal.LocalTestDataFactory
    }
}

public interface TestData : DataSourceFactory {
    /** Configures this source with events and production cache disabled. Single-client binding. */
    public fun clientOptions(user: User): Outcome<ClientOptions>

    /**
     * Flag change timestamps in Unix milliseconds are generated internally; callers provide only
     * keys, values and types.
     */
    public fun replace(flags: List<BootstrapFlag>): Operation<TestDataResult>

    public fun update(flag: BootstrapFlag): Operation<TestDataResult>

    public fun remove(key: String): Operation<TestDataResult>
}

public enum class TestDataResult {
    COMMITTED,
    SAVED_FOR_NEXT_START,
}
