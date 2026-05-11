package com.musediagnostics.taal.lungs.data.db.dao;

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
import com.musediagnostics.taal.lungs.data.db.entity.LungPatientEntity;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
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
public final class LungPatientDao_Impl implements LungPatientDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<LungPatientEntity> __insertionAdapterOfLungPatientEntity;

  private final EntityDeletionOrUpdateAdapter<LungPatientEntity> __updateAdapterOfLungPatientEntity;

  private final SharedSQLiteStatement __preparedStmtOfDeleteById;

  public LungPatientDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfLungPatientEntity = new EntityInsertionAdapter<LungPatientEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `lung_patients` (`id`,`sequenceNumber`,`sex`,`age`,`chestCircumferenceCm`,`heightCm`,`weightKg`,`bmi`,`createdAt`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final LungPatientEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getSequenceNumber());
        statement.bindString(3, entity.getSex());
        statement.bindLong(4, entity.getAge());
        statement.bindDouble(5, entity.getChestCircumferenceCm());
        statement.bindDouble(6, entity.getHeightCm());
        statement.bindDouble(7, entity.getWeightKg());
        statement.bindDouble(8, entity.getBmi());
        statement.bindLong(9, entity.getCreatedAt());
      }
    };
    this.__updateAdapterOfLungPatientEntity = new EntityDeletionOrUpdateAdapter<LungPatientEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `lung_patients` SET `id` = ?,`sequenceNumber` = ?,`sex` = ?,`age` = ?,`chestCircumferenceCm` = ?,`heightCm` = ?,`weightKg` = ?,`bmi` = ?,`createdAt` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final LungPatientEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getSequenceNumber());
        statement.bindString(3, entity.getSex());
        statement.bindLong(4, entity.getAge());
        statement.bindDouble(5, entity.getChestCircumferenceCm());
        statement.bindDouble(6, entity.getHeightCm());
        statement.bindDouble(7, entity.getWeightKg());
        statement.bindDouble(8, entity.getBmi());
        statement.bindLong(9, entity.getCreatedAt());
        statement.bindLong(10, entity.getId());
      }
    };
    this.__preparedStmtOfDeleteById = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM lung_patients WHERE id = ?";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final LungPatientEntity patient,
      final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfLungPatientEntity.insertAndReturnId(patient);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object update(final LungPatientEntity patient,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfLungPatientEntity.handle(patient);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteById(final long id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteById.acquire();
        int _argIndex = 1;
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
          __preparedStmtOfDeleteById.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<LungPatientEntity>> getAllPatients() {
    final String _sql = "SELECT * FROM lung_patients ORDER BY sequenceNumber ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"lung_patients"}, new Callable<List<LungPatientEntity>>() {
      @Override
      @NonNull
      public List<LungPatientEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfSequenceNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "sequenceNumber");
          final int _cursorIndexOfSex = CursorUtil.getColumnIndexOrThrow(_cursor, "sex");
          final int _cursorIndexOfAge = CursorUtil.getColumnIndexOrThrow(_cursor, "age");
          final int _cursorIndexOfChestCircumferenceCm = CursorUtil.getColumnIndexOrThrow(_cursor, "chestCircumferenceCm");
          final int _cursorIndexOfHeightCm = CursorUtil.getColumnIndexOrThrow(_cursor, "heightCm");
          final int _cursorIndexOfWeightKg = CursorUtil.getColumnIndexOrThrow(_cursor, "weightKg");
          final int _cursorIndexOfBmi = CursorUtil.getColumnIndexOrThrow(_cursor, "bmi");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final List<LungPatientEntity> _result = new ArrayList<LungPatientEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final LungPatientEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final int _tmpSequenceNumber;
            _tmpSequenceNumber = _cursor.getInt(_cursorIndexOfSequenceNumber);
            final String _tmpSex;
            _tmpSex = _cursor.getString(_cursorIndexOfSex);
            final int _tmpAge;
            _tmpAge = _cursor.getInt(_cursorIndexOfAge);
            final float _tmpChestCircumferenceCm;
            _tmpChestCircumferenceCm = _cursor.getFloat(_cursorIndexOfChestCircumferenceCm);
            final float _tmpHeightCm;
            _tmpHeightCm = _cursor.getFloat(_cursorIndexOfHeightCm);
            final float _tmpWeightKg;
            _tmpWeightKg = _cursor.getFloat(_cursorIndexOfWeightKg);
            final float _tmpBmi;
            _tmpBmi = _cursor.getFloat(_cursorIndexOfBmi);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _item = new LungPatientEntity(_tmpId,_tmpSequenceNumber,_tmpSex,_tmpAge,_tmpChestCircumferenceCm,_tmpHeightCm,_tmpWeightKg,_tmpBmi,_tmpCreatedAt);
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
  public Object getById(final long id, final Continuation<? super LungPatientEntity> $completion) {
    final String _sql = "SELECT * FROM lung_patients WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, id);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<LungPatientEntity>() {
      @Override
      @Nullable
      public LungPatientEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfSequenceNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "sequenceNumber");
          final int _cursorIndexOfSex = CursorUtil.getColumnIndexOrThrow(_cursor, "sex");
          final int _cursorIndexOfAge = CursorUtil.getColumnIndexOrThrow(_cursor, "age");
          final int _cursorIndexOfChestCircumferenceCm = CursorUtil.getColumnIndexOrThrow(_cursor, "chestCircumferenceCm");
          final int _cursorIndexOfHeightCm = CursorUtil.getColumnIndexOrThrow(_cursor, "heightCm");
          final int _cursorIndexOfWeightKg = CursorUtil.getColumnIndexOrThrow(_cursor, "weightKg");
          final int _cursorIndexOfBmi = CursorUtil.getColumnIndexOrThrow(_cursor, "bmi");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final LungPatientEntity _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final int _tmpSequenceNumber;
            _tmpSequenceNumber = _cursor.getInt(_cursorIndexOfSequenceNumber);
            final String _tmpSex;
            _tmpSex = _cursor.getString(_cursorIndexOfSex);
            final int _tmpAge;
            _tmpAge = _cursor.getInt(_cursorIndexOfAge);
            final float _tmpChestCircumferenceCm;
            _tmpChestCircumferenceCm = _cursor.getFloat(_cursorIndexOfChestCircumferenceCm);
            final float _tmpHeightCm;
            _tmpHeightCm = _cursor.getFloat(_cursorIndexOfHeightCm);
            final float _tmpWeightKg;
            _tmpWeightKg = _cursor.getFloat(_cursorIndexOfWeightKg);
            final float _tmpBmi;
            _tmpBmi = _cursor.getFloat(_cursorIndexOfBmi);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _result = new LungPatientEntity(_tmpId,_tmpSequenceNumber,_tmpSex,_tmpAge,_tmpChestCircumferenceCm,_tmpHeightCm,_tmpWeightKg,_tmpBmi,_tmpCreatedAt);
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
  public Object getCount(final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT COUNT(*) FROM lung_patients";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
