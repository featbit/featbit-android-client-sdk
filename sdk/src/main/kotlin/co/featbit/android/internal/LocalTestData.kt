package co.featbit.android.internal

import co.featbit.android.api.*
import co.featbit.android.datasource.*
import co.featbit.android.testing.*

internal class LocalTestData(
    initial: List<BootstrapFlag>,
    private val clock: Clock,
    private val dispatch: Dispatch,
) : TestData {
    private val lock = Any()
    private val budget = CallbackBudget()
    private var flags = initial.associateBy { it.key }
    private var records =
        BootstrapRecords.create(initial)!!.associate { flag ->
            flag.key to
                FlagRecord.builder(
                        flag.key,
                        flag.variation,
                        flag.variationType,
                        clock.wall().coerceAtLeast(0),
                    )
                    .build()
                    .value!!
        }
    private var binding: Any? = null
    private var sink: SourceUpdateSink? = null
    private var boundSession: Any? = null

    fun bind(owner: Any): Boolean =
        synchronized(lock) {
            if (binding != null) false
            else {
                binding = owner
                true
            }
        }

    fun unbind(owner: Any) =
        synchronized(lock) {
            if (binding === owner) {
                binding = null
                sink = null
                boundSession = null
            }
        }

    override fun capabilities(): SourceCapabilities = SourceCapabilities(Provenance.LOCAL, false)

    override fun validate(): Outcome<SourceValidation> = Outcome.success(SourceValidation.VALID)

    override fun clientOptions(user: User): Outcome<ClientOptions> =
        ClientOptions.builder()
            .user(user)
            .source(this)
            .disableEvents(true)
            .cacheEnabled(false)
            .build()

    override fun create(
        context: SourceSessionContext,
        sink: SourceUpdateSink,
    ): Outcome<DataSource> {
        val token = Any()
        return Outcome.success(
            object : DataSource {
                override fun start(completion: Completion<SourceStarted>) {
                    synchronized(lock) {
                        val previous = this@LocalTestData.sink
                        val previousToken = boundSession
                        this@LocalTestData.sink = sink
                        boundSession = token
                        val result = submit(flags).getResult()
                        if (result?.value != TestDataResult.COMMITTED) {
                            this@LocalTestData.sink = previous
                            boundSession = previousToken
                        }
                    }
                    completion.onComplete(Outcome.success(SourceStarted.STARTED))
                }

                override fun stop(completion: Completion<SourceStopped>) {
                    synchronized(lock) {
                        if (boundSession === token) {
                            this@LocalTestData.sink = null
                            boundSession = null
                        }
                    }
                    completion.onComplete(Outcome.success(SourceStopped.STOPPED))
                }
            }
        )
    }

    private fun done(outcome: Outcome<TestDataResult>): Operation<TestDataResult> =
        ResultOperation<TestDataResult>(dispatch, budget, clock).apply { settle(outcome) }

    private fun submit(next: Map<String, BootstrapFlag>): Operation<TestDataResult> {
        val raw =
            BootstrapRecords.create(next.values.toList())
                ?: return done(Outcome.invalid("duplicate_flag_key"))
        // Full replacement orders mutations independently of wall-clock rollback/equal
        // milliseconds.
        val timestamp = clock.wall().coerceAtLeast(0)
        val prepared =
            raw.map { flag ->
                records[flag.key]?.takeIf {
                    it.variation == flag.variation && it.variationType == flag.variationType
                }
                    ?: FlagRecord.builder(flag.key, flag.variation, flag.variationType, timestamp)
                        .build()
                        .value!!
            }
        flags = LinkedHashMap(next)
        records = prepared.associateBy { it.key }
        val target = sink ?: return done(Outcome.success(TestDataResult.SAVED_FOR_NEXT_START))
        val submission = target.submit(FullUpdate.create(prepared).value!!)
        val result = ResultOperation<TestDataResult>(dispatch, budget, clock)
        fun finish(outcome: Outcome<SourceUpdateResult>) {
            val update = outcome.value
            result.settle(
                when {
                    !outcome.isSuccess -> Outcome.failure(outcome.code, outcome.diagnostic)
                    update?.code == SourceUpdateCode.COMMITTED ->
                        Outcome.success(TestDataResult.COMMITTED)
                    update?.code in
                        setOf(
                            SourceUpdateCode.INACTIVE,
                            SourceUpdateCode.CLOSED,
                            SourceUpdateCode.SUPERSEDED,
                        ) -> Outcome.success(TestDataResult.SAVED_FOR_NEXT_START)
                    else ->
                        Outcome.failure(
                            if (update?.code == SourceUpdateCode.BACKPRESSURED)
                                OutcomeCode.CAPACITY_EXCEEDED
                            else OutcomeCode.INVALID,
                            update?.error,
                        )
                }
            )
        }
        val immediate = submission.getResult()
        if (immediate != null) finish(immediate)
        else {
            val observed = submission.observe(::finish)
            if (!observed.isSuccess)
                result.settle(Outcome.failure(observed.code, observed.diagnostic))
        }
        return result
    }

    override fun replace(flags: List<BootstrapFlag>): Operation<TestDataResult> {
        if (BootstrapRecords.create(flags) == null)
            return done(Outcome.invalid("duplicate_flag_key"))
        return synchronized(lock) { submit(flags.associateBy { it.key }) }
    }

    override fun update(flag: BootstrapFlag): Operation<TestDataResult> =
        synchronized(lock) { submit(flags + (flag.key to flag)) }

    override fun remove(key: String): Operation<TestDataResult> =
        synchronized(lock) {
            if (key.isEmpty()) done(Outcome.invalid("invalid_flag_key")) else submit(flags - key)
        }
}

internal object LocalTestDataFactory : TestDataFactory {
    override fun create(initialFlags: List<BootstrapFlag>): Outcome<TestData> {
        if (BootstrapRecords.create(initialFlags) == null)
            return Outcome.invalid("duplicate_flag_key")
        // Lazy main dispatch also permits constructing TestData before Android initialization.
        val dispatch = Dispatch { mainDispatch().post(it) }
        return Outcome.success(LocalTestData(initialFlags, AndroidClock, dispatch))
    }
}
