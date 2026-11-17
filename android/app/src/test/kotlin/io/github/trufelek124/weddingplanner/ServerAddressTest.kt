package io.github.trufelek124.weddingplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {

    @Test
    fun `keeps scheme, host and port`() {
        assertEquals("http://10.0.2.2:8080", ServerAddress.parse("http://10.0.2.2:8080").value)
        assertEquals("https://planner.example", ServerAddress.parse("https://planner.example").value)
    }

    @Test
    fun `drops surrounding spaces and trailing slashes, and lowercases scheme and host`() {
        assertEquals("https://planner.example", ServerAddress.parse("  HTTPS://Planner.Example//  ").value)
    }

    @Test
    fun `resolves an API path`() {
        assertEquals("https://planner.example/api/plan", ServerAddress.parse("https://planner.example/").resolve("/api/plan"))
    }

    @Test
    fun `refuses a path that does not start with a slash`() {
        assertThrows(IllegalArgumentException::class.java) { ServerAddress.parse("https://planner.example").resolve("api/plan") }
    }

    @Test
    fun `refuses what is not an http or https server address`() {
        val refused = listOf(
            "", "planner.example", "ftp://planner.example", "file:///etc/hosts", "https://", "https:///api",
            "https://planner.example/api", "https://planner.example?x=1", "https://planner.example#top",
            "https://user:secret@planner.example", "https://planner example",
        )
        for (raw in refused) {
            assertThrows("should refuse '$raw'", IllegalArgumentException::class.java) { ServerAddress.parse(raw) }
        }
    }
}
