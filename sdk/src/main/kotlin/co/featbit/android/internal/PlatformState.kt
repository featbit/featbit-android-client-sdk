package co.featbit.android.internal

/** Unknown connectivity permits request-based recovery. Visibility starts conservatively paused. */
internal data class PlatformState(
    val foreground: Boolean = false,
    val network: Boolean = true,
    val executionAllowed: Boolean = true,
)

/** Serializes initial adoption with platform changes; closing fences queued native callbacks. */
internal class PlatformStateRelay {
    private var state = PlatformState()
    private var listener: ((PlatformState) -> Unit)? = null
    private var closed = false

    @Synchronized
    fun update(transform: (PlatformState) -> PlatformState) {
        if (closed) return
        val next = transform(state)
        if (next == state) return
        state = next
        listener?.invoke(next)
    }

    @Synchronized
    fun bind(create: (PlatformState) -> ((PlatformState) -> Unit)): Boolean {
        if (closed) return false
        check(listener == null)
        listener = create(state)
        return true
    }

    @Synchronized
    fun close() {
        closed = true
        listener = null
    }
}

/** API 21+ callbacks track all Internet-capable networks, including overlapping handovers. */
internal class AvailableNetworks<T> {
    private val networks = HashSet<T>()

    fun initialize(initial: Collection<T>) {
        networks.clear()
        networks.addAll(initial)
    }

    fun available(network: T): Boolean {
        networks.add(network)
        return networks.isNotEmpty()
    }

    fun lost(network: T): Boolean {
        networks.remove(network)
        return networks.isNotEmpty()
    }

    val available: Boolean
        get() = networks.isNotEmpty()
}
