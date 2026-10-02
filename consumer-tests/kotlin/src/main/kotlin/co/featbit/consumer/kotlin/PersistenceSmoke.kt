package co.featbit.consumer.kotlin

import android.content.Context
import android.content.ContextWrapper
import co.featbit.android.api.*
import co.featbit.android.datasource.*
import co.featbit.android.kotlin.ClientAdapters
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File

/** Real AtomicFile/noBackupFilesDir coverage through the independently staged AAR. No network. */
object PersistenceSmoke {
    suspend fun verify(application: Context) {
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(application.noBackupFilesDir, "phase3-consumer")
        }
        val adapters = ClientAdapters.getDefault()
        val clients = ArrayList<FeatBitClient>()
        val prefs = application.getSharedPreferences("phase3-proof", Context.MODE_PRIVATE)
        val user = User.builder("persisted-user").name("Persisted User").build().value!!
        class Remote(private val identity: Boolean = false) : DataSourceFactory {
            var sink: SourceUpdateSink? = null
            override fun capabilities() = SourceCapabilities(Provenance.REMOTE, false, "phase3-consumer-v1")
            override fun validate() = Outcome.success(SourceValidation.VALID)
            override fun create(context: SourceSessionContext, sink: SourceUpdateSink): Outcome<DataSource> {
                this.sink = sink
                return Outcome.success(object : DataSource {
                    override fun start(completion: Completion<SourceStarted>) {
                        if (identity) sink.submit(FullUpdate.create(listOf(
                            FlagRecord.builder("identity", context.user.key, "string", 1).build().value!!)).value!!)
                        completion.onComplete(Outcome.success(SourceStarted.STARTED))
                    }
                    override fun stop(completion: Completion<SourceStopped>) { completion.onComplete(Outcome.success(SourceStopped.STOPPED)) }
                })
            }
        }
        suspend fun create(source: Remote, offline: Boolean, anonymous: Boolean = false): FeatBitClient {
            val options = ClientOptions.builder().user(if (anonymous) null else user).anonymousEnabled(anonymous)
                .source(source).sdkKey("consumer-only-not-a-service-key")
                .disableEvents(true).offline(offline).cacheEnabled(!anonymous).build().value!!
            val created = adapters.await(ClientFactory.getDefault().create(context, options), 5000)
            check(created.isSuccess) { "create: ${created.code} ${created.diagnostic?.code}" }
            return created.value!!.also { clients.add(it) }
        }
        suspend fun value(client: FeatBitClient, key: String, expected: String? = null): String = withTimeout(5000) {
            var result: String
            while (true) {
                result = client.stringVariation(key, "<missing>")
                if (result != "<missing>" && (expected == null || result == expected)) break
                delay(10)
            }
            result
        }
        try {
            val previous = prefs.getString("cache", null)
            if (previous != null) check(value(create(Remote(), true), "saved", previous) == previous)
            val anonymous = create(Remote(true), false, true)
            val key = value(anonymous, "identity")
            prefs.getString("anonymous", null)?.let { check(it == key) }
            check(adapters.await(anonymous.resetAnonymousIdentity(3000), 4000).isSuccess)
            val resetKey = value(anonymous, "identity")
            check(resetKey != key)
            check(value(create(Remote(true), false, true), "identity") == resetKey)

            val source = Remote()
            val writer = create(source, false)
            withTimeout(3000) { while (source.sink == null) delay(10) }
            val marker = java.util.UUID.randomUUID().toString()
            check(adapters.await(source.sink!!.submit(FullUpdate.create(listOf(
                FlagRecord.builder("saved", marker, "string", 1).build().value!!)).value!!), 3000).value!!.code == SourceUpdateCode.COMMITTED)
            check(value(create(Remote(), true), "saved", marker) == marker)
            check(adapters.await(writer.clearCache(CacheScope.NAMESPACE, 3000), 4000).isSuccess)
            check(writer.stringVariation("saved", "missing") == marker)
            check(value(create(Remote(true), false, true), "identity") == resetKey)
            // Fresh post-clear commit repopulates storage. Leave it for the next process launch.
            source.sink!!.submit(FullUpdate.create(listOf(FlagRecord.builder("saved", marker, "string", 1).build().value!!)).value!!)
            check(value(create(Remote(), true), "saved", marker) == marker)
            check(prefs.edit().putString("cache", marker).putString("anonymous", resetKey).commit())
            android.util.Log.i("FeatBitConsumer", "PHASE3_PASS previousProcessData=${previous != null} cache=true anonymous=true clear=true")
        } finally { clients.forEach { adapters.await(it.close(), 3000) } }
    }
}
