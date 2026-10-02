package co.featbit.android.internal

import co.featbit.android.api.*
import co.featbit.android.datasource.FlagRecord
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Target service contract. No experiment sampling or retired sendToExperiment field. */
internal object EventProtocol {
    fun user(options: ClientOptions, user: User): JsonObject {
        val attributes = user.attributes.filterKeys { !options.allAttributesPrivate && it !in options.privateAttributes }.toSortedMap()
        return buildJsonObject {
            put("keyId", user.key); put("name", user.name)
            putJsonArray("customizedProperties") {
                attributes.forEach { (name, value) -> add(buildJsonObject {
                    put("name", name)
                    when (value.kind) {
                        AttributeValue.Kind.TEXT -> put("value", value.text!!)
                        AttributeValue.Kind.NULL -> put("value", JsonNull)
                        AttributeValue.Kind.OMITTED -> Unit
                    }
                }) }
            }
        }
    }
    fun metric(name: String, value: Double): JsonObject = buildJsonObject {
        put("type", "CustomEvent"); put("appType", "Android"); put("eventName", name)
        put("numericValue", if (value == 0.0) 0.0 else value)
    }
    fun evaluation(record: FlagRecord): JsonObject? {
        val options = record.variationOptions ?: return null
        if (options.isEmpty() || options.map { it.id }.distinct().size != options.size) return null
        val selected = options.singleOrNull { it.value == record.variation } ?: return null
        return buildJsonObject {
            put("featureFlagKey", record.key)
            putJsonObject("variation") { put("id", selected.id); put("value", selected.value) }
        }
    }
    fun payload(user: JsonObject, kind: String, event: JsonObject, timestamp: Long?): String = buildJsonObject {
        put("user", user)
        putJsonArray(kind) { add(if (timestamp == null) event else JsonObject(event + ("timestamp" to JsonPrimitive(timestamp)))) }
    }.toString()
    fun request(options: ClientOptions, payload: String): Request {
        val base = requireNotNull(Endpoint.parse(options.eventsUrl, false)).httpBase
        val builder = Request.Builder().url(base.trimEnd('/') + "/api/public/insight/track")
            .header("Authorization", options.sdkKey!!)
            .header("User-Agent", "FeatBit-Android-SDK/${SdkInfo.getVersion()}")
            .header("X-User-Agent", "FeatBit-Android-SDK/${SdkInfo.getVersion()}")
        options.eventHeaders.forEach { (key, value) -> builder.header(key, value) }
        return builder.post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    }
}
