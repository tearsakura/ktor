package org.jetbrains.ktor.tests.routing

import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.routing.*
import org.jetbrains.ktor.tests.*
import org.jetbrains.ktor.util.*
import org.junit.*
import kotlin.test.*

fun routing() = Route(parent = null, selector = Routing.RootRouteSelector)
fun context(routing: Route, path: String, parameters: ValuesMap = ValuesMap.Empty)
        = RoutingResolveContext(routing, HttpRequestLine(HttpMethod.Companion.Get, path, "HTTP/1.1"), parameters)

fun context(routing: Route, path: String, parameters: ValuesMap = ValuesMap.Empty, headers: ValuesMap = ValuesMap.Empty)
        = RoutingResolveContext(routing, HttpRequestLine(HttpMethod.Companion.Get, path, "HTTP/1.1"), parameters, headers)

class RoutingResolveTest {
    @Test fun `empty routing`() {
        val root = routing()
        val result = context(root, "/foo/bar").resolve()
        on("resolving any request") {
            it("should not succeed") {
                assertFalse(result.succeeded)
            }
            it("should have root as fail entry") {
                assertEquals(root, result.entry)
            }
        }
    }

    @Test fun `routing with foo`() {
        val root = routing()
        val fooEntry = root.select(UriPartConstantRouteSelector("foo"))

        on("resolving /foo") {
            val result = context(root, "/foo").resolve()
            it("should succeed") {
                assertTrue(result.succeeded)
            }
            it("should have fooEntry as success entry") {
                assertEquals(fooEntry, result.entry)
            }
        }
        on("resolving /foo/bar") {
            val result = context(root, "/foo/bar").resolve()
            it("should not succeed") {
                assertFalse(result.succeeded)
            }
            it("should have fooEntry as fail entry") {
                assertEquals(fooEntry, result.entry)
            }
        }
    }

    @Test fun `routing with foo-bar`() {
        val root = routing()
        val fooEntry = root.select(UriPartConstantRouteSelector("foo"))
        val barEntry = fooEntry.select(UriPartConstantRouteSelector("bar"))

        on("resolving /foo") {
            val result = context(root, "/foo").resolve()
            it("should succeed") {
                assertTrue(result.succeeded)
            }
            it("should have fooEntry as success entry") {
                assertEquals(fooEntry, result.entry)
            }
        }

        on("resolving /foo/bar") {
            val result = context(root, "/foo/bar").resolve()
            it("should succeed") {
                assertTrue(result.succeeded)
            }
            it("should have barEntry as success entry") {
                assertEquals(barEntry, result.entry)
            }
        }

        on("resolving /other/bar") {
            val result = context(root, "/other/bar").resolve()
            it("should not succeed") {
                assertFalse(result.succeeded)
            }
            it("should have root as fail entry") {
                assertEquals(root, result.entry)
            }
        }
    }

    @Test fun `routing foo with parameter`() {
        val root = routing()
        val paramEntry = root.select(UriPartConstantRouteSelector("foo"))
                .select(UriPartParameterRouteSelector("param"))

        on("resolving /foo/value") {
            val resolveResult = context(root, "/foo/value").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, resolveResult.entry)
            }
            it("should have parameter value equal to 'value'") {
                assertEquals("value", resolveResult.values["param"])
            }
        }
    }

    @Test fun `routing foo with surrounded parameter`() {
        val root = routing()
        val paramEntry = root.select(UriPartConstantRouteSelector("foo"))
                .select(UriPartParameterRouteSelector("param", "a", "b"))

        on("resolving /foo/value") {
            val resolveResult = context(root, "/foo/avalueb").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, resolveResult.entry)
            }
            it("should have parameter value equal to 'value'") {
                assertEquals("value", resolveResult.values["param"])
            }
        }
    }

    @Test fun `routing foo with multiply parameters`() {
        val root = routing()
        root.select(UriPartConstantRouteSelector("foo"))
                .select(UriPartParameterRouteSelector("param1"))
                .select(UriPartParameterRouteSelector("param2"))

        on("resolving /foo/value1/value2") {
            val resolveResult = context(root, "/foo/value1/value2").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should have parameter values equal to 'value1' and 'value2'") {
                assertEquals("value1", resolveResult.values["param1"])
                assertEquals("value2", resolveResult.values["param2"])
            }
        }
    }

    @Test fun `routing foo with multivalue parameter`() {
        val root = routing()
        root.select(UriPartConstantRouteSelector("foo"))
                .select(UriPartParameterRouteSelector("param"))
                .select(UriPartParameterRouteSelector("param"))

        on("resolving /foo/value1/value2") {
            val resolveResult = context(root, "/foo/value1/value2").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should have parameter value equal to [value1, value2]") {
                assertEquals(listOf("value1", "value2"), resolveResult.values.getAll("param"))
            }
        }
    }

    @Test fun `routing foo with optional parameter`() {
        val root = routing()
        val paramEntry = root.select(UriPartConstantRouteSelector("foo"))
                .select(UriPartOptionalParameterRouteSelector("param"))

        on("resolving /foo/value") {
            val resolveResult = context(root, "/foo/value").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, resolveResult.entry)
            }
            it("should have parameter value equal to 'value'") {
                assertEquals("value", resolveResult.values["param"])
            }
        }

        on("resolving /foo") {
            val resolveResult = context(root, "/foo").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, resolveResult.entry)
            }
            it("should not have parameter value") {
                assertNull(resolveResult.values["param"])
            }
        }
    }

    @Test fun `routing foo with wildcard`() {
        val root = routing()
        val fooEntry = root.select(UriPartConstantRouteSelector("foo"))
        val paramEntry = fooEntry.select(UriPartWildcardRouteSelector)

        on("resolving /foo/value") {
            val resolveResult = context(root, "/foo/value").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, resolveResult.entry)
            }
        }

        on("resolving /foo") {
            val resolveResult = context(root, "/foo").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to fooEntry") {
                assertEquals(fooEntry, resolveResult.entry)
            }
        }
    }

    @Test fun `routing foo with anonymous tailcard`() {
        val root = routing()
        val paramEntry = root.select(UriPartConstantRouteSelector("foo"))
                .select(UriPartTailcardRouteSelector())

        on("resolving /foo/value") {
            val resolveResult = context(root, "/foo/value").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, resolveResult.entry)
            }
        }

        on("resolving /foo") {
            val resolveResult = context(root, "/foo").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, resolveResult.entry)
            }
        }

        on("resolving /foo/bar/baz/blah") {
            val resolveResult = context(root, "/foo/bar/baz/blah").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, resolveResult.entry)
            }
        }
    }

    @Test fun `routing foo with named tailcard`() {
        val root = routing()
        val paramEntry = root.select(UriPartConstantRouteSelector("foo"))
                .select(UriPartTailcardRouteSelector("items"))

        on("resolving /foo/value") {
            val resolveResult = context(root, "/foo/value").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, resolveResult.entry)
            }
            it("should have parameter value") {
                assertEquals("value", resolveResult.values["items"])
            }
        }

        on("resolving /foo") {
            val resolveResult = context(root, "/foo").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to fooEntry") {
                assertEquals(paramEntry, resolveResult.entry)
            }
            it("should have empty parameter") {
                assertNull(resolveResult.values["items"])
            }
        }

        on("resolving /foo/bar/baz/blah") {
            val resolveResult = context(root, "/foo/bar/baz/blah").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, resolveResult.entry)
            }
            it("should have parameter value") {
                assertEquals(listOf("bar", "baz", "blah"), resolveResult.values.getAll("items"))
            }
        }
    }

    @Test fun `routing foo with parameter entry`() {
        val root = routing()
        val fooEntry = root.select(UriPartConstantRouteSelector("foo"))
        val paramEntry = fooEntry.select(ParameterRouteSelector("name"))

        on("resolving /foo with query string name=value") {
            val resolveResult = context(root, "/foo", valuesOf("name" to listOf("value"))).resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, resolveResult.entry)
            }
            it("should have parameter value") {
                assertEquals(listOf("value"), resolveResult.values.getAll("name"))
            }
        }

        on("resolving /foo") {
            val resolveResult = context(root, "/foo").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to fooEntry") {
                assertEquals(fooEntry, resolveResult.entry)
            }
            it("should have no parameter") {
                assertNull(resolveResult.values["name"])
            }
        }

        on("resolving /foo with multiple parameters") {
            val resolveResult = context(root, "/foo", valuesOf("name" to listOf("value1", "value2"))).resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, resolveResult.entry)
            }
            it("should have parameter value") {
                assertEquals(listOf("value1", "value2"), resolveResult.values.getAll("name"))
            }
        }
    }

    @Test fun `routing foo with quality`() {
        val root = routing()
        val fooEntry = root.select(UriPartConstantRouteSelector("foo"))
        val paramEntry = fooEntry.select(UriPartParameterRouteSelector("name"))
        val constantEntry = fooEntry.select(UriPartConstantRouteSelector("admin"))

        on("resolving /foo/value") {
            val resolveResult = context(root, "/foo/value").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to paramEntry") {
                assertEquals(paramEntry, resolveResult.entry)
            }
            it("should have parameter value equal to 'value'") {
                assertEquals("value", resolveResult.values["name"])
            }
        }

        on("resolving /foo/admin") {
            val resolveResult = context(root, "/foo/admin").resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to constantEntry") {
                assertEquals(constantEntry, resolveResult.entry)
            }
            it("should not have parameter value") {
                assertNull(resolveResult.values["name"])
            }
        }

    }

    @Test fun `routing foo with quality and headers`() {
        val root = routing()
        val fooEntry = root.select(UriPartConstantRouteSelector("foo"))
        val plainEntry = fooEntry.select(HttpHeaderRouteSelector("Accept", "text/plain"))
        val htmlEntry = fooEntry.select(HttpHeaderRouteSelector("Accept", "text/html"))

        on("resolving /foo with more specific") {
            val resolveResult = context(root, "/foo", headers = valuesOf("Accept" to listOf("text/*, text/html, */*"))).resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to htmlEntry") {
                assertEquals(htmlEntry, resolveResult.entry)
            }
        }

        on("resolving /foo with equal preference") {
            val resolveResult = context(root, "/foo", headers = valuesOf("Accept" to listOf("text/plain, text/html"))).resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to plainEntry") {
                assertEquals(plainEntry, resolveResult.entry)
            }
        }

        on("resolving /foo with preference of text/plain") {
            val resolveResult = context(root, "/foo", headers = valuesOf("Accept" to listOf("text/plain, text/html; q=0.5"))).resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to plainEntry") {
                assertEquals(plainEntry, resolveResult.entry)
            }
        }

        on("resolving /foo with preference of text/html") {
            val resolveResult = context(root, "/foo", headers = valuesOf("Accept" to listOf("text/plain; q=0.5, text/html"))).resolve()

            it("should successfully resolve") {
                assertTrue(resolveResult.succeeded)
            }
            it("should resolve to htmlEntry") {
                assertEquals(htmlEntry, resolveResult.entry)
            }
        }
    }
}
