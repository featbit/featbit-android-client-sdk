package co.featbit.android.internal

import co.featbit.android.api.*
import co.featbit.android.datasource.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

public class SyncProtocolTest {
    @Test
    public fun tokenMatchesReferenceEncodingAndPathsKeepPrefix() {
        assertEquals("QQWBSabBXQQQQQQQQQQQcdef", SyncProtocol.token("abcdef==", 1700000000000, 0.0))
        val options =
            ClientOptions.builder()
                .user(user("A"))
                .sdkKey("abcdef==")
                .streamingUrl("wss://example.test/deploy/")
                .disableEvents(true)
                .build()
                .value!!
        val request = SyncProtocol.request(options, true, 0, user("A"), 1700000000000, 0.0)
        assertEquals("/deploy/streaming", request.url.encodedPath)
        assertEquals("client", request.url.queryParameter("type"))
        assertEquals("QQWBSabBXQQQQQQQQQQQcdef", request.url.queryParameter("token"))
    }

    @Test
    public fun attributesPreserveNullOmittedEmptyAndStringValues() {
        val input =
            User.builder("A")
                .name("name")
                .attribute("null", AttributeValue.nullValue())
                .attribute("omitted", AttributeValue.omittedValue())
                .attribute("empty", AttributeValue.text("").value!!)
                .attribute("number", AttributeValue.text("30").value!!)
                .build()
                .value!!
        val attrs = SyncProtocol.user(input)["customizedProperties"]!!.jsonArray
        assertEquals(JsonNull, attrs[0].jsonObject["value"])
        assertFalse(attrs[1].jsonObject.containsKey("value"))
        assertEquals("", attrs[2].jsonObject["value"]!!.jsonPrimitive.content)
        assertTrue(attrs[3].jsonObject["value"]!!.jsonPrimitive.isString)
    }

    @Test
    public fun invalidRecordsSkipIndependentlyButDuplicateKeysRejectEnvelope() {
        val raw =
            """{"messageType":"data-sync","data":{"eventType":"full","userKeyId":"A","featureFlags":[{"id":"ok","variation":"v","variationType":"string","timestamp":9223372036854775807},{"id":"bad","variation":7,"timestamp":9}]}}"""
        val decoded = SyncProtocol.decode(raw, "A") as WireMessage.Update
        assertEquals(1, decoded.skipped)
        val records = (decoded.update as FullUpdate).records
        assertEquals(Long.MAX_VALUE, records.single().timestamp)
        assertSame(WireMessage.Invalid, SyncProtocol.decode(raw.replace("\"bad\"", "\"ok\""), "A"))
        assertSame(WireMessage.Ignored, SyncProtocol.decode(raw, "B"))
    }

    @Test
    public fun malformedEnvelopeUnknownMessagesAndOptionalMetadata() {
        assertSame(WireMessage.Invalid, SyncProtocol.decode("{", "A"))
        assertSame(WireMessage.Ignored, SyncProtocol.decode("""{"messageType":"future"}""", "A"))
        assertSame(WireMessage.Invalid, SyncProtocol.decode(envelope(kind = "other"), "A"))
        val raw =
            envelope()
                .replace(
                    "\"timestamp\":1",
                    "\"timestamp\":1,\"matchReason\":\"flag archived\",\"variationOptions\":[{\"id\":3}]",
                )
        val record =
            ((SyncProtocol.decode(raw, "A") as WireMessage.Update).update as FullUpdate)
                .records
                .single()
        assertTrue(record.archived)
        assertNull(record.variationOptions)
        val fractional = envelope().replace("\"timestamp\":1", "\"timestamp\":1.5")
        assertEquals(1, (SyncProtocol.decode(fractional, "A") as WireMessage.Update).skipped)
        assertSame(
            WireMessage.Invalid,
            SyncProtocol.decode("[".repeat(65) + "0" + "]".repeat(65), "A"),
        )
    }

    @Test
    public fun retryAfterParsesSecondsAndHttpDateWithoutOverflow() {
        assertEquals(120_000L, SyncProtocol.retryAfter("120", 0))
        assertEquals(Long.MAX_VALUE, SyncProtocol.retryAfter(Long.MAX_VALUE.toString(), 0))
        assertEquals(1000L, SyncProtocol.retryAfter("Thu, 01 Jan 1970 00:00:01 GMT", 0))
        assertNull(SyncProtocol.retryAfter("nonsense", 0))
        assertNull(SyncProtocol.retryAfter("-1", 0))
        assertTrue(SyncProtocol.terminal(401))
        assertTrue(SyncProtocol.terminal(403))
        assertFalse(SyncProtocol.terminal(429))
        assertFalse(SyncProtocol.terminal(503))
    }
}
