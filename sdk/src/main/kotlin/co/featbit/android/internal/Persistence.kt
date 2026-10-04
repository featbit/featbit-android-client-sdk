package co.featbit.android.internal

import android.util.AtomicFile
import co.featbit.android.api.*
import co.featbit.android.datasource.*
import java.io.*
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.UUID

/** All methods are called on a storage worker, never on a client/metadata gate. */
internal interface AtomicStorage {
    fun read(): ByteArray?

    fun replace(bytes: ByteArray)
}

internal class AndroidStorage(private val path: File, private val limit: Int? = null) :
    AtomicStorage {
    override fun read(): ByteArray? {
        val file = AtomicFile(path)
        val input =
            try {
                file.openRead()
            } catch (e: FileNotFoundException) {
                if (path.exists() || File(path.path + ".bak").exists()) throw e
                return null
            }
        return input.use {
            val out = limit?.let(::LimitedBytes) ?: ByteArrayOutputStream()
            it.copyTo(out)
            out.toByteArray()
        }
    }

    override fun replace(bytes: ByteArray) {
        check(path.parentFile!!.isDirectory || path.parentFile!!.mkdirs())
        val file = AtomicFile(path)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            file.finishWrite(stream)
            // AtomicFile versions may log a failed final rename instead of throwing.
            // Verify the selected committed image before reporting a durable result.
            check(read()?.contentEquals(bytes) == true)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
}

internal const val CACHE_CONTEXTS: Int = 5

private class LimitedBytes(private val limit: Int) : ByteArrayOutputStream() {
    override fun write(b: Int) {
        check(count < limit)
        super.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(len <= limit - count)
        super.write(b, off, len)
    }
}

private fun DataOutputStream.text(value: String) {
    // UTF-16 code units preserve even unpaired surrogates without ambiguous replacement.
    writeInt(value.length)
    value.forEach { writeChar(it.code) }
}

private fun DataInputStream.text(): String {
    val count = readInt()
    require(count >= 0 && count <= available() / 2)
    return CharArray(count) { readChar() }.concatToString()
}

private fun DataOutputStream.optional(value: String?) {
    writeBoolean(value != null)
    if (value != null) text(value)
}

private fun DataInputStream.optional(): String? = if (readBoolean()) text() else null

private fun digest(write: (DataOutputStream) -> Unit): String {
    val hash = MessageDigest.getInstance("SHA-256")
    DataOutputStream(
            java.security.DigestOutputStream(
                object : OutputStream() {
                    override fun write(b: Int) {}

                    override fun write(b: ByteArray, off: Int, len: Int) {}
                },
                hash,
            )
        )
        .use(write)
    return hash.digest().joinToString("") { "%02x".format(it) }
}

internal fun contextKey(user: User): String = digest { out ->
    out.text(user.key)
    out.text(user.name)
    out.writeInt(user.attributes.size)
    user.attributes.toSortedMap().forEach { (key, value) ->
        out.text(key)
        out.text(value.kind.name)
        out.optional(value.text)
    }
}

internal fun cacheNamespace(options: ClientOptions, capabilities: SourceCapabilities?): String? {
    if (!options.cacheEnabled || options.sdkKey.isNullOrBlank()) return null
    if (
        capabilities != null &&
            (capabilities.provenance != Provenance.REMOTE ||
                capabilities.cacheDiscriminator.isNullOrBlank())
    )
        return null
    val streaming = Endpoint.parse(options.streamingUrl, true)?.namespace
    val polling = Endpoint.parse(options.pollingUrl, false)?.namespace
    if (
        options.streamingUrl != null && streaming == null ||
            options.pollingUrl != null && polling == null
    )
        return null
    // Custom forbids built-in endpoints. Its stable discriminator supplies deployment/source
    // identity, paired with the SDK key's environment fingerprint.
    if (capabilities == null && streaming == null && polling == null) return null
    if (
        capabilities == null &&
            ((options.mode == SyncMode.STREAMING && streaming == null) ||
                ((options.mode == SyncMode.POLLING ||
                    options.backgroundPolling ||
                    options.pollingFallback) && polling == null))
    )
        return null
    return digest { out ->
        out.writeInt(1)
        out.text(options.sdkKey)
        out.optional(streaming)
        out.optional(polling)
        out.text(if (capabilities == null) "builtin" else "custom")
        out.optional(capabilities?.cacheDiscriminator)
    }
}

internal data class CachedSnapshot(val records: Map<String, FlagRecord>, val cursor: Long)

private data class CacheEntry(
    val snapshot: CachedSnapshot,
    val saved: Long,
    val accessed: Long,
    val sequence: Long = 0,
)

/** Versioned namespace image: one atomic replacement also makes namespace clear atomic. */
private object CacheCodec {
    fun encode(entries: Map<String, CacheEntry>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x46424331)
            out.writeInt(1)
            out.writeInt(entries.size)
            entries.forEach { (key, entry) ->
                out.text(key)
                out.writeLong(entry.saved)
                out.writeLong(entry.accessed)
                out.text(Provenance.REMOTE.name)
                out.writeLong(entry.snapshot.cursor)
                out.writeInt(entry.snapshot.records.size)
                entry.snapshot.records.values.forEach { r ->
                    out.text(r.key)
                    out.text(r.variation)
                    out.text(r.variationType)
                    out.writeLong(r.timestamp)
                    out.writeBoolean(r.archived)
                    out.optional(r.reason)
                    out.writeInt(r.variationOptions?.size ?: -1)
                    r.variationOptions?.forEach {
                        out.text(it.id)
                        out.text(it.value)
                    }
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): LinkedHashMap<String, CacheEntry> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 0x46424331 && input.readInt() == 1)
        val count = input.readInt()
        require(count in 0..CACHE_CONTEXTS)
        val result = LinkedHashMap<String, CacheEntry>()
        repeat(count) {
            val key = input.text()
            require(key.matches(Regex("[a-f0-9]{64}")) && key !in result)
            val saved = input.readLong()
            val accessed = input.readLong()
            require(input.text() == Provenance.REMOTE.name)
            val cursor = input.readLong()
            require(cursor >= 0)
            val records = LinkedHashMap<String, FlagRecord>()
            val n = input.readInt()
            require(n >= 0 && n <= input.available() / 25)
            repeat(n) {
                val k = input.text()
                val value = input.text()
                val type = input.text()
                val time = input.readLong()
                val archived = input.readBoolean()
                val reason = input.optional()
                val optionsCount = input.readInt()
                require(optionsCount >= -1 && optionsCount <= input.available() / 8)
                val options =
                    if (optionsCount == -1) null
                    else List(optionsCount) { VariationOption(input.text(), input.text()) }
                val record =
                    FlagRecord.builder(k, value, type, time)
                        .archived(archived)
                        .reason(reason)
                        .variationOptions(options)
                        .build()
                        .value
                require(record != null && k !in records && time <= cursor)
                records[k] = record
            }
            result[key] = CacheEntry(CachedSnapshot(frozen(records), cursor), saved, accessed)
        }
        require(input.available() == 0)
        return result
    }
}

/**
 * One serial worker owns disk state. Metadata gate only allocates epochs/sequences and queues work.
 */
internal class DiskCoordinator(
    private val storage: AtomicStorage,
    private val worker: Workers,
    private val clock: Clock,
) {
    private val metadata = Any()
    private var epoch = 0L
    private var sequence = 0L
    private val queue = java.util.ArrayDeque<() -> Unit>()

    private data class Write(
        val epoch: Long,
        val sequence: Long,
        val snapshot: CachedSnapshot,
        val report: (String) -> Unit,
    )

    private val pending = LinkedHashMap<String, Write>()
    private var running = false
    // Worker confined; never read or changed under metadata/client gates.
    private var entries: LinkedHashMap<String, CacheEntry>? = null

    fun epoch(): Long = synchronized(metadata) { epoch }

    fun withEpoch(expected: Long, publish: () -> Unit): Boolean =
        synchronized(metadata) {
            if (epoch != expected) false
            else {
                publish()
                true
            }
        }

    private fun enqueue(action: () -> Unit): Boolean {
        if (queue.size >= 64) return false
        queue.add(action)
        if (!running) {
            running = true
            if (!worker.execute(::drain)) {
                running = false
                queue.removeLast()
                return false
            }
        }
        return true
    }

    private fun drain() {
        while (true) {
            val action =
                synchronized(metadata) {
                    if (queue.isEmpty()) {
                        running = false
                        return
                    }
                    queue.removeFirst()
                }
            action()
        }
    }

    private fun readEntries(report: (String) -> Unit): LinkedHashMap<String, CacheEntry> {
        entries?.let {
            return it
        }
        val loaded =
            try {
                storage.read()?.let(CacheCodec::decode) ?: LinkedHashMap()
            } catch (_: Exception) {
                report("cache_read_failed")
                LinkedHashMap()
            }
        entries = loaded
        return loaded
    }

    fun load(
        key: String,
        expected: Long,
        report: (String) -> Unit,
        completion: (CachedSnapshot?) -> Unit,
    ): Boolean =
        synchronized(metadata) {
            enqueue {
                if (synchronized(metadata) { epoch != expected }) completion(null)
                else {
                    val data = readEntries(report)
                    val entry = data[key]
                    val now = clock.wall()
                    if (entry != null) {
                        data[key] = entry.copy(accessed = now)
                    }
                    completion(entry?.snapshot)
                }
            }
        }

    /** Called at logical commit, before root publication. No serialization or I/O here. */
    fun commit(key: String, snapshot: CachedSnapshot, report: (String) -> Unit): Boolean =
        synchronized(metadata) {
            val write = Write(epoch, ++sequence, snapshot, report)
            if (pending.containsKey(key)) {
                pending[key] = write
                return true
            }
            if (pending.size >= CACHE_CONTEXTS) return false
            pending[key] = write
            if (
                !enqueue {
                    val next =
                        synchronized(metadata) {
                            if (pending[key]?.epoch == write.epoch) pending.remove(key) else null
                        }
                    if (next != null && synchronized(metadata) { next.epoch == epoch })
                        persist(key, next)
                }
            ) {
                pending.remove(key)
                return false
            }
            true
        }

    private fun persist(key: String, write: Write) {
        val now = clock.wall()
        val data = LinkedHashMap(readEntries(write.report))
        if ((data[key]?.sequence ?: 0) >= write.sequence) return
        data[key] = CacheEntry(write.snapshot, now, now, write.sequence)
        try {
            while (data.size > CACHE_CONTEXTS) data.remove(
                data.filterKeys { it != key }.minBy { it.value.accessed }.key
            )
            val bytes = CacheCodec.encode(data)
            if (synchronized(metadata) { write.epoch != epoch }) return
            storage.replace(bytes)
            entries = data
        } catch (_: Exception) {
            write.report("cache_write_failed")
        }
    }

    fun clear(key: String?, report: (String) -> Unit, completion: (Boolean) -> Unit): Boolean =
        synchronized(metadata) {
            // Reserve the barrier before revoking any work; capacity rejection has no effects.
            if (
                !enqueue {
                    val data = LinkedHashMap(readEntries(report))
                    if (key == null) data.clear() else data.remove(key)
                    entries = data
                    val ok =
                        try {
                            storage.replace(CacheCodec.encode(data))
                            true
                        } catch (_: Exception) {
                            report("cache_clear_failed")
                            false
                        }
                    completion(ok)
                }
            )
                return false
            epoch++
            pending.clear()
            true
        }
}

internal class PersistentAnonymous(
    private val storage: AtomicStorage,
    private val worker: Workers,
) : AnonymousRepository {
    private val metadata = Any()
    private var current: AnonymousIdentity? = null
    private var revision = 0L

    override fun withCurrent(identity: AnonymousIdentity, adopt: () -> Unit): Boolean =
        synchronized(metadata) {
            if (identity != current) false
            else {
                adopt()
                true
            }
        }

    override fun prepare(reset: Boolean, completion: (Outcome<AnonymousIdentity>) -> Unit) {
        if (
            !worker.execute {
                val result =
                    try {
                        val key =
                            if (reset) UUID.randomUUID().toString()
                            else
                                storage.read()?.let {
                                    val input = DataInputStream(ByteArrayInputStream(it))
                                    require(input.readInt() == 1)
                                    input.text().also { key ->
                                        require(key.isNotBlank() && input.available() == 0)
                                    }
                                }
                        val selected = key ?: UUID.randomUUID().toString()
                        if (reset || key == null) {
                            val bytes = ByteArrayOutputStream()
                            DataOutputStream(bytes).use {
                                it.writeInt(1)
                                it.text(selected)
                            }
                            storage.replace(bytes.toByteArray())
                        }
                        val identity =
                            synchronized(metadata) {
                                if (current?.key == selected) current!!
                                else AnonymousIdentity(selected, ++revision).also { current = it }
                            }
                        Outcome.success(identity)
                    } catch (_: Exception) {
                        Outcome.failure(
                            OutcomeCode.STORAGE_FAILED,
                            Diagnostic("anonymous_storage_failed"),
                        )
                    }
                completion(result)
            }
        )
            completion(
                Outcome.failure(OutcomeCode.CAPACITY_EXCEEDED, Diagnostic("storage_capacity"))
            )
    }
}

/**
 * Weak registry: clients and admitted work retain coordinators, so closing never resets live
 * ordering.
 */
internal object PersistenceRegistry {
    private val caches = HashMap<String, WeakReference<DiskCoordinator>>()
    private val identities = HashMap<String, WeakReference<PersistentAnonymous>>()
    // Process-owned bounded storage executor. Anonymous writes also remain serialized.
    private val disk = BoundedWorkers(1, 256)

    @Synchronized
    fun cache(root: File, namespace: String): DiskCoordinator {
        val path = File(root, "featbit/cache-$namespace")
        caches.entries.removeAll { it.value.get() == null }
        return caches[path.path]?.get()
            ?: DiskCoordinator(AndroidStorage(path), disk, AndroidClock).also {
                caches[path.path] = WeakReference(it)
            }
    }

    @Synchronized
    fun anonymous(root: File): PersistentAnonymous {
        val path = File(root, "featbit/anonymous")
        identities.entries.removeAll { it.value.get() == null }
        return identities[path.path]?.get()
            ?: PersistentAnonymous(AndroidStorage(path, 4096), disk).also {
                identities[path.path] = WeakReference(it)
            }
    }
}
