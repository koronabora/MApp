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
import javax.net.ssl.HttpsURLConnection;
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
                finally { queued.remove(item.optString("id")); if(queued.isEmpty()) stopSelf(); }
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
                    HttpsURLConnection connection=api.open(item.getString("path"));
                    connection.setRequestProperty("Range","bytes="+done+"-"+end);
                    try {
                        if(connection.getResponseCode()!=206) throw new IOException("Сервер не вернул запрошенный фрагмент");
                        try(InputStream input=connection.getInputStream()) {
                            byte[] buffer=new byte[128*1024]; int read;
                            while((read=input.read(buffer))!=-1) {
                                if(stopped || cancelled.contains(item.getString("id"))) throw new InterruptedIOException();
                                if(done+read>end+1) throw new IOException("Некорректный размер фрагмента");
                                ByteBuffer bytes=ByteBuffer.wrap(buffer,0,read);
                                while(bytes.hasRemaining()) channel.write(bytes);
                                done+=read;
                            }
                        }
                        if(done!=end+1) throw new IOException("Загрузка прервана; можно продолжить");
                        channel.force(false);
                        item.put("downloaded",done).put("download_rate",(done-chunkStart)*1000/Math.max(1,System.currentTimeMillis()-chunkStarted)); Library.put(this,item);
                        getSystemService(NotificationManager.class).notify(1,notification(item.getString("title"),(int)(done*100/total)));
                    } finally { connection.disconnect(); }
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
                Uri uri=Uri.parse(item.getString("uri"));
                if(android.provider.DocumentsContract.isDocumentUri(this,uri)) android.provider.DocumentsContract.deleteDocument(getContentResolver(),uri);
                else getContentResolver().delete(uri,null,null);
                Library.remove(this,item.getString("id"));
            } catch(Exception e) { try { item.put("state","paused").put("error","Не удалось удалить файл: "+e.getMessage()); Library.put(this,item); } catch(Exception ignored) { } }
        } else Library.put(this,item);
    }
    @Override public void onTimeout(int startId,int fgsType) { stopped=true; stopSelf(); }
    @Override public void onDestroy() { stopped=true; worker.shutdownNow(); queued.clear(); super.onDestroy(); }
}
