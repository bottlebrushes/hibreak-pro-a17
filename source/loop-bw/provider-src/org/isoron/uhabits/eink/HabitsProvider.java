package org.isoron.uhabits.eink;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.CancellationSignal;

/** Read-only projection of Loop's open habits for the privileged e-ink Shade. */
public final class HabitsProvider extends ContentProvider {
    public static final String AUTHORITY = "org.isoron.uhabits.eink";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/habits");
    public static final String READ_PERMISSION = "org.isoron.uhabits.permission.READ_EINK_HABITS";

    private static final String TABLE = "Habits";
    private static final String[] DEFAULT_PROJECTION = {"name", "position"};
    private static final String[] ALLOWED_COLUMNS = {"name", "position"};
    private SQLiteDatabase database;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return query(uri, projection, selection, selectionArgs, sortOrder, null);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder,
                        CancellationSignal cancellationSignal) {
        if (!CONTENT_URI.equals(uri)) {
            throw new IllegalArgumentException("Unsupported URI: " + uri);
        }
        if (selection != null || selectionArgs != null) {
            throw new IllegalArgumentException("Selections are not supported");
        }

        String[] columns = projection == null ? DEFAULT_PROJECTION : checkedProjection(projection);
        SQLiteDatabase db = openDatabase();
        Cursor cursor = db.query(TABLE, columns, "archived = ?", new String[]{"0"},
                null, null, "position ASC", null);
        cursor.setNotificationUri(getContext().getContentResolver(), CONTENT_URI);
        return cursor;
    }

    private String[] checkedProjection(String[] projection) {
        String[] checked = new String[projection.length];
        for (int i = 0; i < projection.length; i++) {
            String column = projection[i];
            boolean allowed = false;
            for (String candidate : ALLOWED_COLUMNS) {
                if (candidate.equals(column)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) throw new IllegalArgumentException("Unsupported column: " + column);
            checked[i] = column;
        }
        return checked;
    }

    private synchronized SQLiteDatabase openDatabase() {
        if (database == null || !database.isOpen()) {
            Context context = getContext();
            database = SQLiteDatabase.openDatabase(
                    context.getDatabasePath("uhabits.db").getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        }
        return database;
    }

    @Override
    public String getType(Uri uri) {
        if (!CONTENT_URI.equals(uri)) throw new IllegalArgumentException("Unsupported URI: " + uri);
        return "vnd.android.cursor.dir/vnd.org.isoron.uhabits.eink.habit";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public void shutdown() {
        synchronized (this) {
            if (database != null) {
                database.close();
                database = null;
            }
        }
        super.shutdown();
    }
}
