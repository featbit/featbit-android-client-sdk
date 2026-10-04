package co.featbit.android.api

/** Recoverable SDK outcomes never require exception-based control flow. */
public enum class OutcomeCode {
    SUCCESS,
    INVALID,
    SUPERSEDED,
    TIMED_OUT,
    CLOSED,
    TERMINAL_FAILURE,
    DEFERRED,
    DISABLED,
    CAPACITY_EXCEEDED,
    CLEANUP_FAILED,
    STORAGE_FAILED,
}

/** SDK-defined codes/fields only; never contains raw transport exceptions or credentials. */
public data class Diagnostic
public constructor(public val code: String, public val field: String? = null)

public class Outcome<T>
private constructor(
    public val code: OutcomeCode,
    public val value: T?,
    public val diagnostic: Diagnostic?,
) {
    public val isSuccess: Boolean
        get() = code == OutcomeCode.SUCCESS

    public companion object {
        @JvmStatic
        public fun <T : Any> success(value: T): Outcome<T> =
            Outcome(OutcomeCode.SUCCESS, value, null)

        @JvmStatic
        public fun <T> invalid(code: String, field: String? = null): Outcome<T> =
            Outcome(OutcomeCode.INVALID, null, Diagnostic(code, field))

        @JvmStatic
        public fun <T> failure(code: OutcomeCode, diagnostic: Diagnostic?): Outcome<T> =
            if (code == OutcomeCode.SUCCESS) invalid("invalid_failure_code")
            else Outcome(code, null, diagnostic)
    }
}
