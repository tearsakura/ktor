package org.jetbrains.ktor.testing

import com.typesafe.config.*
import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.content.*
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.interception.*
import org.jetbrains.ktor.logging.*
import org.jetbrains.ktor.nio.*
import org.jetbrains.ktor.util.*
import java.io.*
import java.util.concurrent.*
import kotlin.reflect.*
import kotlin.reflect.jvm.*

inline fun <reified T : Application> withApplication(noinline test: TestApplicationHost.() -> Unit) {
    withApplication(T::class, test)
}

fun withApplication(config: ApplicationConfig, test: TestApplicationHost.() -> Unit) {
    val host = TestApplicationHost(config)
    try {
        host.test()
    } finally {
        host.dispose()
    }
}

fun withApplication(applicationClass: KClass<*>, test: TestApplicationHost.() -> Unit) {
    val testConfig = ConfigFactory.parseMap(
            mapOf(
                    "ktor.deployment.environment" to "test",
                    "ktor.application.class" to applicationClass.jvmName
            ))
    val config = HoconApplicationConfig(testConfig, ApplicationConfig::class.java.classLoader, SLF4JApplicationLog("ktor.test"))
    withApplication(config, test)
}

class TestApplicationHost(val applicationConfig: ApplicationConfig) {
    val application: Application = ApplicationLoader(applicationConfig).application
    val pipeline = ApplicationCallPipeline()
    var exception : Throwable? = null
    val executor = Executors.newCachedThreadPool()

    init {
        pipeline.intercept(ApplicationCallPipeline.Infrastructure) { call ->
            onFail { exception ->
                val testApplicationCall = call as? TestApplicationCall
                testApplicationCall?.latch?.countDown()
                this@TestApplicationHost.exception = exception
            }

            onSuccess {
                val testApplicationCall = call as? TestApplicationCall
                testApplicationCall?.latch?.countDown()
            }
            fork(call, application)
        }
    }

    fun handleRequest(setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
        val call = createCall(setup)

        call.execute(pipeline)
        call.await()

        exception?.let { throw it }

        return call
    }

    fun handleWebSocket(uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
        val call = createCall {
            this.uri = uri
            addHeader(HttpHeaders.Connection, "Upgrade")
            addHeader(HttpHeaders.Upgrade, "websocket")
            addHeader(HttpHeaders.SecWebSocketKey, encodeBase64("test".toByteArray()))

            setup()
        }

        call.execute(pipeline)
        call.await()

        exception?.let { throw it }

        return call
    }

    fun dispose() {
        executor.shutdown()
        application.dispose()
        executor.shutdownNow()
    }

    private fun createCall(setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
        val request = TestApplicationRequest()
        setup(request)

        return TestApplicationCall(application, request, executor)
    }
}

fun TestApplicationHost.handleRequest(method: HttpMethod, uri: String, setup: TestApplicationRequest.() -> Unit = {}): TestApplicationCall {
    return handleRequest {
        this.uri = uri
        this.method = method
        setup()
    }
}

class TestApplicationCall(application: Application, override val request: TestApplicationRequest, executor: Executor) : BaseApplicationCall(application, executor) {
    internal val latch = CountDownLatch(1)
    override val parameters: ValuesMap get() = request.parameters
    override val attributes = Attributes()
    override fun close() {
        requestResult = ApplicationCallResult.Handled
        response.close()
    }

    override val response = TestApplicationResponse()

    @Volatile
    var requestResult = ApplicationCallResult.Unhandled

    override fun toString(): String = "TestApplicationCall(uri=${request.uri}) : $requestResult"

    fun await() {
        latch.await()
    }
}

class TestApplicationRequest() : ApplicationRequest {
    override var requestLine: HttpRequestLine = HttpRequestLine(HttpMethod.Get, "/", "HTTP/1.1")

    var uri: String
        get() = requestLine.uri
        set(value) {
            requestLine = requestLine.copy(uri = value)
        }

    var method: HttpMethod
        get() = requestLine.method
        set(value) {
            requestLine = requestLine.copy(method = value)
        }

    var bodyBytes: ByteArray = ByteArray(0)
    var body: String
        get() = bodyBytes.toString(Charsets.UTF_8)
        set(newValue) {
            bodyBytes = newValue.toByteArray(Charsets.UTF_8)
        }

    var multiPartEntries: List<PartData> = emptyList()

    override val parameters: ValuesMap get() {
        return queryParameters() + if (contentType().match(ContentType.Application.FormUrlEncoded)) body.parseUrlEncodedParameters() else ValuesMap.Empty
    }

    private var headersMap: MutableMap<String, MutableList<String>>? = hashMapOf()
    fun addHeader(name: String, value: String) {
        val map = headersMap ?: throw Exception("Headers were already acquired for this request")
        map.getOrPut(name, { arrayListOf() }).add(value)
    }

    override val headers by lazy {
        val map = headersMap ?: throw Exception("Headers were already acquired for this request")
        headersMap = null
        valuesOf(map, caseInsensitiveKey = true)
    }

    override val content: RequestContent = object : RequestContent(this) {
        override fun getInputStream(): InputStream = ByteArrayInputStream(bodyBytes)
        override fun getReadChannel() = ByteArrayAsyncReadChannel(bodyBytes)

        override fun getMultiPartData(): MultiPartData = object : MultiPartData {
            override val parts: Sequence<PartData>
                get() = when {
                    isMultipart() -> multiPartEntries.asSequence()
                    else -> throw IOException("The request content is not multipart encoded")
                }
        }
    }

    override val cookies = RequestCookies(this)
}

class TestApplicationResponse() : BaseApplicationResponse() {
    private val realContent = lazy { ByteArrayAsyncWriteChannel() }
    @Volatile
    private var closed = false

    override fun setStatus(statusCode: HttpStatusCode) {
    }

    override val channel = Interceptable0<AsyncWriteChannel> { realContent.value }

    override val headers: ResponseHeaders = object : ResponseHeaders() {
        private val headersMap = ValuesMapBuilder(true)
        private val headers: ValuesMap
            get() = headersMap.build()

        override fun hostAppendHeader(name: String, value: String) {
            if (closed)
                throw UnsupportedOperationException("Headers can no longer be set because response was already completed")
            headersMap.append(name, value)
        }

        override fun getHostHeaderNames(): List<String> = headers.names().toList()
        override fun getHostHeaderValues(name: String): List<String> = headers.getAll(name).orEmpty()
    }


    val content: String?
        get() = if (realContent.isInitialized()) {
            realContent.value.toByteArray().toString(charset(headers[HttpHeaders.ContentType]?.let { ContentType.parse(it).parameter("charset") } ?: "UTF-8"))
        } else {
            null
        }

    val byteContent: ByteArray?
        get() = if (realContent.isInitialized()) {
            realContent.value.toByteArray()
        } else {
            null
        }

    fun close() {
        closed = true
    }
}

class TestApplication(config: ApplicationConfig) : Application(config)