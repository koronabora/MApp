package local.mapp;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants the system installer read access to one verified APK only. */
public final class UpdateProvider extends ContentProvider {
    static final Uri URI=Uri.parse("content://local.mapp.update/update.apk");
    static File apk(Context context) { return new File(context.getFilesDir(),"updates/update.apk"); }
    @Override public boolean onCreate() { return true; }
    private void check(Uri uri) { if(!URI.equals(uri)) throw new IllegalArgumentException("Unknown update"); }
    @Override public String getType(Uri uri) { check(uri); return "application/vnd.android.package-archive"; }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        check(uri); if(!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(apk(getContext()),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
        check(uri); String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor cursor=new MatrixCursor(columns); Object[] row=new Object[columns.length];
        for(int i=0;i<columns.length;i++) row[i]=OpenableColumns.DISPLAY_NAME.equals(columns[i])?"MApp.apk":OpenableColumns.SIZE.equals(columns[i])?apk(getContext()).length():null;
        cursor.addRow(row); return cursor;
    }
    @Override public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
}
