package co.featbit.android.api

public enum class LogLevel {
    NONE,
    ERROR,
    WARN,
    INFO,
    DEBUG,
}

/** Receives sanitized SDK-owned diagnostics, never raw user data or Throwables. */
public fun interface Logger {
    public fun log(level: LogLevel, diagnostic: Diagnostic): Unit
}
