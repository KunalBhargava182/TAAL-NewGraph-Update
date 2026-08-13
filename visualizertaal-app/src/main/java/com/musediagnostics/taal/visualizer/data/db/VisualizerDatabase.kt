package com.musediagnostics.taal.visualizer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.musediagnostics.taal.visualizer.data.db.dao.RecordingDao
import com.musediagnostics.taal.visualizer.data.db.dao.SessionDao
import com.musediagnostics.taal.visualizer.data.db.entity.RecordingEntity
import com.musediagnostics.taal.visualizer.data.db.entity.SessionEntity

@Database(entities = [SessionEntity::class, RecordingEntity::class], version = 2)
abstract class VisualizerDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile private var instance: VisualizerDatabase? = null

        fun getInstance(context: Context): VisualizerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VisualizerDatabase::class.java,
                    "visualizer_database"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
