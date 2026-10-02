package co.featbit.android.internal

import co.featbit.android.api.*
import co.featbit.android.datasource.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal sealed class WireMessage {
    data class Update(val update: SourceUpdate, val skipped: Int) : WireMessage()
    object Ignored : WireMessage()
    object Invalid : WireMessage()
    object Ping : WireMessage()
}

/** Wire numbers stay decimal text until Long parsing; never round timestamps through Double. */
internal object SyncProtocol {
    const val PING = "{\"messageType\":\"ping\",\"data\":null}"
    private val json = Json { isLenient = false }
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun user(user: User): JsonObject = buildJsonObject {
        put("keyId", user.key); put("name", user.name)
        putJsonArray("customizedProperties") {
            user.attributes.forEach { (name, value) -> add(buildJsonObject {
                put("name", name)
                when (value.kind) {
                    AttributeValue.Kind.TEXT -> put("value", value.text!!)
                    AttributeValue.Kind.NULL -> put("value", JsonNull)
                    AttributeValue.Kind.OMITTED -> Unit
                }
            }) }
        }
    }
    fun sync(user: User, cursor: Long): String = buildJsonObject {
        put("messageType", "data-sync")
        putJsonObject("data") { put("timestamp", cursor); put("user", user(user)) }
    }.toString()
    fun token(key: String, now: Long, random: Double): String {
        val raw = key.trimEnd('=')
        require(raw.length in 2..999 && now >= 0)
        val p = maxOf((random * raw.length).toInt(), 2).coerceAtMost(raw.length)
        fun encode(value: String, width: Int) = value.padStart(width, '0').takeLast(width).map { "QBWSPHDXZU"[it - '0'] }.joinToString("")
        val time = now.toString()
        return encode(p.toString(), 3) + encode(time.length.toString(), 2) + raw.take(p) + encode(time, time.length) + raw.drop(p)
    }
    fun request(options: ClientOptions, streaming: Boolean, cursor: Long, user: User, now: Long, random: Double): Request {
        val base = requireNotNull(Endpoint.parse(if (streaming) options.streamingUrl else options.pollingUrl, streaming)).httpBase
        val url = (base.trimEnd('/') + "/").toHttpUrl().newBuilder()
            .addPathSegments(if (streaming) "streaming" else "api/public/sdk/client/latest-all")
        if (streaming) url.addQueryParameter("type", "client").addQueryParameter("token", token(options.sdkKey!!, now, random))
        else url.addQueryParameter("timestamp", cursor.toString())
        val builder = Request.Builder().url(url.build()).header("User-Agent", "FeatBit-Android-SDK/${SdkInfo.getVersion()}")
            .header("X-User-Agent", "FeatBit-Android-SDK/${SdkInfo.getVersion()}")
        options.synchronizationHeaders.forEach { (key, value) -> builder.header(key, value) }
        if (!streaming) builder.header("Authorization", options.sdkKey!!)
            .post(user(user).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        return builder.build()
    }
    fun decode(text: String, userKey: String): WireMessage { return try {
        // Guard parser recursion without imposing payload byte/record/node limits.
        var depth = 0; var quoted = false; var escape = false
        for (c in text) {
            if (quoted) { if (escape) escape = false else if (c == '\\') escape = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 64) }; '}', ']' -> depth-- }
        }
        val root = json.parseToJsonElement(text) as? JsonObject ?: return WireMessage.Invalid
        when (root["messageType"].string()) {
            "ping" -> WireMessage.Ping
            "data-sync" -> {
                val data = root["data"] as? JsonObject ?: return WireMessage.Invalid
                val key = data["userKeyId"].string() ?: return WireMessage.Invalid
                if (key != userKey) return WireMessage.Ignored
                val kind = data["eventType"].string()
                if (kind != "full" && kind != "patch") return WireMessage.Invalid
                val flags = data["featureFlags"] as? JsonArray ?: return WireMessage.Invalid
                val records = ArrayList<FlagRecord>(); val keys = HashSet<String>(); var skipped = 0
                for (item in flags) {
                    val flag = item as? JsonObject
                    val id = flag?.get("id").string()
                    if (id != null && !keys.add(id)) return WireMessage.Invalid
                    val value = flag?.get("variation").string()
                    val type = flag?.get("variationType").string()
                    val stamp = flag?.get("timestamp") as? JsonPrimitive
                    val time = stamp?.takeIf { !it.isString && it.content.matches(Regex("[0-9]+")) }?.content?.toLongOrNull()
                    if (id.isNullOrEmpty() || value == null || type == null || time == null) { skipped++; continue }
                    val reason = flag["matchReason"].string()
                    val metadata = flag["variationOptions"] as? JsonArray
                    val options = metadata?.map { option ->
                        val obj = option as? JsonObject
                        val optionId = obj?.get("id").string(); val optionValue = obj?.get("value").string()
                        if (optionId == null || optionValue == null) null else VariationOption(optionId, optionValue)
                    }
                    records.add(FlagRecord.builder(id, value, type, time).reason(reason).archived(reason == "flag archived")
                        .variationOptions(options?.takeIf { list -> list.none { it == null } }).build().value!!)
                }
                WireMessage.Update(if (kind == "full") FullUpdate.create(records).value!! else PatchUpdate.create(records).value!!, skipped)
            }
            null -> WireMessage.Invalid
            else -> WireMessage.Ignored
        }
    } catch (_: IllegalArgumentException) { WireMessage.Invalid } }

    // Authentication/authorization rejection is terminal. Other statuses are paced failures.
    fun terminal(status: Int): Boolean = status == 401 || status == 403
    fun retryAfter(value: String?, now: Long): Long? {
        if (value == null) return null
        val seconds = value.trim().toLongOrNull()
        if (seconds != null) return seconds.takeIf { it >= 0 }?.let { if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000 }
        return try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT"); isLenient = false }
            val position = java.text.ParsePosition(0)
            val date = format.parse(value, position) ?: return null
            if (position.index != value.length) null else (date.time - now).coerceAtLeast(0)
        } catch (_: IllegalArgumentException) { null }
    }
}
