package org.jetbrains.ktor.application

import org.jetbrains.ktor.content.*
import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.nio.*
import org.jetbrains.ktor.util.*
import java.io.*

/**
 * Represents server's response
 */
interface ApplicationResponse {
    val headers: ResponseHeaders
    val cookies: ResponseCookies

    fun status(): HttpStatusCode?
    fun status(value: HttpStatusCode)

    fun channel(): AsyncWriteChannel
    fun interceptChannel(handler: (() -> AsyncWriteChannel) -> AsyncWriteChannel)
}

fun ApplicationCall.respondWrite(body: Writer.() -> Unit) : Nothing = respond(object : FinalContent.StreamConsumer() {
    override val headers: ValuesMap
        get() = ValuesMap.Empty

    override fun stream(out: OutputStream) {
        out.writer().use { writer ->
            writer.body()
            writer.flush()
        }
    }
})