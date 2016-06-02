package org.jetbrains.ktor.tomcat

import org.apache.catalina.startup.*
import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.servlet.*
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javax.servlet.*

class TomcatApplicationHost(override val hostConfig: ApplicationHostConfig,
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

    private val threadCounter = AtomicLong(0)
    override val executor = ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() * 2,
            Math.max(100, Runtime.getRuntime().availableProcessors() * 2),
            30L, TimeUnit.SECONDS, LinkedBlockingQueue(), ThreadFactory { Thread(it, "worker-thread-${threadCounter.incrementAndGet()}") }
    )

    val server = Tomcat().apply {
        setPort(hostConfig.port)
        setHostname(hostConfig.host)

        val ctx = addContext("/", File(".").absolutePath).apply {
        }

        Tomcat.addServlet(ctx, "ktor-servlet", object : KtorServlet() {
            override val application: Application
                get() = this@TomcatApplicationHost.application
        }).apply {
            addMapping("/*")
            isAsyncSupported = true
            multipartConfigElement = MultipartConfigElement("")
        }
    }

    override fun start(wait: Boolean) {
        config.log.info("Starting server...")
        server.start()
        config.log.info("Server started")

        if (wait) {
            server.server.await()
            config.log.info("Server stopped.")
        }
    }

    override fun stop() {
        executor.shutdown()
        server.stop()

        executor.shutdownNow()
        config.log.info("Server stopped.")
    }
}