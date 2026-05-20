package com.musediagnostics.taal.lungs.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.musediagnostics.taal.lungs.data.db.dao.LungPatientDao
import com.musediagnostics.taal.lungs.data.db.dao.LungRecordingDao
import com.musediagnostics.taal.lungs.data.db.dao.LungSessionDao
import com.musediagnostics.taal.lungs.data.db.entity.LungPatientEntity
import com.musediagnostics.taal.lungs.data.db.entity.LungRecordingEntity
import com.musediagnostics.taal.lungs.data.db.entity.LungSessionEntity

@Database(
    entities = [LungPatientEntity::class, LungSessionEntity::class, LungRecordingEntity::class],
    version = 2,
    exportSchema = false
)
abstract class LungsDatabase : RoomDatabase() {

    abstract fun lungPatientDao(): LungPatientDao
    abstract fun lungSessionDao(): LungSessionDao
    abstract fun lungRecordingDao(): LungRecordingDao

    companion object {
        @Volatile
        private var INSTANCE: LungsDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create sessions table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `lung_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `patientId` INTEGER NOT NULL,
                        `sessionNumber` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`patientId`) REFERENCES `lung_patients`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_lung_sessions_patientId` ON `lung_sessions` (`patientId`)")

                // Create session 1 for every existing patient
                database.execSQL("""
                    INSERT INTO `lung_sessions` (`patientId`, `sessionNumber`, `createdAt`)
                    SELECT `id`, 1, `createdAt` FROM `lung_patients`
                """.trimIndent())

                // Recreate lung_recordings with sessionId FK
                // (ALTER TABLE cannot add FK constraints; table recreation is required)
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `lung_recordings_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `patientId` INTEGER NOT NULL,
                        `sessionId` INTEGER NOT NULL DEFAULT 0,
                        `pointCode` TEXT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `durationSeconds` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`patientId`) REFERENCES `lung_patients`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`sessionId`) REFERENCES `lung_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())

                // Copy recordings, joining to sessions to resolve sessionId
                database.execSQL("""
                    INSERT INTO `lung_recordings_new`
                        (`id`, `patientId`, `sessionId`, `pointCode`, `filePath`, `durationSeconds`, `createdAt`)
                    SELECT r.`id`, r.`patientId`, COALESCE(s.`id`, 0),
                           r.`pointCode`, r.`filePath`, r.`durationSeconds`, r.`createdAt`
                    FROM `lung_recordings` r
                    LEFT JOIN `lung_sessions` s
                        ON s.`patientId` = r.`patientId` AND s.`sessionNumber` = 1
                """.trimIndent())

                database.execSQL("DROP TABLE `lung_recordings`")
                database.execSQL("ALTER TABLE `lung_recordings_new` RENAME TO `lung_recordings`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_lung_recordings_patientId` ON `lung_recordings` (`patientId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_lung_recordings_sessionId` ON `lung_recordings` (`sessionId`)")
            }
        }

        fun getInstance(context: Context): LungsDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    LungsDatabase::class.java,
                    "lungs_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
