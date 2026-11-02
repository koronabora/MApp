package local.mapp;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import org.json.JSONObject;

/** Bounded, account-scoped snapshots. Availability is never trusted from disk. */
final class MovieCache {
    private static File file(Context c,String id) throws Exception {
        JSONObject session=Vault.load(c);
        String key=session.optString("server")+"\n"+session.optString("key")+"\n"+id;
        File folder=new File(c.getCacheDir(),"cards"); folder.mkdirs();
        return new File(folder,AppUpdates.hex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)))+".json");
    }
    static synchronized JSONObject read(Context c,String id) {
        try {
            File file=file(c,id); JSONObject data;
            try(var in=new AtomicFile(file).openRead()) { data=new JSONObject(new String(Api.read(in,16*1024*1024),StandardCharsets.UTF_8)); }
            var releases=data.optJSONArray("releases");
            if(releases!=null) for(int i=0;i<releases.length();i++) releases.getJSONObject(i).put("availability_known",false).put("swarm_stale",true).put("availability_checked_at",0).put("seeds",0).put("peers",0);
            file.setLastModified(System.currentTimeMillis()); return data;
        } catch(Exception ignored) { return null; }
    }
    static synchronized void remove(Context c,String id) { try { new AtomicFile(file(c,id)).delete(); } catch(Exception ignored) { } }
    static synchronized void write(Context c,String id,JSONObject data) {
        AtomicFile atomic=null; FileOutputStream output=null;
        try {
            File file=file(c,id); byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>16*1024*1024) return;
            atomic=new AtomicFile(file); output=atomic.startWrite(); output.write(bytes); atomic.finishWrite(output); output=null;
            File[] files=file.getParentFile().listFiles((dir,name)->name.endsWith(".json"));
            if(files!=null) {
                Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed()); long total=0;
                for(int i=0;i<files.length;i++) { total+=files[i].length(); if(i>=24 || total>64L*1024*1024) files[i].delete(); }
            }
        } catch(Exception ignored) { if(atomic!=null && output!=null) atomic.failWrite(output); }
    }
}
