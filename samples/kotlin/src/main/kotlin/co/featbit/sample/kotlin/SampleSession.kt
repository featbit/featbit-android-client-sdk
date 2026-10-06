package co.featbit.sample.kotlin

import android.content.Context
import co.featbit.android.api.*
import co.featbit.android.kotlin.ClientAdapters
import co.featbit.android.testing.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Application-owned SDK integration. All public calls and state mutations run on main. */
class SampleSession(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapters = ClientAdapters.getDefault()
    private val mutable = MutableStateFlow(ScreenState())
    val state: StateFlow<ScreenState> = mutable.asStateFlow()
    private var client: FeatBitClient? = null
    private var data: TestData? = null
    private var generation = 0L
    private var identityAdopting = false
    private var changes: Registration? = null
    private var statusJob: Job? = null
    private var readyJob: Job? = null
    val specs: List<FlagSpec> =
        Json.parseToJsonElement(
                context.assets.open("demo-flags.json").bufferedReader().use { it.readText() }
            )
            .jsonArray
            .map {
                val o = it.jsonObject
                FlagSpec(
                    o.getValue("key").jsonPrimitive.content,
                    ValueType.valueOf(o.getValue("type").jsonPrimitive.content),
                    o.getValue("initial").jsonPrimitive.content,
                    o.getValue("fallback").jsonPrimitive.content,
                )
            }
    val people: List<Person> =
        Json.parseToJsonElement(
                context.assets.open("users.json").bufferedReader().use { it.readText() }
            )
            .jsonArray
            .map {
                val o = it.jsonObject
                Person(
                    o.getValue("key").jsonPrimitive.content,
                    o.getValue("name").jsonPrimitive.content,
                    o.getValue("plan").jsonPrimitive.content,
                )
            }
    private val localValues = specs.associate { it.key to it.initial }.toMutableMap()
    val draft = ConnectionDraft()
    // In-process UI continuity. Credentials/editor content never enter saved-instance-state
    // bundles.
    var destination = "Demo"
    var detailKey: String? = null
    var formOpen = false
    var editorKey: String? = null
    var editorText = ""
    var userSheet = false
    var userChoice = 0
    var filter = ""
    var scrollY = 0

    private var started = false

    // Start after the first rendered frame, keeping renderer warm-up outside SDK deadlines.
    fun start() {
        if (!started) {
            started = true
            connect()
        }
    }

    private fun now() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun update(change: (ScreenState) -> ScreenState) {
        mutable.value = change(mutable.value).copy(revision = mutable.value.revision + 1)
    }

    private fun log(message: String) = update {
        it.copy(history = (it.history + ActivityEntry(now(), message)).takeLast(100))
    }

    fun dismissMessage() = update { it.copy(message = null) }

    private fun stale() = update { s ->
        s.copy(reads = s.reads.mapValues { it.value.copy(stale = true) })
    }

    fun invalidateReads() {
        if (state.value.reads.isNotEmpty()) stale()
    }

    fun sdkVersion(): String = client?.getVersion() ?: SdkInfo.getVersion()

    fun localValue(key: String): String = localValues[key] ?: specs.first { it.key == key }.initial

    private fun records(values: Map<String, String>) =
        specs
            .filter { it.key in values }
            .map { BootstrapFlag.create(it.key, values.getValue(it.key), it.type).value!! }

    fun validateValue(spec: FlagSpec, value: String): Boolean =
        when (spec.type) {
            ValueType.NUMBER -> value.toDoubleOrNull()?.isFinite() == true
            ValueType.BOOLEAN -> value == "true" || value == "false"
            ValueType.JSON -> runCatching { Json.parseToJsonElement(value) }.isSuccess
            ValueType.STRING -> true
        }

    fun validateDraft(): Map<String, String> {
        if (draft.local) return emptyMap()
        val errors = mutableMapOf<String, String>()
        if (draft.key.isBlank()) errors["sdkKey"] = "Client SDK Key is required"
        val endpoint = if (draft.mode == SyncMode.STREAMING) draft.streaming else draft.polling
        if (endpoint.isBlank())
            errors[if (draft.mode == SyncMode.STREAMING) "streamingUrl" else "pollingUrl"] =
                "Synchronization URL is required"
        if (draft.events && draft.eventsUrl.isBlank())
            errors["eventsUrl"] = "Events URL is required"
        if (draft.mode == SyncMode.STREAMING && draft.pollingFallback && draft.polling.isBlank()) {
            errors["pollingUrl"] = "Polling URL is required when fallback is enabled"
        }
        if (errors.isEmpty()) {
            val outcome = liveOptions().build()
            if (!outcome.isSuccess)
                errors[outcome.diagnostic?.field ?: "form"] =
                    "Invalid configuration: ${outcome.diagnostic?.code ?: outcome.code}"
        }
        return errors
    }

    private fun liveOptions(): ClientOptions.Builder =
        ClientOptions.builder()
            .user(people[state.value.user].user())
            .sdkKey(draft.key)
            .mode(draft.mode)
            .disableEvents(!draft.events)
            .apply {
                if (draft.mode == SyncMode.STREAMING) {
                    streamingUrl(draft.streaming)
                    pollingFallback(draft.pollingFallback)
                    if (draft.pollingFallback) pollingUrl(draft.polling)
                } else {
                    pollingUrl(draft.polling)
                    pollingFallback(false)
                }
                if (draft.events) eventsUrl(draft.eventsUrl)
            }

    /**
     * Adapters detach their wait on timeout; keep observing the original operation until settled.
     */
    private suspend fun <T> settled(operation: Operation<T>, budget: Long): Outcome<T> {
        while (true) {
            operation.getResult()?.let {
                return it
            }
            val result = adapters.await(operation, budget)
            operation.getResult()?.let {
                return it
            }
            if (result.code != OutcomeCode.TIMED_OUT)
                delay(100) // Registration failure is not the underlying operation result.
        }
    }

    fun connect() {
        if (state.value.busy != null || state.value.flushPending) return
        val errors = validateDraft()
        if (errors.isNotEmpty()) {
            update { it.copy(message = errors.values.first()) }
            return
        }
        val nextLocal = draft.local
        val nextEvents = !nextLocal && draft.events
        // Validate all proposed options before retiring the existing client.
        val nextData =
            if (nextLocal) TestDataFactory.getDefault().create(records(localValues)).value else null
        val options =
            if (nextLocal) nextData?.clientOptions(people[state.value.user].user())
            else liveOptions().build()
        if (options == null || !options.isSuccess) {
            update { it.copy(message = "Invalid configuration") }
            return
        }
        val revision = ++generation
        identityAdopting = false
        readyJob?.cancel()
        statusJob?.cancel()
        changes?.close()
        changes = null
        stale()
        update {
            it.copy(busy = "Reconnecting…", message = null, waitTimedOut = false, active = false)
        }
        scope.launch {
            val old = client
            client = null
            if (old != null) {
                val closed = settled(old.close(), 6000)
                if (
                    !closed.isSuccess ||
                        closed.value?.cleanupComplete == false ||
                        (closed.value?.undeliveredEvents ?: 0) > 0
                ) {
                    val warning =
                        "Close: ${closed.code}; undelivered=${closed.value?.undeliveredEvents ?: "unknown"}; cleanup=${closed.value?.cleanupComplete ?: "unknown"}"
                    log(warning)
                    update { it.copy(message = warning) }
                }
            }
            val made = settled(ClientFactory.getDefault().create(context, options.value!!), 6000)
            if (revision != generation) {
                made.value?.close()
                return@launch
            }
            if (!made.isSuccess) {
                update {
                    it.copy(
                        busy = null,
                        active = false,
                        status = null,
                        snapshot = emptyMap(),
                        business = Business(),
                        message = "Connection failed: ${made.diagnostic?.code ?: made.code}",
                    )
                }
                log("Create failed: ${made.code}")
                return@launch
            }
            val c = made.value!!
            client = c
            data = nextData
            update {
                it.copy(
                    local = nextLocal,
                    events = nextEvents,
                    busy = null,
                    active = true,
                    status = c.getConnectionInformation(),
                    lastTrack = null,
                    lastFlush = null,
                )
            }
            // Registration and initial snapshot are atomic at the SDK boundary.
            val registered =
                c.subscribeChanges {
                    if (generation == revision) {
                        refresh(c)
                        stale()
                        log("Flags updated")
                    }
                }
            if (registered.isSuccess) changes = registered.value!!.registration
            else update { it.copy(message = "Flag subscription failed: ${registered.code}") }
            refresh(c)
            statusJob =
                scope.launch {
                    adapters
                        .status(c)
                        .catch {
                            update { s -> s.copy(message = "Status observation unavailable") }
                        }
                        .collect { status ->
                            if (revision == generation)
                                update {
                                    it.copy(
                                        status = status,
                                        waitTimedOut =
                                            if (status.remoteConfirmed) false else it.waitTimedOut,
                                    )
                                }
                        }
                }
            log(if (nextLocal) "Local demo ready" else "Client created; waiting for remote data")
            readyJob =
                scope.launch {
                    val ready = settled(c.awaitReady(5000), 6000)
                    if (revision == generation) {
                        update { it.copy(waitTimedOut = ready.code == OutcomeCode.TIMED_OUT) }
                        log("Readiness: ${ready.value ?: ready.code}")
                    }
                }
        }
    }

    private fun refresh(c: FeatBitClient) {
        // Adoption can publish changes before its completion reaches main. Keep the old view
        // until the receipt lets us update the selected user and its data together.
        if (identityAdopting) return
        val snapshot = c.allVariations()
        val compact = c.boolVariationDetail(specs[0].key, false)
        val promo = c.stringVariationDetail(specs[1].key, specs[1].fallback)
        val discount = c.numberVariationDetail(specs[2].key, 0.0)
        val rawMenu = c.jsonTextVariationDetail(specs[3].key, specs[3].fallback)
        val parsedMenu = Business.menu(rawMenu.value)
        val badDiscount = !discount.value.isFinite() || discount.value !in 0.0..100.0
        val business =
            Business(
                compact.value,
                promo.value,
                if (badDiscount) 0.0 else discount.value,
                parsedMenu ?: Business.DEFAULT_MENU,
                parsedMenu == null,
                badDiscount,
                listOf(compact.reason, promo.reason, discount.reason, rawMenu.reason).any {
                    it != EvaluationReason.MATCH
                },
            )
        update {
            it.copy(
                snapshot = snapshot,
                business = business,
                selectedSize =
                    if (business.menu.sizes.any { size -> size.id == it.selectedSize })
                        it.selectedSize
                    else business.menu.defaultSize,
                status = c.getConnectionInformation(),
            )
        }
    }

    fun selectSize(id: String) {
        if (state.value.business.menu.sizes.any { it.id == id })
            update { it.copy(selectedSize = id) }
    }

    fun identify(index: Int) {
        val c = client ?: return
        if (!state.value.available || state.value.flushPending || index == state.value.user) return
        readyJob?.cancel()
        val revision = generation
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        identityAdopting = true
        stale()
        update { it.copy(busy = "Switching to ${people[index].name}…", waitTimedOut = false) }
        val op = c.identifyContext(people[index].user(), 5000)
        scope.launch {
            val adopted = settled(op, 6000)
            if (revision != generation) return@launch
            identityAdopting = false
            if (!adopted.isSuccess) {
                // No receipt means this request did not adopt; do not relabel the old user.
                update { it.copy(busy = null, message = "User change failed: ${adopted.code}") }
                refresh(c)
                log("Identify ${people[index].name}: ${adopted.code}")
                return@launch
            }
            update { it.copy(user = index) }
            refresh(c)
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            val result =
                if (remaining > 0) settled(c.awaitReady(remaining), remaining + 1000)
                else Outcome.failure<ReadyResult>(OutcomeCode.TIMED_OUT, null)
            if (revision == generation) {
                userSheet = false
                refresh(c)
                update {
                    it.copy(
                        busy = null,
                        waitTimedOut = result.code == OutcomeCode.TIMED_OUT,
                        message = "Identify: ${result.value ?: result.code}",
                    )
                }
                log("Identify ${people[index].name}: ${result.value ?: result.code}")
            }
        }
    }

    fun offline(offline: Boolean) {
        val c = client ?: return
        if (!state.value.available || state.value.flushPending) return
        val revision = generation
        update { it.copy(busy = if (offline) "Going offline…" else "Going online…") }
        scope.launch {
            val result = settled(if (offline) c.setOffline(5000) else c.setOnline(5000), 6000)
            if (generation == revision) {
                refresh(c)
                update { it.copy(busy = null, message = "Mode: ${result.value ?: result.code}") }
                log("Mode: ${result.value ?: result.code}")
            }
        }
    }

    fun edit(
        key: String?,
        value: String? = null,
        restoreAll: Boolean = false,
        completed: (Boolean) -> Unit = {},
    ) {
        val d = data ?: return
        if (!state.value.local || !state.value.available) return
        val spec = specs.firstOrNull { it.key == key }
        if (spec != null && value != null && !validateValue(spec, value)) {
            completed(false)
            return
        }
        val proposed = localValues.toMutableMap()
        val operation =
            when {
                restoreAll -> {
                    proposed.clear()
                    specs.forEach { proposed[it.key] = it.initial }
                    d.replace(records(proposed))
                }
                spec != null && value != null -> {
                    proposed[spec.key] = value
                    d.update(BootstrapFlag.create(spec.key, value, spec.type).value!!)
                }
                spec != null -> {
                    proposed.remove(spec.key)
                    d.remove(spec.key)
                }
                else -> return
            }
        update { it.copy(busy = "Saving local flags…") }
        val revision = generation
        scope.launch {
            val result = settled(operation, 6000)
            if (revision == generation) {
                if (result.isSuccess) {
                    localValues.clear()
                    localValues.putAll(proposed)
                    if (editorKey == key) editorKey = null
                }
                update {
                    it.copy(
                        busy = null,
                        message =
                            when {
                                result.value == TestDataResult.COMMITTED -> "Applied locally"
                                result.value == TestDataResult.SAVED_FOR_NEXT_START ->
                                    "Saved; applies when resumed"
                                else -> "Save failed: ${result.diagnostic?.code ?: result.code}"
                            },
                    )
                }
                if (result.value == TestDataResult.COMMITTED) client?.let { refresh(it) }
                log("TestData: ${result.value ?: result.code}")
                completed(result.isSuccess)
            }
        }
    }

    fun evaluate(key: String) {
        val c = client ?: return
        if (!state.value.available) return
        val spec = specs.first { it.key == key }
        val result: EvaluationDetail<*> =
            when (spec.type) {
                ValueType.BOOLEAN -> c.boolVariationDetail(key, false)
                ValueType.NUMBER -> c.numberVariationDetail(key, 0.0)
                ValueType.STRING -> c.stringVariationDetail(key, spec.fallback)
                ValueType.JSON -> c.jsonTextVariationDetail(key, spec.fallback)
            }
        update {
            it.copy(
                reads =
                    it.reads +
                        (key to
                            ReadRecord(
                                result.value.toString(),
                                result.reason.name,
                                spec.fallback,
                                people[it.user].name,
                                now(),
                            ))
            )
        }
        log("Evaluated $key: ${result.reason}")
    }

    fun order() {
        val c = client ?: return
        val s = state.value
        if (!s.available) return
        val size = s.business.menu.sizes.first { it.id == s.selectedSize }.label
        val result =
            if (s.local) "Events disabled in Local"
            else
                c.track("sample-order-completed", s.business.total.toDouble()).let {
                    (it.value ?: it.code).toString()
                }
        val record = OrderRecord(size, s.business.total, people[s.user].name, result)
        update {
            it.copy(order = record, lastTrack = result, message = "Demo order created · $result")
        }
        log("Order ${record.amount}: $result")
    }

    fun flush() {
        val c = client ?: return
        if (!state.value.available || !state.value.events || state.value.flushPending) return
        val revision = generation
        update { it.copy(flushPending = true) }
        scope.launch {
            val outcome = settled(c.flush(), 11000)
            if (revision == generation) {
                val result = (outcome.value ?: outcome.code).toString()
                update {
                    it.copy(flushPending = false, lastFlush = result, message = "Flush: $result")
                }
                log("Flush: $result")
            }
        }
    }
}
