package io.deepseekharness.mobile.companion;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Test-only URI provider. Exposes only the controlled drag fixture filenames with a temporary OS grant. */
public class ForeignDropProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    private File file(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[a-z0-9-]{1,80}\\.txt")) throw new IllegalArgumentException();
        return new File(new File(getContext().getCacheDir(), "drag-probe"), name);
    }
    public String getType(Uri uri) { return "text/plain"; }
    public Cursor query(Uri uri, String[] columns, String selection, String[] args, String sort) {
        File f = file(uri);
        String[] projection = columns == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : columns;
        MatrixCursor c = new MatrixCursor(projection);
        MatrixCursor.RowBuilder row = c.newRow();
        for (String col : projection) row.add(OpenableColumns.DISPLAY_NAME.equals(col) ? f.getName() : f.length());
        return c;
    }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri, String w, String[] a) { throw new UnsupportedOperationException(); }
    public int update(Uri uri, ContentValues v, String w, String[] a) { throw new UnsupportedOperationException(); }
}
