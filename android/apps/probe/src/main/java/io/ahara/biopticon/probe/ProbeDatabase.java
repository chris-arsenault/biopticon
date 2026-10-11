package io.ahara.biopticon.probe;

import androidx.annotation.NonNull;
import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Entity;
import androidx.room.Insert;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.room.Query;
import androidx.room.RoomDatabase;
import androidx.room.Transaction;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import java.util.List;

@Database(entities = {ProbeDatabase.EventRow.class, ProbeDatabase.RunRow.class}, version = 2, exportSchema = false)
public abstract class ProbeDatabase extends RoomDatabase {
    public abstract Records records();

    public static final Migration REMOVE_OWNER = new Migration(1, 2) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE events_new (sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, id TEXT NOT NULL, runId TEXT NOT NULL, json TEXT NOT NULL, summary TEXT NOT NULL, delivery INTEGER NOT NULL, error TEXT NOT NULL, created INTEGER NOT NULL)");
            db.execSQL("INSERT INTO events_new (sequence, id, runId, json, summary, delivery, error, created) SELECT sequence, id, runId, json, summary, delivery, error, created FROM events");
            db.execSQL("DROP TABLE events");
            db.execSQL("ALTER TABLE events_new RENAME TO events");
            db.execSQL("CREATE UNIQUE INDEX index_events_id ON events (id)");
        }
    };

    @Entity(tableName = "events", indices = {@Index(value = "id", unique = true)})
    public static class EventRow {
        @PrimaryKey(autoGenerate = true) public long sequence;
        @NonNull public String id = "";
        @NonNull public String runId = "";
        @NonNull public String json = "";
        @NonNull public String summary = "";
        public int delivery;
        @NonNull public String error = "";
        public long created;
    }

    @Entity(tableName = "runs")
    public static class RunRow {
        @PrimaryKey @NonNull public String id = "";
        @NonNull public String state = "RUNNING";
        @NonNull public String session = "";
        public long created;
    }

    @Dao
    public interface Records {
        @Insert void insertEvent(EventRow row);
        @Insert void insertRun(RunRow row);
        @Query("SELECT * FROM events ORDER BY sequence DESC LIMIT 400") List<EventRow> recent();
        @Query("SELECT * FROM events WHERE delivery = 0 ORDER BY sequence LIMIT 100") List<EventRow> pending();
        @Query("UPDATE events SET delivery = :state, error = :error WHERE id = :id") void delivery(String id, int state, String error);
        @Query("SELECT * FROM runs WHERE state = 'RUNNING' AND session != :session") List<RunRow> interrupted(String session);
        @Query("UPDATE runs SET state = :state WHERE id = :id") void runState(String id, String state);
        @Query("SELECT COUNT(*) FROM events WHERE delivery = 0") int queuedCount();
        @Query("UPDATE events SET delivery = 0, error = '' WHERE delivery = 2") void retryErrors();

        @Transaction
        default void finish(RunRow run, EventRow event) {
            runState(run.id, run.state);
            insertEvent(event);
        }
    }
}
