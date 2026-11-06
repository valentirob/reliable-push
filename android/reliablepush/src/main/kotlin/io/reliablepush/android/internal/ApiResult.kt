package io.reliablepush.android.internal

/** Outcome of one call to the device API. */
internal sealed interface ApiResult<out T> {
    /** The expected success status, with the parsed value. */
    data class Ok<T>(val value: T) : ApiResult<T>

    /** Any other HTTP status (including an unfollowed 3xx). [retryAfterSeconds] is the `Retry-After` header, if numeric. */
    data class Http(val status: Int, val retryAfterSeconds: Long?) : ApiResult<Nothing>

    /** I/O failure, timeout, or a success response whose body could not be understood. */
    data object NetworkError : ApiResult<Nothing>
}
