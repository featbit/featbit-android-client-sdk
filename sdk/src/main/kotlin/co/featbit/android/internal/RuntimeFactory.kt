package co.featbit.android.internal

import android.content.Context
import android.os.Build
import co.featbit.android.api.*
import co.featbit.android.datasource.SourceCapabilities

internal object RuntimeFactory : ClientFactory {
    override fun create(applicationContext: Context, options: ClientOptions): Operation<FeatBitClient> {
        val context = applicationContext.applicationContext
        val dispatch = mainDispatch()
        val result = ResultOperation<FeatBitClient>(dispatch, CallbackBudget(), AndroidClock)
        if (context == null) { result.settle(Outcome.invalid("application_context_required")); return result }
        if (Limits.bootstrap(options.bootstrap ?: emptyList()) == null) {
            result.settle(Outcome.invalid("input_resource_limit")); return result
        }
        if (!options.offline) onlineError(options)?.let {
            result.settle(Outcome.failure(it.first, Diagnostic(it.second))); return result
        }
        if (options.initialUser == null) {
            result.settle(Outcome.failure(OutcomeCode.DISABLED, Diagnostic("anonymous_persistence_unavailable"))); return result
        }
        val testData = options.source as? LocalTestData
        if (testData != null && (!options.disableEvents || options.cacheEnabled)) {
            result.settle(Outcome.invalid("test_data_configuration_conflict")); return result
        }
        val binding = Any()
        if (testData != null && !testData.bind(binding)) {
            result.settle(Outcome.invalid("test_data_already_bound")); return result
        }
        val workers = BoundedWorkers()
        val ticker = DeadlineTicker()
        val diagnostics = Diagnostics(options, AndroidClock)
        val deadline = AndroidClock.elapsed() + options.startupWaitMillis
        val lock = Any()
        var settled = false
        fun failed(outcome: Outcome<FeatBitClient>) {
            synchronized(lock) { if (settled) return; settled = true; result.settle(outcome) }
            testData?.unbind(binding); workers.close(); ticker.close(); diagnostics.close()
        }
        ticker.start { if (AndroidClock.elapsed() >= deadline) failed(Outcome.failure(OutcomeCode.TIMED_OUT, Diagnostic("creation_deadline"))) }
        workers.execute {
            try {
                val source = options.source
                val caps: SourceCapabilities? = source?.capabilities()
                val validation = source?.validate()
                if (validation != null && !validation.isSuccess) {
                    failed(Outcome.invalid("custom_validation_failed")); return@execute
                }
                val enrich: (User) -> Outcome<User> = { enrich(context, options, it) }
                val user = enrich(options.initialUser)
                if (!user.isSuccess) { failed(Outcome.failure(user.code, user.diagnostic)); return@execute }
                if (AndroidClock.elapsed() >= deadline) { failed(Outcome.failure(OutcomeCode.TIMED_OUT, Diagnostic("creation_deadline"))); return@execute }
                synchronized(lock) {
                    if (settled) return@execute
                    ticker.close()
                    val client = LocalClient(options, user.value!!, caps, dispatch, AndroidClock, workers,
                        DeadlineTicker(), diagnostics, enrich = enrich, releaseBinding = { testData?.unbind(binding) })
                    client.start()
                    settled = true
                    result.settle(Outcome.success(client))
                }
            } catch (_: Exception) { failed(Outcome.invalid("client_creation_failed")) }
        }
        return result
    }
    private fun enrich(context: Context, options: ClientOptions, user: User): Outcome<User> {
        if (!options.automaticAttributes) return Outcome.success(user)
        if (user.attributes.keys.any { it.startsWith("featbit.sdk.") }) return Outcome.invalid("reserved_attribute_prefix")
        val builder = User.builder(user.key).name(user.name)
        user.attributes.forEach { (key, value) -> builder.attribute(key, value) }
        @Suppress("DEPRECATION")
        val version = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { null }
        val values = mapOf("applicationId" to context.packageName, "applicationVersion" to version,
            "osName" to "Android", "osVersion" to Build.VERSION.RELEASE, "deviceManufacturer" to Build.MANUFACTURER,
            "deviceModel" to Build.MODEL, "version" to SdkInfo.getVersion())
        values.forEach { (key, value) -> if (!value.isNullOrEmpty()) builder.attribute("featbit.sdk.$key", AttributeValue.text(value).value!!) }
        return builder.build()
    }
}
