package local.mapp;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import org.json.JSONObject;

/** Verified container tail for partial saved playback; never read from the network by the player. */
final class LocalIndex {
    static File file(Context c,JSONObject item) {
        String id=item.optString("id");
        return new File(c.getFilesDir(),"index-"+java.util.UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".bin");
    }
    static long offset(JSONObject item) { return Math.max(0,item.optLong("size")-8L*1024*1024)/(512*1024)*(512*1024); }
    static boolean ready(Context c,JSONObject item) { return item.optBoolean("index_ready") && file(c,item).length()==item.optLong("size")-offset(item); }
    static void prepare(Context c,Api api,JSONObject item) throws Exception {
        if(ready(c,item)) return;
        long size=item.getLong("size"); AtomicFile target=new AtomicFile(file(c,item)); FileOutputStream out=null;
        try {
            out=target.startWrite();
            for(long start=offset(item);start<size;start+=4L*1024*1024) {
                if(DownloadService.paused.contains(item.getString("id")) || DownloadService.cancelled.contains(item.getString("id"))) throw new InterruptedIOException();
                long first=start, last=Math.min(size-1,start+4L*1024*1024-1);
                out.write(VerifiedChunk.retry(()->VerifiedChunk.read(api,item.getString("path"),first,last,size)));
            }
            target.finishWrite(out); out=null;
            JSONObject info=api.request("GET",item.getString("path")+"/info",null);
            long duration=info.optLong("duration_ms");
            if(duration<=0) throw new IOException("Индекс видео ещё не готов");
            item.put("duration",duration).put("index_ready",true); Library.downloadState(c,item);
        } finally { if(out!=null) target.failWrite(out); }
    }
    static byte[] read(Context c,JSONObject item,long start,int count) throws Exception {
        try(var source=new RandomAccessFile(file(c,item),"r")) { source.seek(start-offset(item)); byte[] bytes=new byte[count]; source.readFully(bytes); return bytes; }
    }
}
