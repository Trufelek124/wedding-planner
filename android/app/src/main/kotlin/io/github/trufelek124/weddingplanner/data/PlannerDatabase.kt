package io.github.trufelek124.weddingplanner.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Dao
abstract class PlannerDao {

    /** Everything that is stored, as it was at one moment; null while nothing was ever loaded. */
    @Transaction
    open suspend fun stored(): Stored? {
        val planner = planner() ?: return null
        return Stored(planner, entries(), categories(), vendors(), members())
    }

    /** Puts a new load in the place of the last one, all of it or nothing. */
    @Transaction
    open suspend fun replace(stored: Stored) {
        forget()
        put(stored.planner)
        putEntries(stored.entries)
        putCategories(stored.categories)
        putVendors(stored.vendors)
        putMembers(stored.members)
    }

    /** Removes everything: for a sign-out. */
    @Transaction
    open suspend fun forget() {
        deletePlanner()
        deleteEntries()
        deleteCategories()
        deleteVendors()
        deleteMembers()
    }

    @Query("SELECT * FROM planner WHERE id = 1")
    abstract suspend fun planner(): PlannerRow?

    @Query("SELECT * FROM entry ORDER BY position")
    abstract suspend fun entries(): List<EntryRow>

    @Query("SELECT * FROM category ORDER BY position")
    abstract suspend fun categories(): List<CategoryRow>

    @Query("SELECT * FROM vendor")
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
}

/**
 * The phone's copy of the planner. Until the phone keeps something the server does not have
 * (changes waiting to be sent), a copy made by an older version of the app is simply dropped and
 * loaded again; from then on each change of the tables needs a migration.
 */
@Database(
    entities = [PlannerRow::class, EntryRow::class, CategoryRow::class, VendorRow::class, MemberRow::class],
    version = 1,
    exportSchema = true,
)
abstract class PlannerDatabase : RoomDatabase() {

    abstract fun planner(): PlannerDao

    companion object {

        /** The tables a screen has to hear about when they change. */
        val TABLES = arrayOf("planner", "entry", "category", "vendor", "member")

        fun onThePhone(context: Context): PlannerDatabase =
            Room.databaseBuilder(context, PlannerDatabase::class.java, "planner.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
