package org.jetbrains.ktor.pipeline

class PipelineContext<T>(private val execution: PipelineExecution<T>, val function: PipelineContext<T>.(T) -> Unit) : PipelineControl<T> {
    override fun fork(subject: T, pipeline: Pipeline<T>) {
        execution.fork(subject, pipeline)
    }

    override fun fail(exception: Throwable) {
        execution.fail(exception)
    }

    override fun pause() {
        state = PipelineExecution.State.Pause
    }

    override fun proceed() {
        execution.proceed()
    }

    override fun stop() {
        state = PipelineExecution.State.Finished
    }

    val exits = mutableListOf<() -> Unit>()
    val failures = mutableListOf<(Throwable) -> Unit>()

    val subject: T get() = execution.subject
    val pipeline: PipelineControl<T> get() = this

    var state = PipelineExecution.State.Pause

    fun onFinish(body: () -> Unit) {
        exits.add(body)
    }

    fun onFail(body: (Throwable) -> Unit) {
        failures.add(body)
    }

    fun execute(subject: T) {
        state = PipelineExecution.State.Execute
        function(subject)
    }
}