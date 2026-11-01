package local.mapp;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Set;
import java.util.concurrent.*;
import org.json.JSONObject;

public final class DownloadService extends Service {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    static final Set<String> queued=ConcurrentHashMap.newKeySet();
    static final Set<String> cancelled=ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("downloads","Загрузки",NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(String title,int percent) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).putExtra("library",true),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"downloads").setSmallIcon(R.drawable.camera).setContentTitle(title)
            .setContentText(percent+" % • MApp").setProgress(100,percent,false).setContentIntent(open).setOngoing(true).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        startForeground(1,notification("Сохранение фильма",0));
        if(intent==null || intent.getStringExtra("item")==null) { stopSelf(); return START_NOT_STICKY; }
        try {
            JSONObject item=new JSONObject(intent.getStringExtra("item"));
            if(queued.add(item.getString("id"))) worker.submit(()->{
                try { download(item); }
                finally { queued.remove(item.optString("id")); if(queued.isEmpty()) stopSelfResult(startId); }
            });
        } catch(Exception e) { stopSelf(); }
        return START_NOT_STICKY;
    }
    private void download(JSONObject item) {
        try {
            Api api=new Api(this);
            if(!api.server.equals(item.getString("server"))) throw new IOException("Подключитесь к исходному серверу");
            Uri target=Uri.parse(item.getString("uri"));
            long total=item.getLong("size"), done=item.optLong("downloaded");
            item.put("state","downloading"); Library.put(this,item);
            try(ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(target,"rw");
                FileOutputStream output=new FileOutputStream(fd.getFileDescriptor()); FileChannel channel=output.getChannel()) {
                done=Math.min(done,channel.size()); channel.position(done); channel.truncate(done);
                while(done<total && !stopped) {
                    if(cancelled.contains(item.getString("id"))) throw new InterruptedIOException("Отменено пользователем");
                    long chunkStarted=System.currentTimeMillis(), chunkStart=done;
                    long end=Math.min(total-1,done+4*1024*1024-1);
                    byte[] verified=VerifiedChunk.retry(()->{
                        if(stopped || cancelled.contains(item.getString("id"))) throw new InterruptedIOException();
                        return VerifiedChunk.read(api,item.getString("path"),chunkStart,end,total);
                    });
                    if(stopped || cancelled.contains(item.getString("id"))) throw new InterruptedIOException();
                    {
                        ByteBuffer bytes=ByteBuffer.wrap(verified);
                        while(bytes.hasRemaining()) channel.write(bytes);
                        channel.force(false);
                        done=end+1;
                        item.put("downloaded",done).put("download_rate",(done-chunkStart)*1000/Math.max(1,System.currentTimeMillis()-chunkStarted)); Library.put(this,item);
                        FolderLibrary.backup(this,item);
                        getSystemService(NotificationManager.class).notify(1,notification(item.getString("title"),(int)(done*100/total)));
                    }
                }
            }
            item.put("state",done==total?"complete":"paused");
            if(done==total && item.optBoolean("mediastore")) {
                ContentValues values=new ContentValues(); values.put(MediaStore.Downloads.IS_PENDING,0);
                getContentResolver().update(Uri.parse(item.getString("uri")),values,null,null);
            }
        } catch(Exception e) {
            try { item.put("state","paused").put("error",e.getMessage()); } catch(Exception ignored) { }
        }
        if(cancelled.remove(item.optString("id"))) {
            try {
                FolderLibrary.remove(this,item);
            } catch(Exception e) { try { item.put("state","paused").put("error","Не удалось удалить файл: "+e.getMessage()); Library.put(this,item); } catch(Exception ignored) { } }
        } else { Library.put(this,item); FolderLibrary.backup(this,item); }
    }
    @Override public void onTimeout(int startId,int fgsType) { stopped=true; stopSelf(); }
    @Override public void onDestroy() { stopped=true; worker.shutdownNow(); queued.clear(); super.onDestroy(); }
}
