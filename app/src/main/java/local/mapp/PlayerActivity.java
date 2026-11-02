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
    private ImageButton pause;
    private long resume, lastSaved;
    private boolean started, seeking, completed;
    private float buffering=100;
    private String movie, savedUri;
    private boolean askAudio, audioHandled;
    private AlertDialog audioDialog;
    private long audioDeadline;
    private String audioKey;
    private LinearLayout overlay, top, bottom;
    private boolean locked;
    private final Runnable hideControls=()->{ if(overlay!=null && player!=null && player.isPlaying() && !seeking) overlay.setVisibility(View.GONE); };
    private int dp(int n) { return (int)(n*getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Diagnostics.init(this); Diagnostics.event("player","created position="+getIntent().getLongExtra("position",0));
        FrameLayout root=new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v,insets)->{ v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        VLCVideoLayout video=new VLCVideoLayout(this); root.addView(video,new FrameLayout.LayoutParams(-1,-1));
        View touch=new View(this); root.addView(touch,new FrameLayout.LayoutParams(-1,-1));
        overlay=new LinearLayout(this); overlay.setOrientation(LinearLayout.VERTICAL); root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        overlay.setBackgroundColor(0x22000000);
        top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setBackgroundColor(0x77000000); overlay.addView(top);
        top.addView(icon(R.drawable.player_arrow_back,"Назад",this::finish));
        top.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        top.addView(icon(R.drawable.player_screen_rotation,"Повернуть экран",()->setRequestedOrientation(getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE?android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)));
        ImageButton lock=icon(R.drawable.player_lock_open,"Блокировка управления",()->{}); top.addView(lock);
        lock.setOnClickListener(v->{ locked=!locked; bottom.setVisibility(locked?View.GONE:View.VISIBLE); lock.setImageResource(locked?R.drawable.player_lock:R.drawable.player_lock_open); Toast.makeText(this,locked?"Управление заблокировано":"Управление разблокировано",Toast.LENGTH_SHORT).show(); });
        View space=new View(this); overlay.addView(space,new LinearLayout.LayoutParams(-1,0,1)); space.setOnClickListener(v->overlay.setVisibility(View.GONE));
        bottom=new LinearLayout(this); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setPadding(dp(12),dp(8),dp(12),dp(12)); bottom.setBackgroundColor(0x77000000); overlay.addView(bottom);
        status=new TextView(this); status.setTextColor(Color.WHITE); status.setGravity(Gravity.CENTER); status.setText("Подготовка видео…"); bottom.addView(status);
        seek=new SeekBar(this); seek.setMax(10000); seek.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffff8a24)); seek.setThumbTintList(android.content.res.ColorStateList.valueOf(0xffff8a24)); bottom.addView(seek);
        LinearLayout controls=new LinearLayout(this); controls.setGravity(Gravity.CENTER); bottom.addView(controls);
        controls.addView(icon(R.drawable.player_replay_10,"Назад 10 секунд",()->jump(-10000)));
        pause=icon(R.drawable.player_pause,"Пауза / продолжить",()->{ if(player.isPlaying()) player.pause(); else player.play(); showControls(); }); controls.addView(pause);
        controls.addView(icon(R.drawable.player_forward_10,"Вперёд 10 секунд",()->jump(10000)));
        controls.addView(icon(R.drawable.player_audiotrack,"Аудиодорожки",()->tracks(false))); controls.addView(icon(R.drawable.player_subtitles,"Субтитры",()->tracks(true)));
        controls.addView(icon(R.drawable.player_more_vert,"Дополнительно",this::more));
        final float[] down=new float[2];
        touch.setOnTouchListener((v,event)->{ if(event.getAction()==android.view.MotionEvent.ACTION_DOWN) { down[0]=event.getX(); down[1]=event.getY(); return true; }
            if(event.getAction()==android.view.MotionEvent.ACTION_UP) {
                float delta=down[1]-event.getY();
                if(!locked && Math.abs(delta)>dp(40)) {
                    if(down[0]<root.getWidth()/2f) { var attrs=getWindow().getAttributes(); attrs.screenBrightness=Math.max(.05f,Math.min(1f,(attrs.screenBrightness<0?.5f:attrs.screenBrightness)+delta/root.getHeight())); getWindow().setAttributes(attrs); }
                    else { var audio=getSystemService(android.media.AudioManager.class); int max=audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC); int current=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC); audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,Math.max(0,Math.min(max,current+Math.round(delta/root.getHeight()*max))),android.media.AudioManager.FLAG_SHOW_UI); }
                } else showControls(); return true;
            } return true; });
        setContentView(root);
        movie=getIntent().getStringExtra("movie"); savedUri=getIntent().getStringExtra("saved_uri");
        resume=state!=null?state.getLong("position"):getIntent().getLongExtra("position",0);
        audioKey="audio:"+movie+":"+getIntent().getStringExtra("torrent")+":"+getIntent().getIntExtra("file",0)+":"+savedUri;
        askAudio=resume==0 && getSharedPreferences("settings",0).getBoolean("ask_audio",false);
        audioDeadline=System.currentTimeMillis()+30000;
        try {
            // VLC's default 16 MiB prefetch read can wait across an unavailable torrent gap.
            // Keep reads small so a seek can reach a different available range promptly.
            int cache=getSharedPreferences("settings",0).getInt("buffer",15)*1000;
            engine=new LibVLC(this,new ArrayList<>(Arrays.asList("--no-video-title-show","--file-caching=1500","--network-caching="+cache,"--prefetch-buffer-size=1024","--prefetch-read-size=65536")));
            player=new MediaPlayer(engine); player.attachViews(video,null,true,false);
            if(askAudio) player.setVolume(0);
            player.setEventListener(event->{
                if(event.type!=org.videolan.libvlc.MediaPlayer.Event.TimeChanged && event.type!=org.videolan.libvlc.MediaPlayer.Event.PositionChanged)
                    Diagnostics.event("player-event","type="+event.type);
                if(event.type==MediaPlayer.Event.Playing) {
                    pause.setImageResource(R.drawable.player_pause); showControls();
                    if(!started) { started=true; if(resume>0) player.setTime(resume); }
                    if(audioDialog!=null && audioDialog.isShowing()) player.pause();
                } else if(event.type==MediaPlayer.Event.Paused) { pause.setImageResource(R.drawable.player_play_arrow); showControls(); }
                else if(event.type==MediaPlayer.Event.Buffering) { buffering=event.getBuffering(); status.setText("Буферизация: "+(int)buffering+" %"); }
                else if(event.type==MediaPlayer.Event.EncounteredError) new AlertDialog.Builder(this).setMessage("Не удалось прочитать видео. Проверьте соединение или выберите другую раздачу.").setPositiveButton("Закрыть",(d,n)->finish()).show();
                else if(event.type==MediaPlayer.Event.EndReached) { completed=true; finish(); }
            });
            Media media;
            Uri uri=getIntent().getData();
            if(getIntent().hasExtra("item_id") || getIntent().hasExtra("local_item")) {
                bridge=new PlaybackBridge(this,Library.intentItem(this,getIntent(),"local_item"));
                media=new Media(engine,Uri.parse(bridge.url()));
            } else if("local.mapp.media".equals(uri.getAuthority())) {
                JSONObject source=MediaProvider.describe(this,uri);
                bridge=new PlaybackBridge(this,source.getString("path"),source.getLong("size"));
                media=new Media(engine,Uri.parse(bridge.url()));
            } else {
                descriptor=getContentResolver().openFileDescriptor(uri,"r");
                if(descriptor==null) throw new java.io.IOException("Файл недоступен");
                media=new Media(engine,descriptor.getFileDescriptor());
            }
            boolean software=DecoderPolicy.software(this,DecoderPolicy.name(this,uri));
            media.setHWDecoderEnabled(!software,false);
            Diagnostics.event("decoder",software?"VLC software":"MediaCodec auto");
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
        Button button=new Button(this); button.setText(label); button.setAllCaps(false); button.setTextColor(Color.WHITE); button.setBackgroundColor(Color.TRANSPARENT); button.setOnClickListener(v->action.run()); return button;
    }
    private ImageButton icon(int image,String description,Runnable action) { ImageButton b=new ImageButton(this); b.setImageResource(image); b.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE)); b.setContentDescription(description); b.setBackgroundColor(Color.TRANSPARENT); int size=Math.min(dp(60),(getResources().getDisplayMetrics().widthPixels-dp(24))/6); int padding=Math.max(dp(8),(size-dp(30))/2); b.setPadding(padding,padding,padding,padding); b.setScaleType(ImageView.ScaleType.FIT_CENTER); b.setLayoutParams(new LinearLayout.LayoutParams(size,size)); b.setOnClickListener(v->action.run()); return b; }
    private void showControls() { overlay.setVisibility(View.VISIBLE); ui.removeCallbacks(hideControls); ui.postDelayed(hideControls,4000); }
    private void more() {
        new AlertDialog.Builder(this).setItems(new String[]{"Скорость воспроизведения","Масштаб видео"},(d,n)->{
            if(n==0) { String[] labels={"0.5×","0.75×","1×","1.25×","1.5×","2×"}; float[] rates={.5f,.75f,1,1.25f,1.5f,2}; new AlertDialog.Builder(this).setTitle("Скорость").setItems(labels,(dialog,i)->player.setRate(rates[i])).show(); }
            else new AlertDialog.Builder(this).setTitle("Масштаб видео").setItems(new String[]{"Вписать","Заполнить","16:9","4:3"},(dialog,i)->{ player.setAspectRatio(i==2?"16:9":i==3?"4:3":null); player.setScale(i==1?1.5f:0); }).show();
        }).show();
    }
    private void jump(long amount) { if(player!=null) player.setTime(Math.max(0,Math.min(player.getLength(),player.getTime()+amount))); }
    private void tracks(boolean subtitles) {
        MediaPlayer.TrackDescription[] tracks=subtitles?player.getSpuTracks():player.getAudioTracks();
        if(tracks==null || tracks.length==0) { Toast.makeText(this,"Дорожек нет",Toast.LENGTH_SHORT).show(); return; }
        String[] names=new String[tracks.length]; int selected=-1, active=subtitles?player.getSpuTrack():player.getAudioTrack();
        for(int i=0;i<tracks.length;i++) { names[i]=tracks[i].id==-1?"Выключить":tracks[i].name; if(tracks[i].id==active) selected=i; }
        new AlertDialog.Builder(this).setTitle(subtitles?"Субтитры":"Аудиодорожка").setSingleChoiceItems(names,selected,(d,n)->{ if(subtitles) player.setSpuTrack(tracks[n].id); else selectAudio(tracks[n].id); d.dismiss(); }).show();
    }
    private void selectAudio(int id) {
        player.setAudioTrack(id); if(movie!=null || savedUri!=null) getSharedPreferences("playback",0).edit().putInt(audioKey,id).apply();
    }
    private void initialAudio() {
        if(audioHandled || !started) return;
        MediaPlayer.TrackDescription[] available=player.getAudioTracks();
        ArrayList<MediaPlayer.TrackDescription> audio=new ArrayList<>();
        if(available!=null) for(var track:available) if(track.id>=0) audio.add(track);
        if(audio.isEmpty() && System.currentTimeMillis()<audioDeadline) return;
        audioHandled=true;
        int remembered=movie==null && savedUri==null?Integer.MIN_VALUE:getSharedPreferences("playback",0).getInt(audioKey,Integer.MIN_VALUE);
        for(var track:audio) if(track.id==remembered && player.getAudioTrack()!=remembered) player.setAudioTrack(remembered);
        if(!askAudio || audio.size()<2) { player.setVolume(100); return; }
        // LibVLC 3.7.7 JNI calls set_pause(1). Also enforce this on a late Playing event.
        player.pause();
        String[] names=new String[audio.size()]; int selected=0;
        for(int i=0;i<names.length;i++) { names[i]=audio.get(i).name; if(audio.get(i).id==player.getAudioTrack()) selected=i; }
        audioDialog=new AlertDialog.Builder(this).setTitle("Выберите аудиодорожку")
            .setSingleChoiceItems(names,selected,(d,n)->{
                d.dismiss(); selectAudio(audio.get(n).id); player.setTime(0); player.setVolume(100); player.play();
            }).setNegativeButton("Отмена",(d,n)->finish()).setOnCancelListener(d->finish()).create();
        audioDialog.show();
    }
    private static String clock(long ms) { long s=Math.max(0,ms/1000); return String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,(s/60)%60,s%60); }
    private final Runnable tick=new Runnable() { public void run() {
        if(player==null || isFinishing()) return;
        initialAudio();
        if(started && player.getLength()>0) {
            long position=player.getTime(), length=player.getLength();
            if(!seeking) seek.setProgress((int)(Math.max(0,position)*10000/length));
            status.setText((buffering<100?"Буферизация: "+(int)buffering+" % · ":"")+clock(position)+" / "+clock(length));
            if(System.currentTimeMillis()-lastSaved>5000) { checkpoint(); lastSaved=System.currentTimeMillis(); }
        }
        ui.postDelayed(this,1000);
    }};
    private void checkpoint() {
        checkpoint(false);
    }
    private void checkpoint(boolean finalUpdate) {
        Diagnostics.event("checkpoint","final="+finalUpdate);
        if(player==null || !started) return;
        long position=completed?0:Math.max(0,player.getTime());
        long duration=Math.max(0,player.getLength());
        setResult(RESULT_OK,new Intent().putExtra("extra_position",position));
        if(movie!=null && !movie.isEmpty()) WatchState.save(this,movie,getIntent().getStringExtra("episode")==null?"":getIntent().getStringExtra("episode"),getIntent().getStringExtra("torrent")==null?"":getIntent().getStringExtra("torrent"),getIntent().getIntExtra("file",0),position);
        if(savedUri!=null) Library.progress(this,savedUri,position,duration,completed);
        if(movie!=null && !movie.isEmpty() && !movie.startsWith("local-file:") && (finalUpdate || saving.compareAndSet(false,true))) io.submit(()->{ try {
            JSONObject update=new JSONObject().put("position",position).put("duration",duration).put("completed",completed);
            String torrent=getIntent().getStringExtra("torrent");
            if(torrent!=null && !torrent.isEmpty()) update.put("torrent",torrent).put("file",getIntent().getIntExtra("file",0));
            String episode=getIntent().getStringExtra("episode"); if(episode!=null && !episode.isEmpty()) update.put("episode",episode);
            new Api(this).request("PUT","/v1/history/"+movie,update);
        } catch(Exception ignored) { } finally { saving.set(false); } });
    }
    @Override protected void onPause() { checkpoint(true); if(player!=null) player.pause(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) { if(player!=null) state.putLong("position",Math.max(0,player.getTime())); super.onSaveInstanceState(state); }
    @Override public void finish() { checkpoint(true); super.finish(); }
    @Override protected void onDestroy() {
        if(audioDialog!=null) audioDialog.dismiss();
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
