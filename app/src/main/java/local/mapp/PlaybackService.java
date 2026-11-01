package local.mapp;

import android.app.*;
import android.content.*;
import android.os.IBinder;

/** Keeps the local HTTP stream alive while another app is displaying video. */
public final class PlaybackService extends Service {
    private static PlaybackBridge bridge;
    static synchronized String open(Context context,PlaybackBridge value) {
        if(bridge!=null) bridge.close(); bridge=value;
        context.startForegroundService(new Intent(context,PlaybackService.class)); return value.url();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("playback","Воспроизведение",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,0,new Intent(this,PlaybackService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
        startForeground(2,new Notification.Builder(this,"playback").setSmallIcon(R.drawable.camera).setContentTitle("MApp · воспроизведение")
            .setContentText("Передача видео внешнему плееру").addAction(new Notification.Action.Builder(null,"Остановить",stop).build()).setOngoing(true).build());
        if(intent==null || "stop".equals(intent.getAction())) stopSelf(); return START_NOT_STICKY;
    }
    @Override public void onDestroy() { synchronized(PlaybackService.class) { if(bridge!=null) bridge.close(); bridge=null; } super.onDestroy(); }
}
