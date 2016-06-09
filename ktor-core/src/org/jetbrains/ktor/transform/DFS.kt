package org.jetbrains.ktor.transform

import java.util.*

internal inline fun <reified T : Any> dfs(): List<Class<*>> = dfs(T::class.java)

internal fun dfs(type: Class<*>): List<Class<*>> {
    val result = LinkedHashSet<Class<*>>()
    dfs(mutableListOf(Pair(type, supertypes(type).toMutableList())), ::supertypes, mutableSetOf(type), result)

    return result.toList()
}

tailrec
private fun <T> dfs(nodes: MutableList<Pair<T, MutableList<T>>>, parent: (T) -> List<T>, path: MutableSet<T>, visited: MutableSet<T>) {
    if (nodes.isEmpty()) return

    val (current, children) = nodes.last()
    if (children.isEmpty()) {
        visited.add(current)
        path.remove(current)
        nodes.removeLast()
    } else {
        val next = children.removeLast()
        if (path.add(next)) {
            nodes.add(Pair(next, parent(next).toMutableList()))
        }
    }

    dfs(nodes, parent, path, visited)
}


private fun supertypes(clazz: Class<*>): List<Class<*>> = clazz.superclass?.let { clazz.interfaces.orEmpty().toList() + it } ?: clazz.interfaces.orEmpty().toList()
private fun <T> MutableList<T>.removeLast(): T = removeAt(lastIndex)