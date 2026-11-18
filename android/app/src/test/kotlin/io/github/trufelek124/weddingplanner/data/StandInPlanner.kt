package io.github.trufelek124.weddingplanner.data

import io.github.trufelek124.weddingplanner.api.StandInServer

/**
 * What the planner's server answers, written out field by field as the real one does. The people,
 * vendors and plans are made up. StandInPlannerTest holds these against the API's description.
 */
object StandIn {

    const val ANNA = "11111111-1111-4111-8111-111111111111"
    const val MAREK = "22222222-2222-4222-8222-222222222222"

    private fun text(value: String?): String = if (value == null) "null" else "\"$value\""

    private fun list(values: List<String>): String = values.joinToString(",", "[", "]") { "\"$it\"" }

    /** @param more further fields, for what a newer server might add: `"addedLater":true` */
    fun entry(
        id: String,
        title: String,
        kind: String = "TASK",
        status: String = "NOT_STARTED",
        startDate: String? = null,
        dueDate: String? = null,
        priority: String = "NORMAL",
        priorityReason: String = if (dueDate == null) "NO_DATE" else "DUE_LATER",
        priorityMode: String = "AUTO",
        categoryId: String? = null,
        parentId: String? = null,
        vendorId: String? = null,
        amount: Long? = null,
        paymentKind: String? = null,
        paidOn: String? = null,
        assigneeIds: List<String> = emptyList(),
        taskCount: Int = 0,
        notes: String = "",
        version: Int = 0,
        more: String = "",
    ): String =
        """{"id":"$id","kind":"$kind","title":"$title","notes":"$notes","categoryId":${text(categoryId)},""" +
            """"startDate":${text(startDate)},"dueDate":${text(dueDate)},"status":"$status",""" +
            """"completedAt":${if (status == "DONE") "\"2026-11-10T09:00:00Z\"" else "null"},""" +
            """"priorityMode":"$priorityMode","priority":"$priority","priorityReason":"$priorityReason",""" +
            """"parentId":${text(parentId)},"vendorId":${text(vendorId)},"amount":$amount,"paymentKind":${text(paymentKind)},""" +
            """"paidOn":${text(paidOn)},"assigneeIds":${list(assigneeIds)},"dependsOnIds":[],"conflictsWithIds":[],""" +
            """"taskCount":$taskCount,"version":$version,"createdAt":"2026-11-01T10:00:00Z","updatedAt":"2026-11-02T10:00:00Z"""" +
            (if (more.isEmpty()) "" else ",$more") + "}"

    fun wedding(date: String? = "2027-06-19", currency: String = "PLN", second: Boolean = true): String =
        """{"id":"99999999-9999-4999-8999-999999999999","date":${text(date)},"timeZone":"Europe/Warsaw","currency":"$currency",""" +
            """"members":[{"id":"$ANNA","displayName":"Anna","signedIn":true,"you":true}""" +
            (if (second) """,{"id":"$MAREK","displayName":"Marek","signedIn":false,"you":false}""" else "") + "]}"

    fun budget(total: Long? = null, planned: Long = 0, committed: Long = 0, paid: Long = 0): String =
        """{"total":$total,"planned":$planned,"committed":$committed,"paid":$paid}"""

    fun home(
        daysLeft: Long? = 213,
        today: String = "2026-11-18",
        weddingDate: String? = "2027-06-19",
        overdue: List<String> = emptyList(),
        next: List<String> = emptyList(),
        nextPayments: List<String> = emptyList(),
        budget: String = budget(),
    ): String =
        """{"daysLeft":$daysLeft,"today":"$today","weddingDate":${text(weddingDate)},"overdue":[${overdue.joinToString(",")}],""" +
            """"next":[${next.joinToString(",")}],"nextPayments":[${nextPayments.joinToString(",")}],"budget":$budget}"""

    fun category(id: String, name: String): String = """{"id":"$id","name":"$name"}"""

    fun vendor(id: String, name: String): String =
        """{"id":"$id","name":"$name","categoryId":null,"status":"BOOKED","contactPerson":"","phone":"","email":"","website":"",""" +
            """"address":"","notes":"","agreedPrice":null,"planItems":0,"tasks":0,"payments":0,"committed":0,"paid":0,""" +
            """"inspirations":0,"version":0,"createdAt":"2026-11-01T10:00:00Z","updatedAt":"2026-11-01T10:00:00Z"}"""
}

/** Makes the stand-in server answer everything the phone asks for when it loads the planner. */
fun StandInServer.planner(
    entries: List<String> = emptyList(),
    home: String = StandIn.home(),
    wedding: String = StandIn.wedding(),
    categories: List<String> = emptyList(),
    vendors: List<String> = emptyList(),
) {
    json("GET /api/wedding", wedding)
    json("GET /api/home", home)
    json("GET /api/entries", entries.joinToString(",", "[", "]"))
    json("GET /api/categories", categories.joinToString(",", "[", "]"))
    json("GET /api/vendors", vendors.joinToString(",", "[", "]"))
}

/** The server goes away: nothing of the planner can be asked for. */
fun StandInServer.plannerUnreachable() {
    listOf("wedding", "home", "entries", "categories", "vendors").forEach { unreachable("GET /api/$it") }
}
