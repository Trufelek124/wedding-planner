package io.github.trufelek124.weddingplanner.api

import io.github.trufelek124.weddingplanner.api.model.AppInfo
import io.github.trufelek124.weddingplanner.api.model.Problem
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The models are generated from the backend's API description; these tests read what the server really sends. */
class ApiModelsTest {

    // A newer server may send fields this app does not know yet.
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `reads what the server says about itself`() {
        val info = json.decodeFromString<AppInfo>("""{"name":"wedding-planner","version":"0.1.0"}""")

        assertEquals(AppInfo(name = "wedding-planner", version = "0.1.0"), info)
    }

    @Test
    fun `ignores a field a newer server added`() {
        val info = json.decodeFromString<AppInfo>("""{"name":"wedding-planner","version":"0.2.0","addedLater":true}""")

        assertEquals("0.2.0", info.version)
    }

    @Test
    fun `reads an error with its kind`() {
        val problem = json.decodeFromString<Problem>(
            """{"type":"urn:wedding-planner:problem:not-found","title":"Not found","status":404,"instance":"/api/x"}""",
        )

        assertEquals(Problem.Type.NOT_FOUND, problem.type)
        assertEquals(404, problem.status)
        assertEquals("/api/x", problem.instance)
        assertNull(problem.detail)
    }

    @Test
    fun `a kind of error this app does not know cannot be read as a Problem`() {
        // Whoever calls the API must treat this case as "some error": see the API client (Android epic).
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<Problem>("""{"type":"urn:wedding-planner:problem:added-later","title":"New","status":409}""")
        }
    }
}
