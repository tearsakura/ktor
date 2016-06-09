package org.jetbrains.ktor.servlet

import com.typesafe.config.*
import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.features.*
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.logging.*
import org.jetbrains.ktor.pipeline.*
import org.jetbrains.ktor.transform.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javax.servlet.annotation.*
import javax.servlet.http.*

@MultipartConfig
open class ServletApplicationHost() : HttpServlet() {
    private val loader: ApplicationLoader by lazy {
        val servletContext = servletContext
        val parameterNames = servletContext.initParameterNames.toList().filter { it.startsWith("org.jetbrains.ktor") }
        val parameters = parameterNames.associateBy({ it.removePrefix("org.jetbrains.") }, { servletContext.getInitParameter(it) })

        val config = ConfigFactory.parseMap(parameters)
        val configPath = "ktor.config"

        val combinedConfig = if (config.hasPath(configPath)) {
            val configStream = servletContext.classLoader.getResourceAsStream(config.getString(configPath))
            val loadedKtorConfig = ConfigFactory.parseReader(configStream.bufferedReader())
            config.withFallback(loadedKtorConfig)
        } else
            config

        val applicationLog = SLF4JApplicationLog("ktor.application")
        val applicationConfig = HoconApplicationConfig(combinedConfig, servletContext.classLoader, applicationLog)
        ApplicationLoader(applicationConfig)
    }

    val application: Application get() = loader.application
    private val threadCounter = AtomicInteger()
    val executorService = ThreadPoolExecutor(Runtime.getRuntime().availableProcessors(), 100, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(), { r ->
        Thread(r, "apphost-pool-thread-${threadCounter.incrementAndGet()}")
    })

    override fun init() {
        application.setupDefaultHostPages()
        application.install(TransformationSupport)
    }

    override fun destroy() {
        executorService.shutdown()
        loader.dispose()
    }

    override fun service(request: HttpServletRequest, response: HttpServletResponse) {
        response.characterEncoding = "UTF-8"
        request.characterEncoding = "UTF-8"

        try {
            val latch = CountDownLatch(1)
            val call = ServletApplicationCall(application, request, response, executorService) { latch.countDown() }
            var throwable: Throwable? = null
            var pipelineState: PipelineState? = null

            call.executeOn(executorService, application).whenComplete { state, t ->
                pipelineState = state
                throwable = t
                latch.countDown()
            }

            latch.await()
            when {
                throwable != null -> throw throwable!!
                pipelineState == null -> {}
                pipelineState == PipelineState.Executing -> call.ensureAsync()
                !call.completed -> {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND)
                    call.close()
                }
                else -> {}
            }
        } catch (ex: Throwable) {
            application.config.log.error("ServletApplicationHost cannot service the request", ex)
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ex.message)
        }
    }

}
