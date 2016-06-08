package org.jetbrains.ktor.transform

import java.util.*
import kotlin.reflect.*

class TransformTable {
    private val root = Entry(Any::class.java)

    inline fun <reified T : Any> register(noinline predicate: (T) -> Boolean = { true }, noinline handler: (T) -> Any) {
        register(T::class, predicate, handler)
    }

    fun <T : Any> register(type: KClass<T>, predicate: (T) -> Boolean, handler: (T) -> Any) {
        @Suppress("UNCHECKED_CAST")
        registerImpl(type.java, root as Entry<T>, Handler(predicate, handler))
    }

    fun transform(obj: Any): Any = transformImpl(obj)

    private fun <T : Any> registerImpl(type: Class<T>, node: Entry<T>, handler: Handler<T>): Int {
        if (node.type === type) {
            node.handlers.add(handler)
            return 1
        } else if (node.type.isAssignableFrom(type)) {
            val installed = node.leafs.map { registerImpl(type, it, handler) }.sum()
            if (installed == 0) {
                val entry = Entry(type)
                node.leafs.add(entry)
                return registerImpl(type, entry, handler)
            }

            return installed
        }

        return 0
    }

    tailrec
    private fun <T : Any> transformImpl(obj: T, handlers: List<Handler<T>> = collect(obj.javaClass), visited: MutableSet<Handler<*>> = HashSet()): Any {
        for (handler in handlers) {
            if (handler !in visited && handler.predicate(obj)) {
                val result = handler.handler(obj)

                if (result === obj) {
                    continue
                }

                visited.add(handler)
                if (result.javaClass === obj.javaClass) {
                    @Suppress("UNCHECKED_CAST")
                    return transformImpl(result as T, handlers, visited)
                } else {
                    return transformImpl(result, collect(result.javaClass), visited)
                }
            }
        }

        return obj
    }

    private fun <T : Any> collect(type: Class<T>) =
            ArrayList<Handler<T>>().apply {
                collectImpl(type, mutableListOf<Entry<*>>(root), this)
            }.asReversed()

    tailrec
    private fun <T : Any> collectImpl(type: Class<T>, nodes: MutableList<Entry<*>>, result: ArrayList<Handler<T>>) {
        val current = nodes.lookup(type) ?: return

        result.addAll(current.handlers)
        nodes.addAll(current.leafs)

        collectImpl(type, nodes, result)
    }

    tailrec
    private fun <T : Any> MutableList<Entry<*>>.lookup(type: Class<T>): Entry<T>? {
        if (isEmpty()) return null

        return removeAt(lastIndex).castOrNull(type) ?: lookup(type)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> Entry<*>.castOrNull(type: Class<T>) = if (this.type.isAssignableFrom(type)) this as Entry<T> else null

    private class Entry<T : Any>(val type: Class<T>) {
        val handlers = ArrayList<Handler<T>>()
        val leafs = ArrayList<Entry<T>>()

        override fun toString() = "Entry(${type.name})"
    }

    private class Handler<in T>(val predicate: (T) -> Boolean, val handler: (T) -> Any) {
        override fun toString() = handler.toString()
    }
}