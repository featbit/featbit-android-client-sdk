package co.featbit.android.api

public enum class ValueType {
    BOOLEAN,
    NUMBER,
    STRING,
    JSON,
}

public class BootstrapFlag
private constructor(public val key: String, public val value: String, public val type: ValueType) {
    public companion object {
        @JvmStatic
        public fun create(key: String?, value: String?, type: ValueType?): Outcome<BootstrapFlag> =
            if (key.isNullOrEmpty() || value == null || type == null)
                Outcome.invalid("invalid_bootstrap_flag")
            else Outcome.success(BootstrapFlag(key, value, type))
    }
}
