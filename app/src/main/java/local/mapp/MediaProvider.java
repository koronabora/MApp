package local.mapp;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.*;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.*;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Seekable, pinned-HTTPS content bridge. Players never receive the account key. */
public final class MediaProvider extends ContentProvider {
    static Uri create(Context context, String path, long size, String name) throws Exception {
        String id=UUID.randomUUID().toString();
        JSONObject item=new JSONObject().put("path",path).put("size",size).put("name",name)
            .put("server",Vault.load(context).optString("server")).put("expires",System.currentTimeMillis()+86400000);
        var prefs=context.getSharedPreferences("streams",0);
        var edit=prefs.edit();
        for(String k:prefs.getAll().keySet()) {
            try { if(new JSONObject(prefs.getString(k,"{}")).optLong("expires")<System.currentTimeMillis()) edit.remove(k); }
            catch(Exception ignored) { edit.remove(k); }
        }
        edit.putString(id,item.toString()).apply();
        return Uri.parse("content://local.mapp.media/"+id);
    }
    private JSONObject item(Uri uri) throws Exception {
        return describe(getContext(),uri);
    }
    static JSONObject describe(Context context,Uri uri) throws Exception {
        String raw=context.getSharedPreferences("streams",0).getString(uri.getLastPathSegment(),null);
        if(raw==null) throw new FileNotFoundException();
        JSONObject item=new JSONObject(raw);
        if(item.getLong("expires")<System.currentTimeMillis() || !item.getString("server").equals(Vault.load(context).optString("server"))) throw new FileNotFoundException();
        return item;
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        try {
            String name=item(uri).getString("name"); int dot=name.lastIndexOf('.');
            String type=dot<0?null:android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot+1).toLowerCase(java.util.Locale.ROOT));
            if(type!=null) return type;
        } catch(Exception ignored) { }
        return "video/*";
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        try {
            JSONObject i=item(uri);
            String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor cursor=new MatrixCursor(cols); Object[] row=new Object[cols.length];
            for(int n=0;n<cols.length;n++) row[n]=cols[n].equals(OpenableColumns.SIZE)?i.getLong("size"):cols[n].equals(OpenableColumns.DISPLAY_NAME)?i.getString("name"):null;
            cursor.addRow(row); return cursor;
        } catch(Exception e) { return null; }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!mode.equals("r")) throw new FileNotFoundException("Read only");
        try {
            JSONObject item=item(uri); Api api=new Api(getContext()); long length=item.getLong("size");
            String sessionIdentity=Vault.sessionIdentity(getContext());
            HandlerThread thread=new HandlerThread("MApp media"); thread.start();
            return getContext().getSystemService(StorageManager.class).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,new ProxyFileDescriptorCallback() {
                byte[] cache=new byte[0]; long cacheStart=-1;
                final java.util.LinkedHashMap<Long,byte[]> blocks=new java.util.LinkedHashMap<>(8,.75f,true);
                @Override public long onGetSize() { return length; }
                @Override public int onRead(long offset,int size,byte[] data) throws ErrnoException {
                    if(offset>=length) return 0;
                    try {
                        if(sessionIdentity.isEmpty() || !sessionIdentity.equals(Vault.sessionIdentity(getContext()))) throw new IOException("Account disconnected");
                        int wanted=(int)Math.min(size,length-offset), copied=0;
                        while(copied<wanted) {
                            long cursor=offset+copied;
                            if(cursor<cacheStart || cursor>=cacheStart+cache.length) {
                                long block=cursor/(512*1024)*(512*1024);
                                byte[] stored=blocks.get(block);
                                if(stored!=null) { cache=stored; cacheStart=block; continue; }
                                HttpsURLConnection c=api.open(item.getString("path"));
                                long end=Math.min(length-1,block+512*1024-1);
                                c.setRequestProperty("Range","bytes="+block+"-"+end);
                                c.setRequestProperty("Accept-Encoding","identity");
                                try {
                                    if(c.getResponseCode()!=206) throw new IOException("Range request failed");
                                    try(InputStream in=c.getInputStream()) { cache=Api.read(in,1024*1024); }
                                    if(cache.length!=end-block+1 || !("bytes "+block+"-"+end+"/"+length).equals(c.getHeaderField("Content-Range"))) throw new IOException("Incorrect range");
                                    cacheStart=block; blocks.put(block,cache);
                                    if(blocks.size()>8) blocks.remove(blocks.keySet().iterator().next());
                                } finally { c.disconnect(); }
                            }
                            int count=(int)Math.min(wanted-copied,cacheStart+cache.length-cursor);
                            System.arraycopy(cache,(int)(cursor-cacheStart),data,copied,count); copied+=count;
                        }
                        return copied;
                    } catch(Exception e) { throw new ErrnoException("MCore read",OsConstants.EIO,e); }
                }
                @Override public void onRelease() { thread.quitSafely(); }
            },new Handler(thread.getLooper()));
        } catch(Exception e) { throw new FileNotFoundException(e.getMessage()); }
    }
    @Override public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
}
