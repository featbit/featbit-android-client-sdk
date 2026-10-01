package co.featbit.android.api

import java.util.Collections

/** Immutable JSON-compatible value. Accessors return null for the wrong kind. */
public sealed class FbValue {
    public enum class Kind { BOOLEAN, NUMBER, STRING, ARRAY, OBJECT, NULL }
    public abstract val kind: Kind
    public fun asBoolean(): Boolean? = (this as? BooleanValue)?.value
    public fun asNumber(): Double? = (this as? NumberValue)?.value
    public fun asString(): String? = (this as? StringValue)?.value
    public fun asArray(): List<FbValue>? = (this as? ArrayValue)?.value
    public fun asObject(): Map<String, FbValue>? = (this as? ObjectValue)?.value

    private data class BooleanValue(val value: Boolean) : FbValue() { override val kind = Kind.BOOLEAN }
    private data class NumberValue(val value: Double) : FbValue() { override val kind = Kind.NUMBER }
    private data class StringValue(val value: String) : FbValue() { override val kind = Kind.STRING }
    private data class ArrayValue(val value: List<FbValue>) : FbValue() { override val kind = Kind.ARRAY }
    private data class ObjectValue(val value: Map<String, FbValue>) : FbValue() { override val kind = Kind.OBJECT }
    private object NullValue : FbValue() { override val kind = Kind.NULL }

    public companion object {
        @JvmStatic public fun ofBoolean(value: Boolean): FbValue = BooleanValue(value)
        @JvmStatic public fun ofNumber(value: Double): Outcome<FbValue> =
            if (value.isFinite()) Outcome.success(NumberValue(value)) else Outcome.invalid("non_finite_number")
        @JvmStatic public fun ofString(value: String?): Outcome<FbValue> =
            if (value != null) Outcome.success(StringValue(value)) else Outcome.invalid("null_string")
        @JvmStatic public fun jsonNull(): FbValue = NullValue
        @JvmStatic public fun ofArray(values: List<FbValue?>?): Outcome<FbValue> {
            if (values == null || values.any { it == null }) return Outcome.invalid("invalid_array")
            return Outcome.success(ArrayValue(Collections.unmodifiableList(values.filterNotNull())))
        }
        @JvmStatic public fun ofObject(values: Map<String?, FbValue?>?): Outcome<FbValue> {
            if (values == null || values.any { it.key == null || it.value == null }) return Outcome.invalid("invalid_object")
            val copy = LinkedHashMap<String, FbValue>()
            values.forEach { (key, value) -> copy[key!!] = value!! }
            return Outcome.success(ObjectValue(Collections.unmodifiableMap(copy)))
        }
    }
}
