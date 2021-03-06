package org.jetbrains.ktor.http

import org.jetbrains.ktor.application.*
import java.time.*
import java.util.*

enum class ConditionalHeaderCheckResult(val statusCode: HttpStatusCode) {
    OK(HttpStatusCode.OK),
    NOT_MODIFIED(HttpStatusCode.NotModified),
    PRECONDITION_FAILED(HttpStatusCode.PreconditionFailed)
}

/**
 * Checks current [etag] value and pass it through conditions supplied by the remote client. Depends on conditions it
 * produces return value of enum type [ConditionalHeaderCheckResult]
 *
 * It never handles If-None-Match: *  as it is related to non-etag logic (for example, Last modified checks).
 * See http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.26 for more details
 *
 * @param etag - current entity tag, for example file's content hash
 * @return [ConditionalHeaderCheckResult.OK] if all headers pass or there was no related headers,
 *      [ConditionalHeaderCheckResult.NOT_MODIFIED] for successful If-None-Match,
 *      [ConditionalHeaderCheckResult.PRECONDITION_FAILED] for failed If-Match
 */
fun ApplicationCall.checkEtag(etag: String): ConditionalHeaderCheckResult {
    val givenNoneMatchEtags = request.header(HttpHeaders.IfNoneMatch)?.parseMatchTag()
    val givenMatchEtags = request.header(HttpHeaders.IfMatch)?.parseMatchTag()

    if (givenNoneMatchEtags != null && etag in givenNoneMatchEtags && "*" !in givenNoneMatchEtags) {
        return ConditionalHeaderCheckResult.NOT_MODIFIED
    }

    if (givenMatchEtags != null && givenMatchEtags.isNotEmpty() && etag !in givenMatchEtags && "*" !in givenMatchEtags) {
        return ConditionalHeaderCheckResult.PRECONDITION_FAILED
    }

    return ConditionalHeaderCheckResult.OK
}

/**
 * Checks current [etag] value and pass it through conditions supplied by the remote client. Depends on conditions it
 * produces 410 Precondition Failed or 304 Not modified responses when necessary.
 * Otherwise sets ETag header and delegates to the [block] function
 *
 * It never handles If-None-Match: *  as it is related to non-etag logic (for example, Last modified checks).
 * See http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.26 for more details
 */
fun <R> ApplicationCall.withETag(etag: String, putHeader: Boolean = true, block: () -> R): R {
    val result = checkEtag(etag)

    if (putHeader) {
        response.header(HttpHeaders.ETag, etag)
    }

    return when (result) {
        ConditionalHeaderCheckResult.NOT_MODIFIED,
        ConditionalHeaderCheckResult.PRECONDITION_FAILED -> {
            respond(result.statusCode)
        }
        ConditionalHeaderCheckResult.OK -> block()
    }
}

fun <R> ApplicationCall.withLastModified(lastModified: Date, putHeader: Boolean = true, block: () -> R): R {
    return withLastModified(LocalDateTime.ofInstant(lastModified.toInstant(), ZoneId.systemDefault()), putHeader, block)
}

fun <R> ApplicationCall.withLastModified(lastModified: ZonedDateTime, putHeader: Boolean = true, block: () -> R): R {
    return withLastModified(lastModified.toLocalDateTime(), putHeader, block)
}

/**
 * The function passes the given [lastModified] date through the client provided
 *  http conditional headers If-Modified-Since and If-Unmodified-Since.
 *
 * Notice the second precision so it may work wrong if there were few changes during the same second.
 *
 * For better behaviour use etag instead
 *
 * See https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.28 and
 *  https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.25
 *
 *  @param lastModified of the current content, for example file's last modified date
 *  @return [ConditionalHeaderCheckResult.OK] if all header pass or there was no headers in the request,
 *      [ConditionalHeaderCheckResult.NOT_MODIFIED] for If-Modified-Since,
 *      [ConditionalHeaderCheckResult.PRECONDITION_FAILED] for If-Unmodified*Since
 */
fun ApplicationCall.checkLastModified(lastModified: LocalDateTime): ConditionalHeaderCheckResult {
    val normalized = lastModified.withNano(0) // we need this because of the http date format that only has seconds
    val ifModifiedSince = request.headers[HttpHeaders.IfModifiedSince]?.let { it.fromHttpDateString().toLocalDateTime() }
    val ifUnmodifiedSince = request.headers[HttpHeaders.IfUnmodifiedSince]?.let { it.fromHttpDateString().toLocalDateTime() }

    if (ifModifiedSince != null) {
        if (normalized <= ifModifiedSince) {
            return ConditionalHeaderCheckResult.NOT_MODIFIED
        }
    }
    if (ifUnmodifiedSince != null) {
        if (normalized > ifUnmodifiedSince) {
            return ConditionalHeaderCheckResult.PRECONDITION_FAILED
        }
    }

    return ConditionalHeaderCheckResult.OK
}

/**
 * The function passes the given [lastModified] date through the client provided
 *  http conditional headers If-Modified-Since and If-Unmodified-Since. Depends on conditions it
 * produces 410 Precondition Failed or 304 Not modified responses when necessary.
 * Otherwise sets ETag header and delegates to the [block] function.
 *
 * Notice the second precision so it may work wrong if there were few changes during the same second.
 *
 * For better behaviour use etag instead
 *
 * See https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.28 and
 *  https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.25
 */
fun <R> ApplicationCall.withLastModified(lastModified: LocalDateTime, putHeader: Boolean = true, block: () -> R): R {
    val result = checkLastModified(lastModified)

    if (putHeader) {
        response.header(HttpHeaders.LastModified, lastModified)
    }

    return when (result) {
        ConditionalHeaderCheckResult.NOT_MODIFIED,
        ConditionalHeaderCheckResult.PRECONDITION_FAILED -> {
            respond(result.statusCode)
        }
        ConditionalHeaderCheckResult.OK -> block()
    }
}

private fun String.parseMatchTag() = split("\\s*,\\s*".toRegex()).map { it.removePrefix("W/") }.filter { it.isNotEmpty() }.toSet()
