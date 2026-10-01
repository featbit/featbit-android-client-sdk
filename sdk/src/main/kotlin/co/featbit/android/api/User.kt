package co.featbit.android.api

import java.util.Collections

/** Absence of a property differs from a property with an omitted or null wire value. */
public class AttributeValue private constructor(public val kind: Kind, public val text: String?) {
    public enum class Kind { TEXT, NULL, OMITTED }
    public companion object {
        @JvmStatic public fun text(value: String?): Outcome<AttributeValue> =
            if (value == null) Outcome.invalid("null_attribute_text") else Outcome.success(AttributeValue(Kind.TEXT, value))
        @JvmStatic public fun nullValue(): AttributeValue = AttributeValue(Kind.NULL, null)
        @JvmStatic public fun omittedValue(): AttributeValue = AttributeValue(Kind.OMITTED, null)
    }
}

public class User private constructor(
    public val key: String,
    public val name: String,
    public val attributes: Map<String, AttributeValue>,
) {
    public class Builder public constructor(private val key: String?) {
        private var name: String? = ""
        private val attributes = LinkedHashMap<String, AttributeValue>()
        private var error: String? = null
        public fun name(value: String?): Builder = apply { name = value }
        public fun attribute(name: String?, value: AttributeValue?): Builder = apply {
            if (name.isNullOrEmpty() || name == "keyId" || name == "name" || value == null || attributes.containsKey(name)) {
                error = "invalid_or_duplicate_attribute"
            } else attributes[name] = value
        }
        public fun build(): Outcome<User> {
            if (key.isNullOrEmpty()) return Outcome.invalid("invalid_user_key", "key")
            if (name == null) return Outcome.invalid("invalid_user_name", "name")
            error?.let { return Outcome.invalid(it, "attributes") }
            return Outcome.success(User(key, name!!, Collections.unmodifiableMap(LinkedHashMap(attributes))))
        }
    }
    public companion object {
        @JvmStatic public fun builder(key: String?): Builder = Builder(key)
    }
}
