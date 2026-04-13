package com.musediagnostics.taal.app.data.db.dao;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.musediagnostics.taal.app.data.db.entity.RecordingEntity;
import com.musediagnostics.taal.app.data.db.entity.RecordingWithPatient;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class RecordingDao_Impl implements RecordingDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<RecordingEntity> __insertionAdapterOfRecordingEntity;

  private final EntityDeletionOrUpdateAdapter<RecordingEntity> __deletionAdapterOfRecordingEntity;

  private final EntityDeletionOrUpdateAdapter<RecordingEntity> __updateAdapterOfRecordingEntity;

  private final SharedSQLiteStatement __preparedStmtOfRenameRecording;

  public RecordingDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfRecordingEntity = new EntityInsertionAdapter<RecordingEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `recordings` (`id`,`patientId`,`filePath`,`fileName`,`filterType`,`durationSeconds`,`bpm`,`preAmplification`,`isEmergency`,`notes`,`createdAt`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final RecordingEntity entity) {
        statement.bindLong(1, entity.getId());
        if (entity.getPatientId() == null) {
          statement.bindNull(2);
        } else {
          statement.bindLong(2, entity.getPatientId());
        }
        statement.bindString(3, entity.getFilePath());
        statement.bindString(4, entity.getFileName());
        statement.bindString(5, entity.getFilterType());
        statement.bindLong(6, entity.getDurationSeconds());
        statement.bindLong(7, entity.getBpm());
        statement.bindDouble(8, entity.getPreAmplification());
        final int _tmp = entity.isEmergency() ? 1 : 0;
        statement.bindLong(9, _tmp);
        statement.bindString(10, entity.getNotes());
        statement.bindLong(11, entity.getCreatedAt());
      }
    };
    this.__deletionAdapterOfRecordingEntity = new EntityDeletionOrUpdateAdapter<RecordingEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "DELETE FROM `recordings` WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final RecordingEntity entity) {
        statement.bindLong(1, entity.getId());
      }
    };
    this.__updateAdapterOfRecordingEntity = new EntityDeletionOrUpdateAdapter<RecordingEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `recordings` SET `id` = ?,`patientId` = ?,`filePath` = ?,`fileName` = ?,`filterType` = ?,`durationSeconds` = ?,`bpm` = ?,`preAmplification` = ?,`isEmergency` = ?,`notes` = ?,`createdAt` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final RecordingEntity entity) {
        statement.bindLong(1, entity.getId());
        if (entity.getPatientId() == null) {
          statement.bindNull(2);
        } else {
          statement.bindLong(2, entity.getPatientId());
        }
        statement.bindString(3, entity.getFilePath());
        statement.bindString(4, entity.getFileName());
        statement.bindString(5, entity.getFilterType());
        statement.bindLong(6, entity.getDurationSeconds());
        statement.bindLong(7, entity.getBpm());
        statement.bindDouble(8, entity.getPreAmplification());
        final int _tmp = entity.isEmergency() ? 1 : 0;
        statement.bindLong(9, _tmp);
        statement.bindString(10, entity.getNotes());
        statement.bindLong(11, entity.getCreatedAt());
        statement.bindLong(12, entity.getId());
      }
    };
    this.__preparedStmtOfRenameRecording = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE recordings SET fileName = ? WHERE id = ?";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final RecordingEntity recording,
      final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfRecordingEntity.insertAndReturnId(recording);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object delete(final RecordingEntity recording,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __deletionAdapterOfRecordingEntity.handle(recording);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object update(final RecordingEntity recording,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfRecordingEntity.handle(recording);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object renameRecording(final long id, final String newName,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfRenameRecording.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, newName);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfRenameRecording.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<RecordingEntity>> getAllRecordings() {
    final String _sql = "SELECT * FROM recordings ORDER BY createdAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"recordings"}, new Callable<List<RecordingEntity>>() {
      @Override
      @NonNull
      public List<RecordingEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfPatientId = CursorUtil.getColumnIndexOrThrow(_cursor, "patientId");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfFileName = CursorUtil.getColumnIndexOrThrow(_cursor, "fileName");
          final int _cursorIndexOfFilterType = CursorUtil.getColumnIndexOrThrow(_cursor, "filterType");
          final int _cursorIndexOfDurationSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "durationSeconds");
          final int _cursorIndexOfBpm = CursorUtil.getColumnIndexOrThrow(_cursor, "bpm");
          final int _cursorIndexOfPreAmplification = CursorUtil.getColumnIndexOrThrow(_cursor, "preAmplification");
          final int _cursorIndexOfIsEmergency = CursorUtil.getColumnIndexOrThrow(_cursor, "isEmergency");
          final int _cursorIndexOfNotes = CursorUtil.getColumnIndexOrThrow(_cursor, "notes");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final List<RecordingEntity> _result = new ArrayList<RecordingEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final RecordingEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final Long _tmpPatientId;
            if (_cursor.isNull(_cursorIndexOfPatientId)) {
              _tmpPatientId = null;
            } else {
              _tmpPatientId = _cursor.getLong(_cursorIndexOfPatientId);
            }
            final String _tmpFilePath;
            _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            final String _tmpFileName;
            _tmpFileName = _cursor.getString(_cursorIndexOfFileName);
            final String _tmpFilterType;
            _tmpFilterType = _cursor.getString(_cursorIndexOfFilterType);
            final int _tmpDurationSeconds;
            _tmpDurationSeconds = _cursor.getInt(_cursorIndexOfDurationSeconds);
            final int _tmpBpm;
            _tmpBpm = _cursor.getInt(_cursorIndexOfBpm);
            final float _tmpPreAmplification;
            _tmpPreAmplification = _cursor.getFloat(_cursorIndexOfPreAmplification);
            final boolean _tmpIsEmergency;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsEmergency);
            _tmpIsEmergency = _tmp != 0;
            final String _tmpNotes;
            _tmpNotes = _cursor.getString(_cursorIndexOfNotes);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _item = new RecordingEntity(_tmpId,_tmpPatientId,_tmpFilePath,_tmpFileName,_tmpFilterType,_tmpDurationSeconds,_tmpBpm,_tmpPreAmplification,_tmpIsEmergency,_tmpNotes,_tmpCreatedAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getRecordingById(final long id,
      final Continuation<? super RecordingEntity> $completion) {
    final String _sql = "SELECT * FROM recordings WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, id);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<RecordingEntity>() {
      @Override
      @Nullable
      public RecordingEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfPatientId = CursorUtil.getColumnIndexOrThrow(_cursor, "patientId");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfFileName = CursorUtil.getColumnIndexOrThrow(_cursor, "fileName");
          final int _cursorIndexOfFilterType = CursorUtil.getColumnIndexOrThrow(_cursor, "filterType");
          final int _cursorIndexOfDurationSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "durationSeconds");
          final int _cursorIndexOfBpm = CursorUtil.getColumnIndexOrThrow(_cursor, "bpm");
          final int _cursorIndexOfPreAmplification = CursorUtil.getColumnIndexOrThrow(_cursor, "preAmplification");
          final int _cursorIndexOfIsEmergency = CursorUtil.getColumnIndexOrThrow(_cursor, "isEmergency");
          final int _cursorIndexOfNotes = CursorUtil.getColumnIndexOrThrow(_cursor, "notes");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final RecordingEntity _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final Long _tmpPatientId;
            if (_cursor.isNull(_cursorIndexOfPatientId)) {
              _tmpPatientId = null;
            } else {
              _tmpPatientId = _cursor.getLong(_cursorIndexOfPatientId);
            }
            final String _tmpFilePath;
            _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            final String _tmpFileName;
            _tmpFileName = _cursor.getString(_cursorIndexOfFileName);
            final String _tmpFilterType;
            _tmpFilterType = _cursor.getString(_cursorIndexOfFilterType);
            final int _tmpDurationSeconds;
            _tmpDurationSeconds = _cursor.getInt(_cursorIndexOfDurationSeconds);
            final int _tmpBpm;
            _tmpBpm = _cursor.getInt(_cursorIndexOfBpm);
            final float _tmpPreAmplification;
            _tmpPreAmplification = _cursor.getFloat(_cursorIndexOfPreAmplification);
            final boolean _tmpIsEmergency;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsEmergency);
            _tmpIsEmergency = _tmp != 0;
            final String _tmpNotes;
            _tmpNotes = _cursor.getString(_cursorIndexOfNotes);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _result = new RecordingEntity(_tmpId,_tmpPatientId,_tmpFilePath,_tmpFileName,_tmpFilterType,_tmpDurationSeconds,_tmpBpm,_tmpPreAmplification,_tmpIsEmergency,_tmpNotes,_tmpCreatedAt);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<RecordingEntity>> getRecordingsForPatient(final long patientId) {
    final String _sql = "SELECT * FROM recordings WHERE patientId = ? ORDER BY createdAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, patientId);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"recordings"}, new Callable<List<RecordingEntity>>() {
      @Override
      @NonNull
      public List<RecordingEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfPatientId = CursorUtil.getColumnIndexOrThrow(_cursor, "patientId");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfFileName = CursorUtil.getColumnIndexOrThrow(_cursor, "fileName");
          final int _cursorIndexOfFilterType = CursorUtil.getColumnIndexOrThrow(_cursor, "filterType");
          final int _cursorIndexOfDurationSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "durationSeconds");
          final int _cursorIndexOfBpm = CursorUtil.getColumnIndexOrThrow(_cursor, "bpm");
          final int _cursorIndexOfPreAmplification = CursorUtil.getColumnIndexOrThrow(_cursor, "preAmplification");
          final int _cursorIndexOfIsEmergency = CursorUtil.getColumnIndexOrThrow(_cursor, "isEmergency");
          final int _cursorIndexOfNotes = CursorUtil.getColumnIndexOrThrow(_cursor, "notes");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final List<RecordingEntity> _result = new ArrayList<RecordingEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final RecordingEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final Long _tmpPatientId;
            if (_cursor.isNull(_cursorIndexOfPatientId)) {
              _tmpPatientId = null;
            } else {
              _tmpPatientId = _cursor.getLong(_cursorIndexOfPatientId);
            }
            final String _tmpFilePath;
            _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            final String _tmpFileName;
            _tmpFileName = _cursor.getString(_cursorIndexOfFileName);
            final String _tmpFilterType;
            _tmpFilterType = _cursor.getString(_cursorIndexOfFilterType);
            final int _tmpDurationSeconds;
            _tmpDurationSeconds = _cursor.getInt(_cursorIndexOfDurationSeconds);
            final int _tmpBpm;
            _tmpBpm = _cursor.getInt(_cursorIndexOfBpm);
            final float _tmpPreAmplification;
            _tmpPreAmplification = _cursor.getFloat(_cursorIndexOfPreAmplification);
            final boolean _tmpIsEmergency;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsEmergency);
            _tmpIsEmergency = _tmp != 0;
            final String _tmpNotes;
            _tmpNotes = _cursor.getString(_cursorIndexOfNotes);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _item = new RecordingEntity(_tmpId,_tmpPatientId,_tmpFilePath,_tmpFileName,_tmpFilterType,_tmpDurationSeconds,_tmpBpm,_tmpPreAmplification,_tmpIsEmergency,_tmpNotes,_tmpCreatedAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getMostRecentRecording(final Continuation<? super RecordingEntity> $completion) {
    final String _sql = "SELECT * FROM recordings ORDER BY createdAt DESC LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<RecordingEntity>() {
      @Override
      @Nullable
      public RecordingEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfPatientId = CursorUtil.getColumnIndexOrThrow(_cursor, "patientId");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfFileName = CursorUtil.getColumnIndexOrThrow(_cursor, "fileName");
          final int _cursorIndexOfFilterType = CursorUtil.getColumnIndexOrThrow(_cursor, "filterType");
          final int _cursorIndexOfDurationSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "durationSeconds");
          final int _cursorIndexOfBpm = CursorUtil.getColumnIndexOrThrow(_cursor, "bpm");
          final int _cursorIndexOfPreAmplification = CursorUtil.getColumnIndexOrThrow(_cursor, "preAmplification");
          final int _cursorIndexOfIsEmergency = CursorUtil.getColumnIndexOrThrow(_cursor, "isEmergency");
          final int _cursorIndexOfNotes = CursorUtil.getColumnIndexOrThrow(_cursor, "notes");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final RecordingEntity _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final Long _tmpPatientId;
            if (_cursor.isNull(_cursorIndexOfPatientId)) {
              _tmpPatientId = null;
            } else {
              _tmpPatientId = _cursor.getLong(_cursorIndexOfPatientId);
            }
            final String _tmpFilePath;
            _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            final String _tmpFileName;
            _tmpFileName = _cursor.getString(_cursorIndexOfFileName);
            final String _tmpFilterType;
            _tmpFilterType = _cursor.getString(_cursorIndexOfFilterType);
            final int _tmpDurationSeconds;
            _tmpDurationSeconds = _cursor.getInt(_cursorIndexOfDurationSeconds);
            final int _tmpBpm;
            _tmpBpm = _cursor.getInt(_cursorIndexOfBpm);
            final float _tmpPreAmplification;
            _tmpPreAmplification = _cursor.getFloat(_cursorIndexOfPreAmplification);
            final boolean _tmpIsEmergency;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsEmergency);
            _tmpIsEmergency = _tmp != 0;
            final String _tmpNotes;
            _tmpNotes = _cursor.getString(_cursorIndexOfNotes);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _result = new RecordingEntity(_tmpId,_tmpPatientId,_tmpFilePath,_tmpFileName,_tmpFilterType,_tmpDurationSeconds,_tmpBpm,_tmpPreAmplification,_tmpIsEmergency,_tmpNotes,_tmpCreatedAt);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<RecordingEntity>> getEmergencyRecordings() {
    final String _sql = "SELECT * FROM recordings WHERE isEmergency = 1 ORDER BY createdAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"recordings"}, new Callable<List<RecordingEntity>>() {
      @Override
      @NonNull
      public List<RecordingEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfPatientId = CursorUtil.getColumnIndexOrThrow(_cursor, "patientId");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfFileName = CursorUtil.getColumnIndexOrThrow(_cursor, "fileName");
          final int _cursorIndexOfFilterType = CursorUtil.getColumnIndexOrThrow(_cursor, "filterType");
          final int _cursorIndexOfDurationSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "durationSeconds");
          final int _cursorIndexOfBpm = CursorUtil.getColumnIndexOrThrow(_cursor, "bpm");
          final int _cursorIndexOfPreAmplification = CursorUtil.getColumnIndexOrThrow(_cursor, "preAmplification");
          final int _cursorIndexOfIsEmergency = CursorUtil.getColumnIndexOrThrow(_cursor, "isEmergency");
          final int _cursorIndexOfNotes = CursorUtil.getColumnIndexOrThrow(_cursor, "notes");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final List<RecordingEntity> _result = new ArrayList<RecordingEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final RecordingEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final Long _tmpPatientId;
            if (_cursor.isNull(_cursorIndexOfPatientId)) {
              _tmpPatientId = null;
            } else {
              _tmpPatientId = _cursor.getLong(_cursorIndexOfPatientId);
            }
            final String _tmpFilePath;
            _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            final String _tmpFileName;
            _tmpFileName = _cursor.getString(_cursorIndexOfFileName);
            final String _tmpFilterType;
            _tmpFilterType = _cursor.getString(_cursorIndexOfFilterType);
            final int _tmpDurationSeconds;
            _tmpDurationSeconds = _cursor.getInt(_cursorIndexOfDurationSeconds);
            final int _tmpBpm;
            _tmpBpm = _cursor.getInt(_cursorIndexOfBpm);
            final float _tmpPreAmplification;
            _tmpPreAmplification = _cursor.getFloat(_cursorIndexOfPreAmplification);
            final boolean _tmpIsEmergency;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsEmergency);
            _tmpIsEmergency = _tmp != 0;
            final String _tmpNotes;
            _tmpNotes = _cursor.getString(_cursorIndexOfNotes);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _item = new RecordingEntity(_tmpId,_tmpPatientId,_tmpFilePath,_tmpFileName,_tmpFilterType,_tmpDurationSeconds,_tmpBpm,_tmpPreAmplification,_tmpIsEmergency,_tmpNotes,_tmpCreatedAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<List<RecordingWithPatient>> getRecordingsWithPatients() {
    final String _sql = "\n"
            + "        SELECT r.*, p.fullName AS patientName, p.patientId AS patientIdentifier\n"
            + "        FROM recordings r\n"
            + "        LEFT JOIN patients p ON r.patientId = p.id\n"
            + "        ORDER BY r.createdAt DESC\n"
            + "    ";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"recordings",
        "patients"}, new Callable<List<RecordingWithPatient>>() {
      @Override
      @NonNull
      public List<RecordingWithPatient> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfPatientId = CursorUtil.getColumnIndexOrThrow(_cursor, "patientId");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfFileName = CursorUtil.getColumnIndexOrThrow(_cursor, "fileName");
          final int _cursorIndexOfFilterType = CursorUtil.getColumnIndexOrThrow(_cursor, "filterType");
          final int _cursorIndexOfDurationSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "durationSeconds");
          final int _cursorIndexOfBpm = CursorUtil.getColumnIndexOrThrow(_cursor, "bpm");
          final int _cursorIndexOfPreAmplification = CursorUtil.getColumnIndexOrThrow(_cursor, "preAmplification");
          final int _cursorIndexOfIsEmergency = CursorUtil.getColumnIndexOrThrow(_cursor, "isEmergency");
          final int _cursorIndexOfNotes = CursorUtil.getColumnIndexOrThrow(_cursor, "notes");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfPatientName = CursorUtil.getColumnIndexOrThrow(_cursor, "patientName");
          final int _cursorIndexOfPatientIdentifier = CursorUtil.getColumnIndexOrThrow(_cursor, "patientIdentifier");
          final List<RecordingWithPatient> _result = new ArrayList<RecordingWithPatient>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final RecordingWithPatient _item;
            final String _tmpPatientName;
            if (_cursor.isNull(_cursorIndexOfPatientName)) {
              _tmpPatientName = null;
            } else {
              _tmpPatientName = _cursor.getString(_cursorIndexOfPatientName);
            }
            final String _tmpPatientIdentifier;
            if (_cursor.isNull(_cursorIndexOfPatientIdentifier)) {
              _tmpPatientIdentifier = null;
            } else {
              _tmpPatientIdentifier = _cursor.getString(_cursorIndexOfPatientIdentifier);
            }
            final RecordingEntity _tmpRecording;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final Long _tmpPatientId;
            if (_cursor.isNull(_cursorIndexOfPatientId)) {
              _tmpPatientId = null;
            } else {
              _tmpPatientId = _cursor.getLong(_cursorIndexOfPatientId);
            }
            final String _tmpFilePath;
            _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            final String _tmpFileName;
            _tmpFileName = _cursor.getString(_cursorIndexOfFileName);
            final String _tmpFilterType;
            _tmpFilterType = _cursor.getString(_cursorIndexOfFilterType);
            final int _tmpDurationSeconds;
            _tmpDurationSeconds = _cursor.getInt(_cursorIndexOfDurationSeconds);
            final int _tmpBpm;
            _tmpBpm = _cursor.getInt(_cursorIndexOfBpm);
            final float _tmpPreAmplification;
            _tmpPreAmplification = _cursor.getFloat(_cursorIndexOfPreAmplification);
            final boolean _tmpIsEmergency;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsEmergency);
            _tmpIsEmergency = _tmp != 0;
            final String _tmpNotes;
            _tmpNotes = _cursor.getString(_cursorIndexOfNotes);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _tmpRecording = new RecordingEntity(_tmpId,_tmpPatientId,_tmpFilePath,_tmpFileName,_tmpFilterType,_tmpDurationSeconds,_tmpBpm,_tmpPreAmplification,_tmpIsEmergency,_tmpNotes,_tmpCreatedAt);
            _item = new RecordingWithPatient(_tmpRecording,_tmpPatientName,_tmpPatientIdentifier);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<List<RecordingWithPatient>> searchRecordingsWithPatients(final String query) {
    final String _sql = "\n"
            + "        SELECT r.*, p.fullName AS patientName, p.patientId AS patientIdentifier\n"
            + "        FROM recordings r\n"
            + "        LEFT JOIN patients p ON r.patientId = p.id\n"
            + "        WHERE p.fullName LIKE '%' || ? || '%'\n"
            + "           OR p.patientId LIKE '%' || ? || '%'\n"
            + "           OR r.fileName LIKE '%' || ? || '%'\n"
            + "        ORDER BY r.createdAt DESC\n"
            + "    ";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 3);
    int _argIndex = 1;
    _statement.bindString(_argIndex, query);
    _argIndex = 2;
    _statement.bindString(_argIndex, query);
    _argIndex = 3;
    _statement.bindString(_argIndex, query);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"recordings",
        "patients"}, new Callable<List<RecordingWithPatient>>() {
      @Override
      @NonNull
      public List<RecordingWithPatient> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfPatientId = CursorUtil.getColumnIndexOrThrow(_cursor, "patientId");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfFileName = CursorUtil.getColumnIndexOrThrow(_cursor, "fileName");
          final int _cursorIndexOfFilterType = CursorUtil.getColumnIndexOrThrow(_cursor, "filterType");
          final int _cursorIndexOfDurationSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "durationSeconds");
          final int _cursorIndexOfBpm = CursorUtil.getColumnIndexOrThrow(_cursor, "bpm");
          final int _cursorIndexOfPreAmplification = CursorUtil.getColumnIndexOrThrow(_cursor, "preAmplification");
          final int _cursorIndexOfIsEmergency = CursorUtil.getColumnIndexOrThrow(_cursor, "isEmergency");
          final int _cursorIndexOfNotes = CursorUtil.getColumnIndexOrThrow(_cursor, "notes");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfPatientName = CursorUtil.getColumnIndexOrThrow(_cursor, "patientName");
          final int _cursorIndexOfPatientIdentifier = CursorUtil.getColumnIndexOrThrow(_cursor, "patientIdentifier");
          final List<RecordingWithPatient> _result = new ArrayList<RecordingWithPatient>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final RecordingWithPatient _item;
            final String _tmpPatientName;
            if (_cursor.isNull(_cursorIndexOfPatientName)) {
              _tmpPatientName = null;
            } else {
              _tmpPatientName = _cursor.getString(_cursorIndexOfPatientName);
            }
            final String _tmpPatientIdentifier;
            if (_cursor.isNull(_cursorIndexOfPatientIdentifier)) {
              _tmpPatientIdentifier = null;
            } else {
              _tmpPatientIdentifier = _cursor.getString(_cursorIndexOfPatientIdentifier);
            }
            final RecordingEntity _tmpRecording;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final Long _tmpPatientId;
            if (_cursor.isNull(_cursorIndexOfPatientId)) {
              _tmpPatientId = null;
            } else {
              _tmpPatientId = _cursor.getLong(_cursorIndexOfPatientId);
            }
            final String _tmpFilePath;
            _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            final String _tmpFileName;
            _tmpFileName = _cursor.getString(_cursorIndexOfFileName);
            final String _tmpFilterType;
            _tmpFilterType = _cursor.getString(_cursorIndexOfFilterType);
            final int _tmpDurationSeconds;
            _tmpDurationSeconds = _cursor.getInt(_cursorIndexOfDurationSeconds);
            final int _tmpBpm;
            _tmpBpm = _cursor.getInt(_cursorIndexOfBpm);
            final float _tmpPreAmplification;
            _tmpPreAmplification = _cursor.getFloat(_cursorIndexOfPreAmplification);
            final boolean _tmpIsEmergency;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsEmergency);
            _tmpIsEmergency = _tmp != 0;
            final String _tmpNotes;
            _tmpNotes = _cursor.getString(_cursorIndexOfNotes);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _tmpRecording = new RecordingEntity(_tmpId,_tmpPatientId,_tmpFilePath,_tmpFileName,_tmpFilterType,_tmpDurationSeconds,_tmpBpm,_tmpPreAmplification,_tmpIsEmergency,_tmpNotes,_tmpCreatedAt);
            _item = new RecordingWithPatient(_tmpRecording,_tmpPatientName,_tmpPatientIdentifier);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
