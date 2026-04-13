package com.musediagnostics.taal.lungs.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.musediagnostics.taal.lungs.data.db.dao.LungPatientDao
import com.musediagnostics.taal.lungs.data.db.dao.LungRecordingDao
import com.musediagnostics.taal.lungs.data.db.entity.LungPatientEntity
import com.musediagnostics.taal.lungs.data.db.entity.LungRecordingEntity

@Database(
    entities = [LungPatientEntity::class, LungRecordingEntity::class],
    version = 1,
    exportSchema = false
)
abstract class LungsDatabase : RoomDatabase() {

    abstract fun lungPatientDao(): LungPatientDao
    abstract fun lungRecordingDao(): LungRecordingDao

    companion object {
        @Volatile
        private var INSTANCE: LungsDatabase? = null

        fun getInstance(context: Context): LungsDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    LungsDatabase::class.java,
                    "lungs_database"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
