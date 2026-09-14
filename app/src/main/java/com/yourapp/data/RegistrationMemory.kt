package com.yourapp.yamahaarranger.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One Registration Memory slot (spec section 6: "8 banks x 4 buttons").
 * Snapshots everything a real arranger's registration button recalls:
 * the loaded style + section + tempo, plus voice/split settings that
 * Phase 2's simpler VoiceLayer (right1/right2/left, split point) will
 * populate once dual/split voices exist — the columns are reserved now
 * so this table doesn't need a migration when that lands.
 */
@Entity(tableName = "registration_memory")
data class RegistrationMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankNumber: Int,       // 1-8
    val buttonNumber: Int,     // 1-4
    val label: String,
    val styleFileName: String?,
    val sectionName: String,   // e.g. "MainA"
    val tempoBpm: Int,
    val transposeSemitones: Int = 0,
    // Reserved for Phase 2's VoiceLayer (right1/right2/left program + split
    // point); nullable so existing rows stay valid once that's wired in.
    val right1ProgramId: Int? = null,
    val right2ProgramId: Int? = null,
    val leftProgramId: Int? = null,
    val splitPointNote: Int? = null
)

@Dao
interface RegistrationMemoryDao {
    @Query("SELECT * FROM registration_memory WHERE bankNumber = :bank ORDER BY buttonNumber")
    fun observeBank(bank: Int): Flow<List<RegistrationMemoryEntity>>

    @Query("SELECT * FROM registration_memory WHERE bankNumber = :bank AND buttonNumber = :button LIMIT 1")
    suspend fun get(bank: Int, button: Int): RegistrationMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RegistrationMemoryEntity)

    @Query("DELETE FROM registration_memory WHERE bankNumber = :bank AND buttonNumber = :button")
    suspend fun clear(bank: Int, button: Int)
}
