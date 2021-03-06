package org.jetbrains.ktor.netty

import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.HttpHeaders
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.interception.*
import org.jetbrains.ktor.nio.*
import java.util.concurrent.atomic.*

internal class NettyApplicationResponse(val request: HttpRequest, val response: HttpResponse, val context: ChannelHandlerContext) : BaseApplicationResponse() {
    @Volatile
    private var commited = false
    private val closed = AtomicBoolean(false)

    override fun setStatus(statusCode: HttpStatusCode) {
        response.status = HttpResponseStatus(statusCode.value, statusCode.description)
    }

    private val channelInstance = lazy {
        context.executeInLoop {
            setChunked()
            sendRequestMessage()
        }

        NettyAsyncWriteChannel(request, this, context)
    }

    override val channel = Interceptable0<AsyncWriteChannel> { channelInstance.value }

    override val headers: ResponseHeaders = object : ResponseHeaders() {
        override fun hostAppendHeader(name: String, value: String) {
            if (commited)
                throw UnsupportedOperationException("Headers can no longer be set because response was already completed")
            response.headers().add(name, value)
        }
        override fun getHostHeaderNames(): List<String> = response.headers().map { it.key }
        override fun getHostHeaderValues(name: String): List<String> = response.headers().getAll(name) ?: emptyList()
    }


    fun sendRequestMessage(): ChannelFuture? {
        if (!commited) {
            val f = context.writeAndFlush(response)
            commited = true
            return f
        }
        return null
    }

    fun finalize() {
        context.executeInLoop {
            sendRequestMessage()
            context.flush()
            if (closed.compareAndSet(false, true)) {
                context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).scheduleClose()
            }
            context.channel().config().isAutoRead = true
            context.read()
            if (channelInstance.isInitialized()) {
                channelInstance.value.close()
            }
        }
    }

    private fun ChannelFuture.scheduleClose() {
        if (!HttpHeaders.isKeepAlive(request)) {
            addListener(ChannelFutureListener.CLOSE)
        }
    }

    private fun setChunked() {
        if (commited) {
            if (!response.headers().contains(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED, true)) {
                throw IllegalStateException("Already commited")
            }
        }
        if (response.status.code() != HttpStatusCode.SwitchingProtocols.value) {
            HttpHeaders.setTransferEncodingChunked(response)
        }
    }
}