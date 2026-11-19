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

    fun board(id: String, name: String, inspirations: Int = 0): String = """{"id":"$id","name":"$name","inspirationCount":$inspirations}"""

    /**
     * An inspiration as the server answers with one. @param pictures how many pictures a person sent with it
     * @param source the source as the server names it; INSTAGRAM for a link, PHOTO without one, unless said
     * @param previewStatus NONE, PENDING or FETCHED; PENDING for a link, NONE without one, unless said
     * @param addedById the member who added it; Anna unless said
     */
    fun inspiration(
        id: String,
        url: String? = null,
        boardId: String? = null,
        note: String = "",
        pictures: Int = 0,
        previews: Int = 0,
        source: String? = null,
        title: String = "",
        previewTitle: String = "",
        previewStatus: String? = null,
        tags: List<String> = emptyList(),
        planItemId: String? = null,
        vendorId: String? = null,
        addedById: String? = ANNA,
        version: Int = 0,
        /** The size the server says its pictures have, width then height. */
        size: Pair<Int, Int> = 800 to 600,
    ): String {
        val held = (List(previews) { true } + List(pictures) { false }).mapIndexed { number, preview ->
            """{"id":"pic-$number","contentType":"image/jpeg","width":${size.first},"height":${size.second},"preview":$preview}"""
        }
        return """{"id":"$id","url":${text(url)},"source":"${source ?: if (url == null) "PHOTO" else "INSTAGRAM"}","title":"$title","note":"$note","tags":${list(tags)},""" +
            """"boardId":${text(boardId)},"planItemId":${text(planItemId)},"vendorId":${text(vendorId)},"addedById":${text(addedById)},""" +
            """"previewStatus":"${previewStatus ?: if (url == null) "NONE" else "PENDING"}","previewTitle":"$previewTitle",""" +
            """"pictures":[${held.joinToString(",")}],"version":$version,"createdAt":"2026-11-18T13:05:00Z","updatedAt":"2026-11-18T13:05:00Z"}"""
    }

    /**
     * A suggestion of the e-mail import as the server sends one: made-up mail from a made-up sender.
     * @param kind TASK, PAYMENT, PLAN_ITEM or VENDOR
     * @param amount in the smallest unit; null where none was read
     * @param vendorId a vendor the planner has; [vendorName] is for one it has not
     * @param mailLink what the server says opens the message
     * @param more further fields, for what a newer server might add: `"addedLater":true`
     */
    fun suggestion(
        id: String,
        title: String,
        kind: String = "TASK",
        notes: String = "",
        startDate: String? = null,
        dueDate: String? = null,
        amount: Long? = null,
        paymentKind: String? = null,
        categoryId: String? = null,
        vendorId: String? = null,
        vendorName: String? = null,
        vendorEmail: String? = null,
        vendorPhone: String? = null,
        vendorWebsite: String? = null,
        senderName: String = "Jan from Palace on the Hill",
        senderAddress: String = "jan@palace.example",
        subject: String = "Deposit for the hall",
        receivedAt: String = "2026-11-18T13:05:00Z",
        inboxMemberId: String = ANNA,
        mailLink: String = "https://mail.google.com/mail/u/0/#all/18c0ffee",
        passage: String = "Please pay the deposit by 1 December.",
        confidence: Int = 62,
        more: String = "",
    ): String =
        """{"id":"$id","kind":"$kind","state":"WAITING","title":"$title","notes":"$notes","startDate":${text(startDate)},"dueDate":${text(dueDate)},""" +
            """"amount":$amount,"paymentKind":${text(paymentKind)},"categoryId":${text(categoryId)},"vendorId":${text(vendorId)},""" +
            """"vendorName":${text(vendorName)},"vendorEmail":${text(vendorEmail)},"vendorPhone":${text(vendorPhone)},"vendorWebsite":${text(vendorWebsite)},""" +
            """"senderName":"$senderName","senderAddress":"$senderAddress","subject":"$subject","receivedAt":"$receivedAt",""" +
            """"inboxMemberId":"$inboxMemberId","mailLink":"$mailLink","passage":"$passage","confidence":$confidence,""" +
            """"decidedBy":null,"decidedAt":null,"madeEntryId":null,"madeVendorId":null,"createdAt":"2026-11-18T13:06:00Z"""" +
            (if (more.isEmpty()) "" else ",$more") + "}"

    /** @param categoryId the vendor's category, or null */
    fun vendor(
        id: String,
        name: String,
        status: String = "BOOKED",
        categoryId: String? = null,
        contactPerson: String = "",
        phone: String = "",
        email: String = "",
        website: String = "",
        address: String = "",
        notes: String = "",
        agreedPrice: Long? = null,
        planItems: Int = 0,
        tasks: Int = 0,
        payments: Int = 0,
        committed: Long = 0,
        paid: Long = 0,
        inspirations: Int = 0,
        version: Int = 0,
    ): String =
        """{"id":"$id","name":"$name","categoryId":${text(categoryId)},"status":"$status","contactPerson":"$contactPerson","phone":"$phone",""" +
            """"email":"$email","website":"$website","address":"$address","notes":"$notes","agreedPrice":$agreedPrice,""" +
            """"planItems":$planItems,"tasks":$tasks,"payments":$payments,"committed":$committed,"paid":$paid,""" +
            """"inspirations":$inspirations,"version":$version,"createdAt":"2026-11-01T10:00:00Z","updatedAt":"2026-11-01T10:00:00Z"}"""

    /** A category of the budget as the server sends it; [planned] null while nothing is planned for it. */
    class Line(
        val categoryId: String,
        val name: String,
        val planned: Long? = null,
        val committed: Long = 0,
        val paid: Long = 0,
        val payments: Int = 0,
    )

    /** The payments under no category. */
    class Rest(val committed: Long = 0, val paid: Long = 0, val payments: Int = 0)

    /**
     * The whole budget. Its sums are worked out from the lines, as the server does, so that a
     * fixture cannot contradict itself.
     */
    fun wholeBudget(total: Long? = null, lines: List<Line> = emptyList(), rest: Rest = Rest(), version: Int = 0): String {
        val categories = lines.joinToString(",", "[", "]") {
            """{"categoryId":"${it.categoryId}","name":"${it.name}","planned":${it.planned},"committed":${it.committed},""" +
                """"paid":${it.paid},"payments":${it.payments}}"""
        }
        return """{"total":$total,"planned":${lines.sumOf { it.planned ?: 0 }},"committed":${lines.sumOf { it.committed } + rest.committed},""" +
            """"paid":${lines.sumOf { it.paid } + rest.paid},"categories":$categories,""" +
            """"uncategorised":{"committed":${rest.committed},"paid":${rest.paid},"payments":${rest.payments}},"version":$version}"""
    }

    /** Home's glance at the money, worked out from the same lines as [wholeBudget]. */
    fun glanceOf(total: Long? = null, lines: List<Line> = emptyList(), rest: Rest = Rest()): String =
        budget(
            total = total, planned = lines.sumOf { it.planned ?: 0 }, committed = lines.sumOf { it.committed } + rest.committed,
            paid = lines.sumOf { it.paid } + rest.paid,
        )

    /** A guest as the server sends one. [heads] is the guest, the plus-one and the children. */
    fun guest(
        id: String,
        name: String,
        status: String = "INVITED",
        sideMemberId: String? = null,
        householdId: String? = null,
        plusOne: Boolean = false,
        children: Int = 0,
        dietaryNeeds: String = "",
        needsAccommodation: Boolean = false,
        phone: String = "",
        email: String = "",
        notes: String = "",
        version: Int = 0,
    ): String =
        """{"id":"$id","name":"$name","status":"$status","sideMemberId":${text(sideMemberId)},"householdId":${text(householdId)},""" +
            """"plusOne":$plusOne,"children":$children,"heads":${1 + (if (plusOne) 1 else 0) + children},"dietaryNeeds":"$dietaryNeeds",""" +
            """"needsAccommodation":$needsAccommodation,"phone":"$phone","email":"$email","notes":"$notes","version":$version,""" +
            """"createdAt":"2026-11-01T10:00:00Z","updatedAt":"2026-11-01T10:00:00Z"}"""

    fun household(id: String, name: String, guests: Int = 0): String = """{"id":"$id","name":"$name","guests":$guests}"""

    private val statuses = listOf("NOT_INVITED", "INVITED", "CONFIRMED", "DECLINED", "MAYBE")

    private fun heads(people: List<Triple<String, Boolean, Int>>): String {
        val plusOnes = people.count { it.second }
        val children = people.sumOf { it.third }
        return """{"guests":${people.size},"plusOnes":$plusOnes,"children":$children,"heads":${people.size + plusOnes + children}}"""
    }

    /**
     * The head count, worked out from [guests] as the server does: every status in its order and every
     * side (the members in the wedding's order, then both sides) is listed, also with nobody in it.
     * @param guests a status, a side (null for both) and a plus-one and children count for each
     */
    fun guestCounts(guests: List<Counted> = emptyList(), sides: List<String> = listOf(ANNA, MAREK)): String {
        fun statusLines(of: List<Counted>) =
            statuses.joinToString(",", "[", "]") { status ->
                """{"status":"$status","count":${heads(of.filter { it.status == status }.map { Triple(it.status, it.plusOne, it.children) })}}"""
            }
        val bySide = (sides + null).joinToString(",", "[", "]") { side ->
            val its = guests.filter { it.sideMemberId == side }
            """{"sideMemberId":${text(side)},"count":${heads(its.map { Triple(it.status, it.plusOne, it.children) })},"byStatus":${statusLines(its)}}"""
        }
        return """{"total":${heads(guests.map { Triple(it.status, it.plusOne, it.children) })},"byStatus":${statusLines(guests)},"bySide":$bySide}"""
    }

    /** What the head count needs to know of a guest. */
    class Counted(val status: String, val sideMemberId: String? = null, val plusOne: Boolean = false, val children: Int = 0)
}

/** Makes the stand-in server answer everything the phone asks for when it loads the planner. */
fun StandInServer.planner(
    entries: List<String> = emptyList(),
    home: String = StandIn.home(),
    wedding: String = StandIn.wedding(),
    categories: List<String> = emptyList(),
    vendors: List<String> = emptyList(),
    boards: List<String> = emptyList(),
    budget: String = StandIn.wholeBudget(),
    guests: List<String> = emptyList(),
    households: List<String> = emptyList(),
    guestCounts: String = StandIn.guestCounts(),
    inspirations: List<String> = emptyList(),
    suggestions: List<String> = emptyList(),
) {
    json("GET /api/suggestions", suggestions.joinToString(",", "[", "]"))
    json("GET /api/inspirations", inspirations.joinToString(",", "[", "]"))
    json("GET /api/budget", budget)
    json("GET /api/guests", guests.joinToString(",", "[", "]"))
    json("GET /api/households", households.joinToString(",", "[", "]"))
    json("GET /api/guests/counts", guestCounts)
    json("GET /api/boards", boards.joinToString(",", "[", "]"))
    json("GET /api/wedding", wedding)
    json("GET /api/home", home)
    json("GET /api/entries", entries.joinToString(",", "[", "]"))
    json("GET /api/categories", categories.joinToString(",", "[", "]"))
    json("GET /api/vendors", vendors.joinToString(",", "[", "]"))
}

/** The server goes away: nothing of the planner can be asked for. */
fun StandInServer.plannerUnreachable() {
    listOf("wedding", "home", "entries", "categories", "vendors", "boards", "budget", "guests", "households", "guests/counts", "inspirations", "suggestions").forEach { unreachable("GET /api/$it") }
}
