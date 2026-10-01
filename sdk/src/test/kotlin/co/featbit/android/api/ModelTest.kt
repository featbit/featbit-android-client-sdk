package co.featbit.android.api

import co.featbit.android.datasource.*
import org.junit.Assert.*
import org.junit.Test

public class ModelTest {
    @Test public fun jsonValuesCopyContainersAndPreserveNull() {
        val source = mutableListOf<FbValue?>(FbValue.jsonNull(), FbValue.ofBoolean(true))
        val value = FbValue.ofArray(source).value!!
        source.clear()
        assertEquals(2, value.asArray()!!.size)
        assertEquals(FbValue.Kind.NULL, value.asArray()!![0].kind)
        assertNull(value.asBoolean())
        val map = linkedMapOf<String?, FbValue?>("nested" to value)
        val obj = FbValue.ofObject(map).value!!
        map.clear()
        assertEquals(value, obj.asObject()!!["nested"])
        try { (value.asArray() as MutableList).clear(); fail("mutable result") } catch (_: UnsupportedOperationException) { }
        try { (obj.asObject() as MutableMap).clear(); fail("mutable result") } catch (_: UnsupportedOperationException) { }
    }
    @Test public fun invalidValuesUseOrdinaryResults() {
        assertFalse(FbValue.ofNumber(Double.POSITIVE_INFINITY).isSuccess)
        assertFalse(FbValue.ofNumber(Double.NaN).isSuccess)
        assertFalse(FbValue.ofString(null).isSuccess)
        assertFalse(FbValue.ofArray(listOf(null)).isSuccess)
        assertFalse(FbValue.ofObject(mapOf(null to FbValue.jsonNull())).isSuccess)
        assertTrue(FbValue.ofNumber(Double.MAX_VALUE).isSuccess)
    }
    @Test public fun usersFreezeBuilderAndPreserveAttributePresence() {
        val builder = User.builder("user").attribute("n", AttributeValue.nullValue()).attribute("o", AttributeValue.omittedValue())
        val user = builder.build().value!!
        builder.attribute("later", AttributeValue.nullValue())
        assertFalse(user.attributes.containsKey("later"))
        assertEquals(AttributeValue.Kind.NULL, user.attributes["n"]!!.kind)
        assertEquals(AttributeValue.Kind.OMITTED, user.attributes["o"]!!.kind)
        assertFalse(builder.attribute("n", AttributeValue.nullValue()).build().isSuccess)
        assertFalse(User.builder("").build().isSuccess)
    }
    @Test public fun offlineExemptsEndpointsAndBootstrapDistinguishesEmptyFromAbsent() {
        val user = User.builder("u").build().value!!
        val builder = ClientOptions.builder().user(user).offline(true)
        assertNull(builder.build().value!!.bootstrap)
        assertEquals(emptyList<BootstrapFlag>(), builder.bootstrap(emptyList()).build().value!!.bootstrap)
        assertFalse(builder.offline(false).build().isSuccess)
    }
    @Test public fun configurationCopiesCollectionsAndRejectsConflictsWithoutLeakingSecrets() {
        val user = User.builder("u").build().value!!
        val flags = mutableListOf<BootstrapFlag?>(BootstrapFlag.create("f", "true", ValueType.BOOLEAN).value!!)
        val builder = ClientOptions.builder().user(user).offline(true).bootstrap(flags).eventHeader("Gateway", "secret")
        val options = builder.build().value!!
        flags.clear()
        builder.eventHeader("Second", "secret")
        assertEquals(1, options.bootstrap!!.size)
        assertEquals(1, options.eventHeaders.size)
        val rejected = builder.eventHeader("Authorization", "credential").build()
        assertFalse(rejected.isSuccess)
        assertFalse(rejected.diagnostic.toString().contains("credential"))
        assertFalse(ClientOptions.builder().user(user).offline(true).eventHeader("x-test", "a").eventHeader("X-Test", "b").build().isSuccess)
    }
    @Test public fun customUpdatesFreezeRecordsAndRejectAmbiguousKeys() {
        val record = FlagRecord.builder("f", "true", "boolean", 1).build().value!!
        val records = mutableListOf<FlagRecord?>(record)
        val update = FullUpdate.create(records).value!!
        records.clear()
        assertEquals(1, update.records.size)
        assertFalse(PatchUpdate.create(listOf(record, record)).isSuccess)
        assertTrue(FullUpdate.create(emptyList()).isSuccess)
        assertFalse(FlagRecord.builder("f", "x", "future-type", -1).build().isSuccess)
    }
    @Test public fun onlineValidationRequiresOnlyEnabledPaths() {
        val user = User.builder("u").build().value!!
        val builder = ClientOptions.builder().user(user).sdkKey("test-key")
            .streamingUrl("wss://example.test/prefix/streaming").disableEvents(true)
        assertTrue(builder.build().isSuccess)
        assertFalse(builder.backgroundPolling(true).build().isSuccess)
        assertTrue(builder.pollingUrl("https://example.test/prefix/latest-all").build().isSuccess)
        assertFalse(builder.disableEvents(false).build().isSuccess)
        assertTrue(builder.eventsUrl("https://example.test/prefix/track").build().isSuccess)
        assertFalse(builder.eventsUrl("https://user:secret@example.test/track").build().isSuccess)
    }
    @Test public fun invalidLimitsAndReservedAttributesRemainInvalidOffline() {
        val user = User.builder("u").attribute("featbit.sdk.version", AttributeValue.text("caller").value!!).build().value!!
        val builder = ClientOptions.builder().user(user).offline(true)
        assertTrue(builder.build().isSuccess)
        assertFalse(builder.automaticAttributes(true).build().isSuccess)
        assertFalse(builder.automaticAttributes(false).eventCapacity(0).build().isSuccess)
        assertTrue(builder.eventCapacity(1).flagGraceMillis(30_000).build().isSuccess)
        assertFalse(builder.flagGraceMillis(30_001).build().isSuccess)
        assertFalse(builder.flagGraceMillis(0).requestTimeoutMillis(0).build().isSuccess)
    }
}
