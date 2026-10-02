package co.featbit.consumer.kotlin

import android.content.Context
import android.os.Build
import co.featbit.android.api.*
import co.featbit.android.datasource.*
import co.featbit.android.kotlin.ClientAdapters
import java.util.concurrent.CopyOnWriteArrayList

/** Platform-derived schema and same-key Identify checked through the public source boundary. */
object AttributeSmoke {
    suspend fun verify(context: Context) {
        val adapters = ClientAdapters.getDefault()
        val user = User.builder("attribute-user").name("Attribute User")
            .attribute("plan", AttributeValue.text("starter").value!!).build().value!!
        for (enabled in listOf(false, true)) {
            val contexts = CopyOnWriteArrayList<User>()
            val source = object : DataSourceFactory {
                override fun capabilities() = SourceCapabilities(Provenance.LOCAL, false)
                override fun validate() = Outcome.success(SourceValidation.VALID)
                override fun create(session: SourceSessionContext, sink: SourceUpdateSink): Outcome<DataSource> {
                    contexts.add(session.user)
                    return Outcome.success(object : DataSource {
                        override fun start(completion: Completion<SourceStarted>) {
                            sink.submit(FullUpdate.create(emptyList()).value!!)
                            completion.onComplete(Outcome.success(SourceStarted.STARTED))
                        }
                        override fun stop(completion: Completion<SourceStopped>) {
                            completion.onComplete(Outcome.success(SourceStopped.STOPPED))
                        }
                    })
                }
            }
            val options = ClientOptions.builder().user(user).source(source).disableEvents(true)
                .cacheEnabled(false).automaticAttributes(enabled).build().value!!
            val client = adapters.await(ClientFactory.getDefault().create(context, options), 5000).value!!
            try {
                check(adapters.await(client.awaitReady(3000), 4000).isSuccess)
                val initial = contexts.last()
                check(initial.attributes["plan"]!!.text == "starter")
                if (enabled) {
                    @Suppress("DEPRECATION")
                    val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    val expected = mapOf("applicationId" to context.packageName, "applicationVersion" to appVersion,
                        "osName" to "Android", "osVersion" to Build.VERSION.RELEASE,
                        "deviceManufacturer" to Build.MANUFACTURER, "deviceModel" to Build.MODEL,
                        "version" to BuildConfig.EXPECTED_SDK_VERSION)
                    val actual = initial.attributes.filterKeys { it.startsWith("featbit.sdk.") }
                    check(actual.keys == expected.filterValues { !it.isNullOrEmpty() }.keys.map { "featbit.sdk.$it" }.toSet())
                    expected.filterValues { !it.isNullOrEmpty() }.forEach { (key, value) ->
                        check(actual["featbit.sdk.$key"]!!.text == value)
                    }
                } else check(initial.attributes.keys.none { it.startsWith("featbit.sdk.") })
                check(adapters.await(client.identify(user, 3000), 4000).isSuccess)
                check(contexts.size == 2)
                check(contexts.last() !== initial || !enabled)
                check(initial.attributes["plan"]!!.text == "starter")
                val collision = User.builder(user.key).name(user.name)
                    .attribute("featbit.sdk.version", AttributeValue.text("caller").value!!).build().value!!
                val outcome = adapters.await(client.identify(collision, 3000), 4000)
                check(outcome.isSuccess != enabled)
                if (enabled) check(contexts.size == 2)
            } finally { check(adapters.await(client.close(), 4000).value!!.cleanupComplete) }
        }
        android.util.Log.i("FeatBitConsumer", "PHASE7_ATTRIBUTES_PASS")
    }
}
