package io.github.trufelek124.weddingplanner.data

import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.api.respondJson
import io.github.trufelek124.weddingplanner.api.respondProblem
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A stand-in server that keeps the planner's entries, so that what it lists follows what it was
 * sent: a tick changes the entry, a change needs the latest version, a new entry is added once.
 * It does what the real one does as far as the phone can tell, and no more.
 */
class LivingPlanner(
    private val server: StandInServer,
    entries: List<String>,
    private val categories: List<String> = emptyList(),
    private val vendors: List<String> = emptyList(),
    private val boards: List<String> = emptyList(),
    private val budget: String = StandIn.wholeBudget(),
    guests: List<String> = emptyList(),
    private val households: List<String> = emptyList(),
    private val inspirations: List<String> = emptyList(),
    private val suggestions: List<String> = emptyList(),
    private val today: String = "2026-11-18",
) {

    /** What the server has, by id, in the order of its list. */
    val entries = LinkedHashMap<String, JsonObject>()

    /** The guests the server has, by id, in the order of its list. */
    val guests = LinkedHashMap<String, JsonObject>()

    init {
        guests.forEach { guest -> Json.parseToJsonElement(guest).jsonObject.let { this.guests[it.getValue("id").jsonPrimitive.content] = it } }
        entries.forEach(::put)
        server.json("GET /api/wedding", StandIn.wedding())
        // Whatever fixed answers were named before give way: from here on the planner answers for itself.
        listOf("home", "entries", "categories", "vendors", "boards", "budget", "guests", "households", "guests/counts", "inspirations", "suggestions").forEach { server.unreachable("GET /api/$it") }
        server.otherwise = { request -> answer(request) }
    }

    /** The other owner changes something on another device. */
    fun put(entry: String) {
        val read = Json.parseToJsonElement(entry).jsonObject
        entries[read.getValue("id").jsonPrimitive.content] = read
    }

    /** Someone removes a guest on the web. */
    fun removeGuest(id: String) {
        guests.remove(id)
    }

    fun guestStatusOf(id: String): String? = guests[id]?.getValue("status")?.jsonPrimitive?.content

    fun guestVersionOf(id: String): Int? = guests[id]?.getValue("version")?.jsonPrimitive?.int

    /** The head count as the server works it out from the guests it has. */
    private fun counted(): String = StandIn.guestCounts(
        guests.values.map {
            StandIn.Counted(
                status = it.getValue("status").jsonPrimitive.content,
                sideMemberId = it.getValue("sideMemberId").let { side -> if (side == JsonNull) null else side.jsonPrimitive.content },
                plusOne = it.getValue("plusOne").jsonPrimitive.content.toBoolean(),
                children = it.getValue("children").jsonPrimitive.int,
            )
        },
    )

    fun titleOf(id: String): String? = entries[id]?.getValue("title")?.jsonPrimitive?.content

    fun statusOf(id: String): String? = entries[id]?.getValue("status")?.jsonPrimitive?.content

    private fun open(entry: JsonObject): Boolean = entry.getValue("status").jsonPrimitive.content.let { it != "DONE" && it != "DROPPED" }

    private fun list(entries: Collection<JsonObject>): String = entries.joinToString(",", "[", "]")

    /** Called with every request before it is answered: a test holds an answer up here to change something while it is on its way. */
    var holdUp: (suspend (HttpRequestData) -> Unit)? = null

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData? {
        holdUp?.invoke(request)
        val path = request.url.encodedPath
        val method = request.method.value
        val id = path.removePrefix("/api/entries/").removeSuffix("/status")
        return when {
            method == "GET" && path == "/api/entries" -> respondJson(list(entries.values))
            method == "GET" && path == "/api/categories" -> respondJson(categories.joinToString(",", "[", "]"))
            method == "GET" && path == "/api/vendors" -> respondJson(vendors.joinToString(",", "[", "]"))
            method == "GET" && path == "/api/boards" -> respondJson(boards.joinToString(",", "[", "]"))
            method == "GET" && path == "/api/budget" -> respondJson(budget)
            method == "GET" && path == "/api/guests" -> respondJson(guests.values.joinToString(",", "[", "]"))
            method == "PUT" && path.startsWith("/api/guests/") && path.endsWith("/status") -> {
                val kept = guests[path.removePrefix("/api/guests/").removeSuffix("/status")] ?: return respondProblem(404, "not-found")
                val status = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject.getValue("status")
                val now = JsonObject(kept + ("status" to status) + ("version" to JsonPrimitive(kept.getValue("version").jsonPrimitive.int + 1)))
                guests[kept.getValue("id").jsonPrimitive.content] = now
                respondJson(now.toString())
            }
            method == "GET" && path == "/api/suggestions" -> respondJson(suggestions.joinToString(",", "[", "]"))
            method == "GET" && path == "/api/inspirations" -> respondJson(inspirations.joinToString(",", "[", "]"))
            method == "GET" && path == "/api/households" -> respondJson(households.joinToString(",", "[", "]"))
            method == "GET" && path == "/api/guests/counts" -> respondJson(counted())
            method == "GET" && path == "/api/home" -> {
                val toDo = entries.values.filter(::open)
                val payments = toDo.filter { it.getValue("amount") != JsonNull }
                respondJson(
                    """{"daysLeft":213,"today":"$today","weddingDate":"2027-06-19","overdue":[],"next":${list(toDo.take(5))},""" +
                        """"nextPayments":${list(payments.take(5))},"budget":${StandIn.budget(committed = payments.sumOf { it.getValue("amount").jsonPrimitive.content.toLong() })}}""",
                )
            }
            method == "POST" && path == "/api/entries" -> {
                val sent = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
                val newId = sent.getValue("id").jsonPrimitive.content
                // Sent twice, it is added once.
                val kept = entries.getOrPut(newId) {
                    Json.parseToJsonElement(StandIn.entry(newId, "")).jsonObject.let { blank -> workedOut(JsonObject(blank + sent)) }
                }
                respondJson(kept.toString(), 201)
            }
            path.startsWith("/api/entries/") -> {
                val kept = entries[id] ?: return respondProblem(404, "not-found")
                when {
                    method == "GET" -> respondJson(kept.toString())
                    method == "PUT" && path.endsWith("/status") -> {
                        val status = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject.getValue("status")
                        val paid = if (status.jsonPrimitive.content == "DONE" && kept.getValue("amount") != JsonNull) JsonPrimitive(today) else JsonNull
                        respondJson(changed(id, kept, JsonObject(mapOf("status" to status, "paidOn" to paid))))
                    }
                    method == "PUT" -> {
                        val sent = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
                        if (sent.getValue("version").jsonPrimitive.int != kept.getValue("version").jsonPrimitive.int) {
                            respondProblem(409, "changed-meanwhile")
                        } else {
                            respondJson(changed(id, kept, JsonObject(sent - "version")))
                        }
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    /** Every change counts the version up, a tick included. */
    private fun changed(id: String, kept: JsonObject, with: JsonObject): String {
        val now = workedOut(JsonObject(kept + with + ("version" to JsonPrimitive(kept.getValue("version").jsonPrimitive.int + 1))))
        entries[id] = now
        return now.toString()
    }

    /** A level that is left to the server comes back worked out, never empty. */
    private fun workedOut(entry: JsonObject): JsonObject =
        if (entry.getValue("priority") == JsonNull) {
            JsonObject(entry + ("priority" to JsonPrimitive("NORMAL")) + ("priorityReason" to JsonPrimitive(if (entry.getValue("dueDate") == JsonNull) "NO_DATE" else "DUE_LATER")))
        } else {
            JsonObject(entry + ("priorityReason" to JsonPrimitive("SET_BY_HAND")))
        }
}
