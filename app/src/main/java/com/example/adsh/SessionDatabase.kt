package com.example.adsh

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "session_events", primaryKeys = ["sessionId", "seq"])
data class SessionEventRow(
    val sessionId: String,
    val seq: Long,
    val type: String,
    val time: Long,
    val data: String,
)

@Dao
interface SessionEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(event: SessionEventRow)

    @Query("SELECT * FROM session_events WHERE sessionId = :id ORDER BY seq")
    suspend fun events(id: String): List<SessionEventRow>

    @Query("SELECT DISTINCT sessionId FROM session_events ORDER BY sessionId")
    suspend fun sessions(): List<String>
}

@Database(entities = [SessionEventRow::class], version = 1, exportSchema = false)
abstract class SessionDatabase : RoomDatabase() {
    abstract fun events(): SessionEventDao
}
