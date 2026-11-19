package io.github.trufelek124.weddingplanner.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
abstract class PlannerDao {

    /** Everything that is stored, as it was at one moment; null while nothing was ever loaded. */
    @Transaction
    open suspend fun stored(): Stored? {
        val planner = planner() ?: return null
        return Stored(planner, entries(), categories(), vendors(), members(), changes(), boards(), shares(), budgetLines(), guests(), households(), guestStatuses(), inspirations(), inspirationPictures(), suggestions())
    }

    /** Puts a new load in the place of the last one, all of it or nothing. The changes waiting to be sent stay. */
    @Transaction
    open suspend fun replace(stored: Stored) {
        forgetTheCopy()
        put(stored.planner)
        putEntries(stored.entries)
        putCategories(stored.categories)
        putVendors(stored.vendors)
        putMembers(stored.members)
        putBoards(stored.boards)
        putBudgetLines(stored.budgetLines)
        putGuests(stored.guests)
        putHouseholds(stored.households)
        putInspirations(stored.inspirations)
        putInspirationPictures(stored.inspirationPictures)
        putSuggestions(stored.suggestions)
    }

    /** Removes everything, the waiting changes included: for a sign-out. */
    @Transaction
    open suspend fun forget() {
        forgetTheCopy()
        deleteChanges()
        deleteShares()
        deleteGuestStatuses()
    }

    @Transaction
    open suspend fun forgetTheCopy() {
        deletePlanner()
        deleteEntries()
        deleteCategories()
        deleteVendors()
        deleteMembers()
        deleteBoards()
        deleteBudgetLines()
        deleteGuests()
        deleteHouseholds()
        deleteInspirations()
        deleteInspirationPictures()
        deleteSuggestions()
    }

    /** The server took a change: its answer replaces the phone's copy of the entry, and the change is no longer waiting. */
    @Transaction
    open suspend fun sent(change: ChangeRow, answer: EntryRow) {
        putEntry(answer)
        deleteChange(change.seq)
    }

    @Query("SELECT * FROM planner WHERE id = 1")
    abstract suspend fun planner(): PlannerRow?

    @Query("SELECT * FROM entry ORDER BY position")
    abstract suspend fun entries(): List<EntryRow>

    @Query("SELECT * FROM category ORDER BY position")
    abstract suspend fun categories(): List<CategoryRow>

    @Query("SELECT * FROM vendor ORDER BY position, name")
    abstract suspend fun vendors(): List<VendorRow>

    @Query("SELECT * FROM member ORDER BY position")
    abstract suspend fun members(): List<MemberRow>

    @Insert
    abstract suspend fun put(planner: PlannerRow)

    @Insert
    abstract suspend fun putEntries(entries: List<EntryRow>)

    @Insert
    abstract suspend fun putCategories(categories: List<CategoryRow>)

    @Insert
    abstract suspend fun putVendors(vendors: List<VendorRow>)

    @Insert
    abstract suspend fun putMembers(members: List<MemberRow>)

    @Query("DELETE FROM planner")
    abstract suspend fun deletePlanner()

    @Query("DELETE FROM entry")
    abstract suspend fun deleteEntries()

    @Query("DELETE FROM category")
    abstract suspend fun deleteCategories()

    @Query("DELETE FROM vendor")
    abstract suspend fun deleteVendors()

    @Query("DELETE FROM member")
    abstract suspend fun deleteMembers()

    @Query("SELECT * FROM entry WHERE id = :id")
    abstract suspend fun entry(id: String): EntryRow?

    @Query("SELECT MAX(position) FROM entry")
    abstract suspend fun lastPosition(): Int?

    @Upsert
    abstract suspend fun putEntry(entry: EntryRow)

    @Query("SELECT * FROM waiting_change ORDER BY seq")
    abstract suspend fun changes(): List<ChangeRow>

    @Query("SELECT * FROM waiting_change WHERE entryId = :entryId")
    abstract suspend fun changeOf(entryId: String): ChangeRow?

    @Query("SELECT * FROM waiting_change WHERE seq = :seq")
    abstract suspend fun change(seq: Long): ChangeRow?

    @Upsert
    abstract suspend fun putChange(change: ChangeRow)

    @Query("DELETE FROM waiting_change WHERE seq = :seq")
    abstract suspend fun deleteChange(seq: Long)

    @Query("DELETE FROM waiting_change")
    abstract suspend fun deleteChanges()

    @Query("SELECT * FROM board ORDER BY position")
    abstract suspend fun boards(): List<BoardRow>

    @Insert
    abstract suspend fun putBoards(boards: List<BoardRow>)

    @Query("DELETE FROM board")
    abstract suspend fun deleteBoards()

    @Query("SELECT * FROM budget_line ORDER BY position")
    abstract suspend fun budgetLines(): List<BudgetLineRow>

    @Insert
    abstract suspend fun putBudgetLines(lines: List<BudgetLineRow>)

    @Query("DELETE FROM budget_line")
    abstract suspend fun deleteBudgetLines()

    @Query("SELECT * FROM guest ORDER BY position")
    abstract suspend fun guests(): List<GuestRow>

    @Insert
    abstract suspend fun putGuests(guests: List<GuestRow>)

    @Query("DELETE FROM guest")
    abstract suspend fun deleteGuests()

    @Query("SELECT * FROM guest WHERE id = :id")
    abstract suspend fun guest(id: String): GuestRow?

    /** The server's word on a guest's status: what its answer to a change said. */
    @Query("UPDATE guest SET status = :status WHERE id = :id")
    abstract suspend fun setGuestStatusCopy(id: String, status: String)

    @Query("SELECT * FROM waiting_guest_status ORDER BY guestId")
    abstract suspend fun guestStatuses(): List<GuestStatusRow>

    @Query("SELECT * FROM waiting_guest_status WHERE guestId = :guestId")
    abstract suspend fun guestStatusOf(guestId: String): GuestStatusRow?

    @Upsert
    abstract suspend fun putGuestStatus(change: GuestStatusRow)

    @Query("DELETE FROM waiting_guest_status WHERE guestId = :guestId")
    abstract suspend fun deleteGuestStatus(guestId: String)

    @Query("DELETE FROM waiting_guest_status")
    abstract suspend fun deleteGuestStatuses()

    @Query("SELECT * FROM household ORDER BY position")
    abstract suspend fun households(): List<HouseholdRow>

    @Insert
    abstract suspend fun putHouseholds(households: List<HouseholdRow>)

    @Query("DELETE FROM household")
    abstract suspend fun deleteHouseholds()

    @Query("SELECT * FROM inspiration ORDER BY position")
    abstract suspend fun inspirations(): List<InspirationRow>

    @Query("SELECT * FROM inspiration_picture ORDER BY inspirationId, position")
    abstract suspend fun inspirationPictures(): List<InspirationPictureRow>

    @Insert
    abstract suspend fun putInspirations(inspirations: List<InspirationRow>)

    @Insert
    abstract suspend fun putInspirationPictures(pictures: List<InspirationPictureRow>)

    @Query("DELETE FROM inspiration")
    abstract suspend fun deleteInspirations()

    @Query("DELETE FROM inspiration_picture")
    abstract suspend fun deleteInspirationPictures()

    @Query("SELECT * FROM suggestion ORDER BY position")
    abstract suspend fun suggestions(): List<SuggestionRow>

    @Insert
    abstract suspend fun putSuggestions(suggestions: List<SuggestionRow>)

    @Query("DELETE FROM suggestion")
    abstract suspend fun deleteSuggestions()

    @Query("SELECT * FROM waiting_share ORDER BY seq")
    abstract suspend fun shares(): List<ShareRow>

    @Query("SELECT * FROM waiting_share WHERE seq = :seq")
    abstract suspend fun share(seq: Long): ShareRow?

    @Query("SELECT * FROM waiting_share WHERE inspirationId = :inspirationId")
    abstract suspend fun shareOf(inspirationId: String): ShareRow?

    @Upsert
    abstract suspend fun putShare(share: ShareRow)

    @Query("DELETE FROM waiting_share WHERE seq = :seq")
    abstract suspend fun deleteShare(seq: Long)

    @Query("DELETE FROM waiting_share")
    abstract suspend fun deleteShares()
}

/**
 * The phone's database: a copy of what the server said last, and the changes made on the phone
 * and shares that wait to be sent. Since version 2 it holds those, which exist nowhere else: every
 * change of the tables from here on carries over what is stored (a migration), and its layout is
 * committed under app/schemas so that this can be checked.
 */
@Database(
    entities = [
        PlannerRow::class, EntryRow::class, CategoryRow::class, VendorRow::class, MemberRow::class, ChangeRow::class, BoardRow::class,
        ShareRow::class, BudgetLineRow::class, GuestRow::class, HouseholdRow::class, GuestStatusRow::class, InspirationRow::class, InspirationPictureRow::class,
        SuggestionRow::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7)],
)
abstract class PlannerDatabase : RoomDatabase() {

    abstract fun planner(): PlannerDao

    companion object {

        fun onThePhone(context: Context): PlannerDatabase = Room.databaseBuilder(context, PlannerDatabase::class.java, "planner.db").build()
    }
}
