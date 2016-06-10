package org.jetbrains.ktor.host

import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.content.*
import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.nio.*
import org.jetbrains.ktor.pipeline.*
import org.jetbrains.ktor.transform.*
import org.jetbrains.ktor.util.*
import java.io.*
import java.util.concurrent.*

abstract class BaseApplicationCall(override val application: Application, override val executor: Executor) : ApplicationCall {
    val executionMachine = PipelineMachine()
    private val state = ResponsePipelineState(this, HttpStatusCode.NotFound)
    final override val attributes = Attributes()

    override fun execute(pipeline: Pipeline<ApplicationCall>): PipelineState {
        try {
            executionMachine.execute(this, pipeline)
        } catch (e: PipelineControlFlow) {
            when (e) {
                is PipelineCompleted -> return PipelineState.Succeeded
                is PipelinePaused -> return PipelineState.Executing
                else -> throw e
            }
        }
    }

    override fun <T : Any> fork(value: T, pipeline: Pipeline<T>): Nothing = executionMachine.execute(value, pipeline)
    override fun respond(message: Any): Nothing {
        state.obj = message
        executionMachine.execute(state, respond)
    }

    protected fun commit(o: FinalContent) {
        o.status?.let { response.status(it) } ?: response.status() ?: response.status(HttpStatusCode.OK)
        for ((name, value) in o.headers.flattenEntries()) {
            response.header(name, value)
        }
    }

    final override val transform by lazy { application.attributes[TransformationSupport.key].copy() }

    final override val respond = RespondPipeline()
    private val HostRespondPhase = PipelinePhase("HostRespondPhase")

    init {
        respond.phases.insertAfter(RespondPipeline.After, HostRespondPhase)

        respond.intercept(HostRespondPhase) { state ->
            val value = state.obj

            when (value) {
                is FinalContent.StreamConsumer -> {
                    val pipe = AsyncPipe()
                    closeAtEnd(pipe)

                    // note: it is very important to resend it here rather than just use value.startContent
                    respond(PipeResponse(pipe, { value.headers }) {
                        executor.execute {
                            try {
                                value.stream(pipe.asOutputStream())
                            } finally {
                                pipe.close()
                            }
                        }
                    })
                }
                is FinalContent.ProtocolUpgrade -> {
                    commit(value)
                    value.upgrade(this@BaseApplicationCall, this, request.content.get(), response.channel())
                    pause()
                }
                is URIFileContent -> { // TODO it should be better place for that purpose
                    if (value.uri.scheme == "file") {
                        respond(LocalFileContent(File(value.uri)))
                    } else {
                        commit(value)
                        value.startContent(this@BaseApplicationCall, this)
                    }
                }
                is FinalContent -> {
                    commit(value)
                    value.startContent(this@BaseApplicationCall, this)
                }
            }
        }
    }

    private class PipeResponse(val pipe: AsyncPipe, headersDelegate: () -> ValuesMap, val start: () -> Unit) : FinalContent.ChannelContent() {
        override val headers by lazy(headersDelegate)

        override fun channel(): AsyncReadChannel {
            start()
            return pipe
        }
    }


    companion object {
        val ResponseChannelOverride = AttributeKey<AsyncWriteChannel>("ktor.response.channel")
        val RequestChannelOverride = AttributeKey<AsyncReadChannel>("ktor.request.channel")
    }
}