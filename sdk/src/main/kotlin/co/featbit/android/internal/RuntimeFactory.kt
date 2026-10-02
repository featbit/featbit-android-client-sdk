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
        if (BootstrapRecords.create(options.bootstrap ?: emptyList()) == null) {
            result.settle(Outcome.invalid("duplicate_flag_key")); return result
        }
        if (!options.offline) onlineError(options)?.let {
            result.settle(Outcome.failure(it.first, Diagnostic(it.second))); return result
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
        val deadline = AndroidClock.deadlineAfter(options.startupWaitMillis)
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
                val root = try { context.noBackupFilesDir } catch (_: Exception) { null }
                val anonymous = if (options.anonymousEnabled && root != null) PersistenceRegistry.anonymous(root) else null
                val cache = if (root != null) cacheNamespace(options, caps)?.let { PersistenceRegistry.cache(root, it) } else null
                if (root == null && options.cacheEnabled) diagnostics.report("cache_storage_unavailable")
                fun complete(initial: User, identity: AnonymousIdentity? = null) {
                    val user = enrich(initial)
                    if (!user.isSuccess) { failed(Outcome.failure(user.code, user.diagnostic)); return }
                    synchronized(lock) {
                        if (settled) return
                        if (AndroidClock.elapsed() >= deadline) { failed(Outcome.failure(OutcomeCode.TIMED_OUT, Diagnostic("creation_deadline"))); return }
                        fun publish() {
                            ticker.close()
                            val client = LocalClient(options, user.value!!, caps, dispatch, AndroidClock, workers,
                                DeadlineTicker(), diagnostics, anonymous, enrich, { testData?.unbind(binding) }, cache)
                            client.start()
                            settled = true
                            result.settle(Outcome.success(client))
                        }
                        if (identity == null) publish()
                        else if (anonymous?.withCurrent(identity, ::publish) != true)
                            failed(Outcome.failure(OutcomeCode.SUPERSEDED, Diagnostic("anonymous_revision_changed")))
                    }
                }
                if (options.initialUser != null) complete(options.initialUser)
                else if (anonymous == null) failed(Outcome.failure(OutcomeCode.STORAGE_FAILED, Diagnostic("anonymous_storage_unavailable")))
                else anonymous.prepare(false) { identity ->
                    if (!identity.isSuccess) failed(Outcome.failure(identity.code, identity.diagnostic))
                    else try { complete(User.builder(identity.value!!.key).name("Anonymous").build().value!!, identity.value) }
                    catch (_: Exception) { failed(Outcome.failure(OutcomeCode.STORAGE_FAILED, Diagnostic("anonymous_creation_failed"))) }
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
