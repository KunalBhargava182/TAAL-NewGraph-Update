package com.musediagnostics.taal.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.musediagnostics.taal.app.data.db.dao.PatientDao
import com.musediagnostics.taal.app.data.db.dao.RecordingDao
import com.musediagnostics.taal.app.data.db.entity.PatientEntity
import com.musediagnostics.taal.app.data.db.entity.RecordingEntity

@Database(
    entities = [PatientEntity::class, RecordingEntity::class],
    version = 1,
    exportSchema = false
)
abstract class TaalDatabase : RoomDatabase() {

    abstract fun patientDao(): PatientDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var INSTANCE: TaalDatabase? = null

        fun getInstance(context: Context): TaalDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TaalDatabase::class.java,
                    "taal_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
