package app.keryx.bridge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PendingRelayDao {

    @Insert
    suspend fun insert(item: PendingRelay): Long

    @Query("SELECT * FROM pending_relay WHERE failed = 0 ORDER BY createdAt ASC LIMIT 50")
    suspend fun getAll(): List<PendingRelay>

    @Query("DELETE FROM pending_relay WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE pending_relay SET attempts = attempts + 1 WHERE id = :id")
    suspend fun incrementAttempts(id: Long)

    @Query("UPDATE pending_relay SET failed = 1 WHERE attempts >= :maxAttempts AND failed = 0")
    suspend fun markExhaustedAsFailed(maxAttempts: Int): Int

    @Query("DELETE FROM pending_relay WHERE failed = 1")
    suspend fun purgeFailedRows()

    @Query("SELECT COUNT(*) FROM pending_relay WHERE failed = 0")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM pending_relay WHERE failed = 1")
    suspend fun countFailed(): Int

    @Query("SELECT COUNT(*) FROM pending_relay")
    suspend fun count(): Int
}
