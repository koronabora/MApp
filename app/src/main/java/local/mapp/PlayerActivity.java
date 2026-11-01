package local.mapp;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.videolan.libvlc.*;
import org.videolan.libvlc.util.VLCVideoLayout;

/** In-process decoder with seekable pinned HTTPS input and persistent playback position. */
public final class PlayerActivity extends Activity {
    private LibVLC engine;
    private MediaPlayer player;
    private ParcelFileDescriptor descriptor;
    private PlaybackBridge bridge;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean saving=new java.util.concurrent.atomic.AtomicBoolean();
    private TextView status;
    private SeekBar seek;
    private Button pause;
    private long resume, lastSaved;
    private boolean started, seeking, completed;
    private float buffering=100;
    private String movie, savedUri;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v,insets)->{ v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
        VLCVideoLayout video=new VLCVideoLayout(this); root.addView(video,new LinearLayout.LayoutParams(-1,0,1));
        status=new TextView(this); status.setTextColor(Color.WHITE); status.setGravity(Gravity.CENTER); status.setText("Подготовка видео…"); root.addView(status);
        seek=new SeekBar(this); seek.setMax(10000); root.addView(seek);
        HorizontalScrollView scroller=new HorizontalScrollView(this); LinearLayout controls=new LinearLayout(this); scroller.addView(controls); root.addView(scroller);
        controls.addView(control("Назад",this::finish)); controls.addView(control("−10 с",()->jump(-10000)));
        pause=control("Пауза",()->{ if(player.isPlaying()) player.pause(); else player.play(); }); controls.addView(pause);
        controls.addView(control("+10 с",()->jump(10000))); controls.addView(control("Аудио",()->tracks(false))); controls.addView(control("Субтитры",()->tracks(true)));
        setContentView(root);
        movie=getIntent().getStringExtra("movie"); savedUri=getIntent().getStringExtra("saved_uri");
        resume=state!=null?state.getLong("position"):getIntent().getLongExtra("position",0);
        try {
            engine=new LibVLC(this,new ArrayList<>(Arrays.asList("--no-video-title-show","--file-caching=1500","--network-caching=1500")));
            player=new MediaPlayer(engine); player.attachViews(video,null,true,false);
            player.setEventListener(event->{
                if(event.type==MediaPlayer.Event.Playing) {
                    pause.setText("Пауза");
                    if(!started) { started=true; if(resume>0) player.setTime(resume); }
                } else if(event.type==MediaPlayer.Event.Paused) pause.setText("Продолжить");
                else if(event.type==MediaPlayer.Event.Buffering) { buffering=event.getBuffering(); status.setText("Буферизация: "+(int)buffering+" %"); }
                else if(event.type==MediaPlayer.Event.EncounteredError) new AlertDialog.Builder(this).setMessage("Не удалось прочитать видео. Проверьте соединение или выберите другую раздачу.").setPositiveButton("Закрыть",(d,n)->finish()).show();
                else if(event.type==MediaPlayer.Event.EndReached) { completed=true; finish(); }
            });
            Media media;
            Uri uri=getIntent().getData();
            if("local.mapp.media".equals(uri.getAuthority())) {
                bridge=new PlaybackBridge(this,MediaProvider.describe(this,uri).getString("path"));
                media=new Media(engine,Uri.parse(bridge.url()));
            } else {
                descriptor=getContentResolver().openFileDescriptor(uri,"r");
                if(descriptor==null) throw new java.io.IOException("Файл недоступен");
                media=new Media(engine,descriptor.getFileDescriptor());
            }
            media.setHWDecoderEnabled(true,false);
            // Never scan a multi-gigabyte incomplete AVI to rebuild its index.
            media.addOption(":avi-index=2");
            player.setMedia(media); media.release(); player.play();
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar bar) { seeking=true; }
                public void onStopTrackingTouch(SeekBar bar) { if(player.getLength()>0) player.setTime(player.getLength()*bar.getProgress()/10000); seeking=false; }
                public void onProgressChanged(SeekBar bar,int p,boolean user) { }
            });
            ui.post(tick);
        } catch(Exception e) { new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("Закрыть",(d,n)->finish()).show(); }
    }
    private Button control(String label,Runnable action) {
        Button button=new Button(this); button.setText(label); button.setAllCaps(false); button.setOnClickListener(v->action.run()); return button;
    }
    private void jump(long amount) { if(player!=null) player.setTime(Math.max(0,Math.min(player.getLength(),player.getTime()+amount))); }
    private void tracks(boolean subtitles) {
        MediaPlayer.TrackDescription[] tracks=subtitles?player.getSpuTracks():player.getAudioTracks();
        if(tracks==null || tracks.length==0) { Toast.makeText(this,"Дорожек нет",Toast.LENGTH_SHORT).show(); return; }
        String[] names=new String[tracks.length]; int selected=-1, active=subtitles?player.getSpuTrack():player.getAudioTrack();
        for(int i=0;i<tracks.length;i++) { names[i]=tracks[i].id==-1?"Выключить":tracks[i].name; if(tracks[i].id==active) selected=i; }
        new AlertDialog.Builder(this).setTitle(subtitles?"Субтитры":"Аудиодорожка").setSingleChoiceItems(names,selected,(d,n)->{ if(subtitles) player.setSpuTrack(tracks[n].id); else player.setAudioTrack(tracks[n].id); d.dismiss(); }).show();
    }
    private static String clock(long ms) { long s=Math.max(0,ms/1000); return String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,(s/60)%60,s%60); }
    private final Runnable tick=new Runnable() { public void run() {
        if(player==null || isFinishing()) return;
        if(started && player.getLength()>0) {
            long position=player.getTime(), length=player.getLength();
            if(!seeking) seek.setProgress((int)(Math.max(0,position)*10000/length));
            status.setText((buffering<100?"Буферизация: "+(int)buffering+" % · ":"")+clock(position)+" / "+clock(length));
            if(System.currentTimeMillis()-lastSaved>5000) { checkpoint(); lastSaved=System.currentTimeMillis(); }
        }
        ui.postDelayed(this,1000);
    }};
    private void checkpoint() {
        if(player==null || !started) return;
        long position=completed?0:Math.max(0,player.getTime());
        setResult(RESULT_OK,new Intent().putExtra("extra_position",position));
        if(savedUri!=null) Library.position(this,savedUri,position);
        if(movie!=null && !movie.isEmpty() && !movie.startsWith("local-file:") && saving.compareAndSet(false,true)) io.submit(()->{ try {
            JSONObject update=new JSONObject().put("position",position);
            String torrent=getIntent().getStringExtra("torrent");
            if(torrent!=null && !torrent.isEmpty()) update.put("torrent",torrent).put("file",getIntent().getIntExtra("file",0));
            new Api(this).request("PUT","/v1/history/"+movie,update);
        } catch(Exception ignored) { } finally { saving.set(false); } });
    }
    @Override protected void onPause() { checkpoint(); if(player!=null) player.pause(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) { if(player!=null) state.putLong("position",Math.max(0,player.getTime())); super.onSaveInstanceState(state); }
    @Override public void finish() { checkpoint(); super.finish(); }
    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if(bridge!=null) bridge.close();
        MediaPlayer old=player; LibVLC oldEngine=engine; ParcelFileDescriptor oldDescriptor=descriptor;
        if(old!=null) { old.setEventListener(null); old.detachViews(); } player=null;
        new Thread(()->{
            if(old!=null) { old.stop(); old.release(); }
            if(oldDescriptor!=null) try { oldDescriptor.close(); } catch(Exception ignored) { }
            if(oldEngine!=null) oldEngine.release();
        },"MApp decoder close").start();
        io.shutdown(); super.onDestroy();
    }
}
