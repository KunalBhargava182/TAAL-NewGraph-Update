package com.musediagnostics.taal.lungs.data.db;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import com.musediagnostics.taal.lungs.data.db.dao.LungPatientDao;
import com.musediagnostics.taal.lungs.data.db.dao.LungPatientDao_Impl;
import com.musediagnostics.taal.lungs.data.db.dao.LungRecordingDao;
import com.musediagnostics.taal.lungs.data.db.dao.LungRecordingDao_Impl;
import com.musediagnostics.taal.lungs.data.db.dao.LungSessionDao;
import com.musediagnostics.taal.lungs.data.db.dao.LungSessionDao_Impl;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class LungsDatabase_Impl extends LungsDatabase {
  private volatile LungPatientDao _lungPatientDao;

  private volatile LungSessionDao _lungSessionDao;

  private volatile LungRecordingDao _lungRecordingDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(2) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `lung_patients` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sequenceNumber` INTEGER NOT NULL, `sex` TEXT NOT NULL, `age` INTEGER NOT NULL, `chestCircumferenceCm` REAL NOT NULL, `heightCm` REAL NOT NULL, `weightKg` REAL NOT NULL, `bmi` REAL NOT NULL, `createdAt` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `lung_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `patientId` INTEGER NOT NULL, `sessionNumber` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`patientId`) REFERENCES `lung_patients`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_lung_sessions_patientId` ON `lung_sessions` (`patientId`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `lung_recordings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `patientId` INTEGER NOT NULL, `sessionId` INTEGER NOT NULL, `pointCode` TEXT NOT NULL, `filePath` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`patientId`) REFERENCES `lung_patients`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`sessionId`) REFERENCES `lung_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_lung_recordings_patientId` ON `lung_recordings` (`patientId`)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_lung_recordings_sessionId` ON `lung_recordings` (`sessionId`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '52071480606f7cc0815660da353d3a55')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `lung_patients`");
        db.execSQL("DROP TABLE IF EXISTS `lung_sessions`");
        db.execSQL("DROP TABLE IF EXISTS `lung_recordings`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        db.execSQL("PRAGMA foreign_keys = ON");
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsLungPatients = new HashMap<String, TableInfo.Column>(9);
        _columnsLungPatients.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungPatients.put("sequenceNumber", new TableInfo.Column("sequenceNumber", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungPatients.put("sex", new TableInfo.Column("sex", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungPatients.put("age", new TableInfo.Column("age", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungPatients.put("chestCircumferenceCm", new TableInfo.Column("chestCircumferenceCm", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungPatients.put("heightCm", new TableInfo.Column("heightCm", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungPatients.put("weightKg", new TableInfo.Column("weightKg", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungPatients.put("bmi", new TableInfo.Column("bmi", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungPatients.put("createdAt", new TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysLungPatients = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesLungPatients = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoLungPatients = new TableInfo("lung_patients", _columnsLungPatients, _foreignKeysLungPatients, _indicesLungPatients);
        final TableInfo _existingLungPatients = TableInfo.read(db, "lung_patients");
        if (!_infoLungPatients.equals(_existingLungPatients)) {
          return new RoomOpenHelper.ValidationResult(false, "lung_patients(com.musediagnostics.taal.lungs.data.db.entity.LungPatientEntity).\n"
                  + " Expected:\n" + _infoLungPatients + "\n"
                  + " Found:\n" + _existingLungPatients);
        }
        final HashMap<String, TableInfo.Column> _columnsLungSessions = new HashMap<String, TableInfo.Column>(4);
        _columnsLungSessions.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungSessions.put("patientId", new TableInfo.Column("patientId", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungSessions.put("sessionNumber", new TableInfo.Column("sessionNumber", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungSessions.put("createdAt", new TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysLungSessions = new HashSet<TableInfo.ForeignKey>(1);
        _foreignKeysLungSessions.add(new TableInfo.ForeignKey("lung_patients", "CASCADE", "NO ACTION", Arrays.asList("patientId"), Arrays.asList("id")));
        final HashSet<TableInfo.Index> _indicesLungSessions = new HashSet<TableInfo.Index>(1);
        _indicesLungSessions.add(new TableInfo.Index("index_lung_sessions_patientId", false, Arrays.asList("patientId"), Arrays.asList("ASC")));
        final TableInfo _infoLungSessions = new TableInfo("lung_sessions", _columnsLungSessions, _foreignKeysLungSessions, _indicesLungSessions);
        final TableInfo _existingLungSessions = TableInfo.read(db, "lung_sessions");
        if (!_infoLungSessions.equals(_existingLungSessions)) {
          return new RoomOpenHelper.ValidationResult(false, "lung_sessions(com.musediagnostics.taal.lungs.data.db.entity.LungSessionEntity).\n"
                  + " Expected:\n" + _infoLungSessions + "\n"
                  + " Found:\n" + _existingLungSessions);
        }
        final HashMap<String, TableInfo.Column> _columnsLungRecordings = new HashMap<String, TableInfo.Column>(7);
        _columnsLungRecordings.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungRecordings.put("patientId", new TableInfo.Column("patientId", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungRecordings.put("sessionId", new TableInfo.Column("sessionId", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungRecordings.put("pointCode", new TableInfo.Column("pointCode", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungRecordings.put("filePath", new TableInfo.Column("filePath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungRecordings.put("durationSeconds", new TableInfo.Column("durationSeconds", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsLungRecordings.put("createdAt", new TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysLungRecordings = new HashSet<TableInfo.ForeignKey>(2);
        _foreignKeysLungRecordings.add(new TableInfo.ForeignKey("lung_patients", "CASCADE", "NO ACTION", Arrays.asList("patientId"), Arrays.asList("id")));
        _foreignKeysLungRecordings.add(new TableInfo.ForeignKey("lung_sessions", "CASCADE", "NO ACTION", Arrays.asList("sessionId"), Arrays.asList("id")));
        final HashSet<TableInfo.Index> _indicesLungRecordings = new HashSet<TableInfo.Index>(2);
        _indicesLungRecordings.add(new TableInfo.Index("index_lung_recordings_patientId", false, Arrays.asList("patientId"), Arrays.asList("ASC")));
        _indicesLungRecordings.add(new TableInfo.Index("index_lung_recordings_sessionId", false, Arrays.asList("sessionId"), Arrays.asList("ASC")));
        final TableInfo _infoLungRecordings = new TableInfo("lung_recordings", _columnsLungRecordings, _foreignKeysLungRecordings, _indicesLungRecordings);
        final TableInfo _existingLungRecordings = TableInfo.read(db, "lung_recordings");
        if (!_infoLungRecordings.equals(_existingLungRecordings)) {
          return new RoomOpenHelper.ValidationResult(false, "lung_recordings(com.musediagnostics.taal.lungs.data.db.entity.LungRecordingEntity).\n"
                  + " Expected:\n" + _infoLungRecordings + "\n"
                  + " Found:\n" + _existingLungRecordings);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "52071480606f7cc0815660da353d3a55", "d1cd3324ddbaaf5006edf59a3099ddc5");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "lung_patients","lung_sessions","lung_recordings");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    final boolean _supportsDeferForeignKeys = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP;
    try {
      if (!_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA foreign_keys = FALSE");
      }
      super.beginTransaction();
      if (_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA defer_foreign_keys = TRUE");
      }
      _db.execSQL("DELETE FROM `lung_patients`");
      _db.execSQL("DELETE FROM `lung_sessions`");
      _db.execSQL("DELETE FROM `lung_recordings`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      if (!_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA foreign_keys = TRUE");
      }
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(LungPatientDao.class, LungPatientDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(LungSessionDao.class, LungSessionDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(LungRecordingDao.class, LungRecordingDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public LungPatientDao lungPatientDao() {
    if (_lungPatientDao != null) {
      return _lungPatientDao;
    } else {
      synchronized(this) {
        if(_lungPatientDao == null) {
          _lungPatientDao = new LungPatientDao_Impl(this);
        }
        return _lungPatientDao;
      }
    }
  }

  @Override
  public LungSessionDao lungSessionDao() {
    if (_lungSessionDao != null) {
      return _lungSessionDao;
    } else {
      synchronized(this) {
        if(_lungSessionDao == null) {
          _lungSessionDao = new LungSessionDao_Impl(this);
        }
        return _lungSessionDao;
      }
    }
  }

  @Override
  public LungRecordingDao lungRecordingDao() {
    if (_lungRecordingDao != null) {
      return _lungRecordingDao;
    } else {
      synchronized(this) {
        if(_lungRecordingDao == null) {
          _lungRecordingDao = new LungRecordingDao_Impl(this);
        }
        return _lungRecordingDao;
      }
    }
  }
}
