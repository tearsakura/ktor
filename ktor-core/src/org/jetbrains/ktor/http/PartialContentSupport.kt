package org.jetbrains.ktor.http

import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.content.*
import org.jetbrains.ktor.features.*
import org.jetbrains.ktor.nio.*
import org.jetbrains.ktor.pipeline.*
import org.jetbrains.ktor.util.*
import kotlin.properties.*

object PartialContentSupport : ApplicationFeature<PartialContentSupport.Configuration> {
    override val name = "Ranges support"
    override val key: AttributeKey<Configuration> = AttributeKey(name)

    class Configuration {
        var maxRangeCount: Int by Delegates.vetoable(10) { p, old, new ->
            new <= 0 || throw IllegalArgumentException("Bad maxRangeCount value $new")
        }
    }

    override fun install(application: Application, configure: Configuration.() -> Unit): Configuration {
        val config = Configuration()
        configure(config)

        application.intercept(0) { requestNext ->
            val rangeSpecifier = call.request.ranges()
            if (rangeSpecifier != null) {
                if (call.isGetOrHead()) {
                    call.attributes.put(CompressionAttributes.preventCompression, true)
                    call.interceptRespond(0) { obj ->
                        if (obj is ChannelContentProvider && obj !is RangeChannelProvider && obj is Resource) {
                            @Suppress("UNCHECKED_CAST")
                            val newContext = this as PipelineContext<ChannelContentProvider>

                            obj.contentLength?.let { length -> newContext.tryProcessRange(call, rangeSpecifier, length, config) }
                        }
                    }
                } else {
                    call.respond(HttpStatusCode.MethodNotAllowed.description("Method ${call.request.httpMethod.value} is not allowed with range request"))
                }
            } else {
                call.interceptRespond(0) { obj ->
                    if (obj is ChannelContentProvider && obj !is RangeChannelProvider && obj is Resource) {
                        sendAcceptRanges(call)
                    }
                }
            }
        }

        return config
    }

    private fun sendAcceptRanges(call: ApplicationCall) {
        call.response.headers.append(HttpHeaders.AcceptRanges, RangeUnits.Bytes.unitToken)
    }

    private fun PipelineContext<ChannelContentProvider>.tryProcessRange(call: ApplicationCall, rangesSpecifier: RangesSpecifier, length: Long, config: Configuration): Unit {
        if (checkIfRangeHeader(call)) {
            processRange(call, rangesSpecifier, length, config)
        } else {
            sendAcceptRanges(call)
        }
    }

    private fun PipelineContext<ChannelContentProvider>.checkIfRangeHeader(call: ApplicationCall): Boolean {
        val versions = versions()
        val ifRange = call.request.header(HttpHeaders.IfRange)

        val unchanged = ifRange == null || versions.all { version ->
            when (version) {
                is EntityTagVersion -> version.etag in ifRange.parseMatchTag()
                is LastModifiedVersion -> version.lastModified <= ifRange.fromHttpDateString().toLocalDateTime()
                else -> true
            }
        }

        return unchanged
    }

    private fun PipelineContext<ChannelContentProvider>.versions() = (subject as? HasVersions)?.versions.orEmpty()

    private fun PipelineContext<ChannelContentProvider>.processRange(call: ApplicationCall, rangesSpecifier: RangesSpecifier, length: Long, config: Configuration): Nothing {
        require(length >= 0L)

        val merged = rangesSpecifier.merge(length, config.maxRangeCount)
        if (merged.isEmpty()) {
            call.response.contentRange(range = null, fullLength = length) // https://tools.ietf.org/html/rfc7233#section-4.4
            call.respond(HttpStatusCode.RequestedRangeNotSatisfiable.description("Couldn't satisfy range request $rangesSpecifier: it should comply with the restriction [0; $length)"))
        }

        sendAcceptRanges(call)
        if (call.request.httpMethod == HttpMethod.Head) {
            proceed()
        }

        val channel = subject.channel()
        onFail { channel.close() }
        onSuccess { channel.close() }

        if (merged.size != 1 && !merged.isAscending() && channel !is SeekableAsyncChannel) {
            // merge into single range for non-seekable channel
            processSingleRange(call, channel, rangesSpecifier.mergeToSingle(length)!!, length)
        }

        if (merged.size == 1) {
            processSingleRange(call, channel, merged.single(), length)
        }

        processMultiRange(call, channel, merged, length)
    }

    private fun PipelineContext<ChannelContentProvider>.processSingleRange(call: ApplicationCall, channel: AsyncReadChannel, range: LongRange, length: Long): Nothing {
        call.response.contentRange(range, fullLength = length)
        call.response.status(HttpStatusCode.PartialContent)

        if (range == 0L..length - 1) {
            // just add header but don't wrap/refork with new channel
            proceed()
        }

        call.respond(RangeChannelProvider.Single(channel, range, versions()))
    }

    private fun PipelineContext<ChannelContentProvider>.processMultiRange(call: ApplicationCall, channel: AsyncReadChannel, ranges: List<LongRange>, length: Long): Nothing {
        val boundary = "ktor-boundary-" + nextNonce()
        call.response.status(HttpStatusCode.PartialContent)
        call.attributes.put(CompressionAttributes.preventCompression, true) // multirange with compression is not supported yet
        call.response.contentType(ContentType.MultiPart.ByteRanges.withParameter("boundary", boundary))

        val contentType = (subject as? Resource)?.contentType ?: ContentType.Application.OctetStream
        call.respond(RangeChannelProvider.Multiple(channel, ranges, length, boundary, contentType, versions()))
    }

    private sealed class RangeChannelProvider : ChannelContentProvider {
        class Single(val delegate: AsyncReadChannel, val range: LongRange, override val versions: List<Version>) : RangeChannelProvider(), HasVersions {
            override fun channel() = when (delegate) {
                is SeekableAsyncChannel -> AsyncSeekAndCut(delegate, range.start, range.length, preventClose = true)
                else -> AsyncSkipAndCut(delegate, range.start, range.length, preventClose = true)
            }
        }

        class Multiple(val delegate: AsyncReadChannel, val ranges: List<LongRange>, val length: Long, val boundary: String, val contentType: ContentType, override val versions: List<Version>) : RangeChannelProvider(), HasVersions {
            override fun channel() = ByteRangesChannel.forRegular(ranges, delegate, length, boundary, contentType.toString())
        }
    }

    private fun ApplicationCall.isGetOrHead() = request.httpMethod == HttpMethod.Get || request.httpMethod == HttpMethod.Head
    private fun String.parseMatchTag() = split("\\s*,\\s*".toRegex()).map { it.removePrefix("W/") }.filter { it.isNotEmpty() }.toSet()
}