package app.keryx.bridge.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_relay")
data class PendingRelay(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val payloadJson: String,
    val attempts: Int = 0,
    val failed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
