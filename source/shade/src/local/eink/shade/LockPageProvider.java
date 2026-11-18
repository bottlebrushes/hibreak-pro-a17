package local.eink.shade;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * content://local.eink.shade.lockpage/page.png: the lock page as it will look at the next lock, for the HiBreak sleep
 * dream (signature permission LOCK_PAGE). The dream draws ~0.1 s before any lock signal reaches this app, so drawing
 * this picture makes the first e-ink frame after locking our page instead of the dream's own.
 */
public final class LockPageProvider extends ContentProvider {
    static final String FILE = "lockpage.png";

    static File file(android.content.Context c) { return new File(c.getFilesDir(), FILE); }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(getContext());
        if (!"r".equals(mode) || !f.canRead()) throw new FileNotFoundException(FILE);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
