package co.featbit.consumer.kotlin

import android.content.Context
import co.featbit.android.api.*
import co.featbit.android.kotlin.ClientAdapters
import co.featbit.android.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

object RuntimeSmoke {
    fun verify(context: Context, completion: (String) -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            val clients = ArrayList<FeatBitClient>()
            try {
                val adapters = ClientAdapters.getDefault()
                val user = User.builder("kotlin-runtime").name("Kotlin Runtime").build().value!!
                val data = TestDataFactory.getDefault().create(listOf(
                    BootstrapFlag.create("json", "{\"items\":[null,1]}", ValueType.JSON).value!!)).value!!
                val c = adapters.await(ClientFactory.getDefault().create(context, data.clientOptions(user).value!!), 3000).value!!
                clients.add(c)
                check(adapters.await(c.awaitReady(2000), 3000).value == ReadyResult.CUSTOM_LOCAL)
                check(c.variation("json", FbValue.jsonNull()).asObject()!!["items"]!!.asArray()!![0].kind == FbValue.Kind.NULL)
                val statusObserved = CompletableDeferred<Unit>()
                val statuses = launch(start = CoroutineStart.UNDISPATCHED) {
                    adapters.status(c).collect { statusObserved.complete(Unit) }
                }
                withTimeout(2000) { statusObserved.await() }
                check(adapters.await(data.update(BootstrapFlag.create("json", "false", ValueType.BOOLEAN).value!!), 2000).value == TestDataResult.COMMITTED)
                check(c.variation("json", FbValue.jsonNull()).asBoolean() == false)
                check(adapters.await(c.identify(User.builder("kotlin-B").name("Kotlin B").build().value!!, 2000), 3000).isSuccess)
                check(adapters.await(c.setOffline(2000), 3000).isSuccess)
                check(data.remove("json").getResult()!!.value == TestDataResult.SAVED_FOR_NEXT_START)
                check(adapters.await(c.setOnline(2000), 3000).isSuccess)
                check(adapters.await(c.awaitReady(2000), 3000).isSuccess)
                check(c.allVariations().isEmpty())
                check(adapters.await(c.close(), 3000).value!!.cleanupComplete)
                withTimeout(2000) { statuses.join() }
                val custom = adapters.await(ClientFactory.getDefault().create(context,
                    ClientOptions.builder().user(user).source(ModelSmoke.LocalFactory()).disableEvents(true).cacheEnabled(false).build().value!!), 3000).value!!
                clients.add(custom)
                check(adapters.await(custom.awaitReady(2000), 3000).value == ReadyResult.CUSTOM_LOCAL)
                check(adapters.await(custom.close(), 3000).value!!.cleanupComplete)
                probeLocalReads(context, user, adapters)
                completion("PASS · Kotlin runtime: TestData、Custom、JSON、suspend/Flow、Identify、Offline/Online、Close")
            } catch (e: Exception) {
                clients.forEach { it.close() }
                completion("FAIL: $e")
            }
        }
    }
    private suspend fun probeLocalReads(context: Context, user: User, adapters: ClientAdapters) {
        val runtime = Runtime.getRuntime()
        fun heap() = runtime.totalMemory() - runtime.freeMemory()
        val before = heap()
        val start = System.nanoTime()
        val flags = List(5000) { BootstrapFlag.create("f$it", "{\"index\":$it,\"items\":[null,true,1,\"text\"]}", ValueType.JSON).value!! }
        val data = TestDataFactory.getDefault().create(flags).value!!
        val client = adapters.await(ClientFactory.getDefault().create(context, data.clientOptions(user).value!!), 3000).value!!
        try {
            check(adapters.await(client.awaitReady(2000), 3000).isSuccess)
            val startupNs = System.nanoTime() - start
            val storedHeap = heap()
            val first = System.nanoTime()
            check(client.variation("f0", FbValue.jsonNull()).asObject() != null)
            val firstNs = System.nanoTime() - first
            val repeat = System.nanoTime()
            repeat(5000) { check(client.variation("f0", FbValue.jsonNull()).asObject() != null) }
            val repeatedNs = System.nanoTime() - repeat
            val repeatedHeap = heap()
            val eager = System.nanoTime()
            val parsed = List(5000) { client.variation("f$it", FbValue.jsonNull()) }
            val eagerNs = System.nanoTime() - eager
            val cached = System.nanoTime()
            repeat(5000) { check(parsed[0].asObject() != null) }
            val cachedNs = System.nanoTime() - cached
            android.util.Log.i("FeatBitConsumer", "ANDROID_PROBE records=5000 startupNs=$startupNs firstNs=$firstNs repeatedNs=$repeatedNs eagerNs=$eagerNs cachedNs=$cachedNs heapBefore=$before heapRaw=$storedHeap heapRepeated=$repeatedHeap heapParsed=${heap()}")
        } finally { adapters.await(client.close(), 3000) }
    }
}
