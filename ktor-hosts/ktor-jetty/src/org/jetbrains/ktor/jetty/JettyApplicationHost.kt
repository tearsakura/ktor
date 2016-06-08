package org.jetbrains.ktor.jetty

import org.eclipse.jetty.server.*
import org.eclipse.jetty.server.handler.*
import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.pipeline.*
import org.jetbrains.ktor.servlet.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javax.servlet.*
import javax.servlet.http.*

/**
 * [ApplicationHost] implementation for running standalone Jetty Host
 */
class JettyApplicationHost(override val hostConfig: ApplicationHostConfig,
                           val config: ApplicationConfig,
                           val applicationLifecycle: ApplicationLifecycle) : ApplicationHost {

    private val application: Application get() = applicationLifecycle.application

    constructor(hostConfig: ApplicationHostConfig, config: ApplicationConfig)
    : this(hostConfig, config, ApplicationLoader(config))

    constructor(hostConfig: ApplicationHostConfig, config: ApplicationConfig, application: Application)
    : this(hostConfig, config, object : ApplicationLifecycle {
        override val application: Application = application
        override fun dispose() {
        }
    })

    private val server = Server().apply {
        val httpConfig = HttpConfiguration().apply {
            sendServerVersion = false
        }
        val connectionFactory = HttpConnectionFactory(httpConfig)
        val connector = ServerConnector(this, connectionFactory).apply {
            host = hostConfig.host
            port = hostConfig.port
        }
        connectors = arrayOf(connector)
        handler = Handler()
    }

    private val threadCounter = AtomicLong(0)
    override val executor = ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() * 2,
            Math.max(100, Runtime.getRuntime().availableProcessors() * 2),
            30L, TimeUnit.SECONDS, LinkedBlockingQueue(), ThreadFactory { Thread(it, "worker-thread-${threadCounter.incrementAndGet()}") }
    )

    private val MULTI_PART_CONFIG = MultipartConfigElement(System.getProperty("java.io.tmpdir"));

    init {
        application.setupDefaultHostPages()
    }

    private inner class Handler : AbstractHandler() {
        override fun handle(target: String, baseRequest: Request, request: HttpServletRequest, response: HttpServletResponse) {
            response.characterEncoding = "UTF-8"

            val latch = CountDownLatch(1)
            var pipelineState: PipelineState? = null
            var throwable: Throwable? = null

            val call = ServletApplicationCall(application, request, response, executor) {
                latch.countDown()
            }

            setupUpgradeHelper(request, response, server, latch, call)

            try {
                val contentType = request.contentType
                if (contentType != null && ContentType.parse(contentType).match(ContentType.MultiPart.Any)) {
                    baseRequest.setAttribute(Request.__MULTIPART_CONFIG_ELEMENT, MULTI_PART_CONFIG)
                    // TODO someone reported auto-cleanup issues so we have to check it
                }

                call.executeOn(executor, application).whenComplete { state, t ->
                    pipelineState = state
                    throwable = t

                    latch.countDown()
                }

                latch.await()
                when {
                    throwable != null -> throw throwable!!
                    pipelineState == null -> baseRequest.isHandled = true
                    pipelineState == PipelineState.Executing -> {
                        baseRequest.isHandled = true
                        call.ensureAsync()
                    }
                    pipelineState == PipelineState.Succeeded -> baseRequest.isHandled = call.completed
                    pipelineState == PipelineState.Failed -> baseRequest.isHandled = true
                    else -> {}
                }
            } catch(ex: Throwable) {
                config.log.error("Application ${application.javaClass} cannot fulfill the request", ex);
                call.executionMachine.runBlockWithResult {
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }
        }
    }

    override fun start(wait: Boolean) {
        config.log.info("Starting server...")

        server.start()
        config.log.info("Server running.")
        if (wait) {
            server.join()
            applicationLifecycle.dispose()
            config.log.info("Server stopped.")
        }
    }

    override fun stop() {
        executor.shutdown()
        server.stop()
        applicationLifecycle.dispose()
        executor.shutdownNow()
        config.log.info("Server stopped.")
    }
}
