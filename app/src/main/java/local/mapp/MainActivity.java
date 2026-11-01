package local.mapp;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
    private int BG=0xfffff8f2, TEXT=0xff29201a, MUTED=0xff6e625a, BUTTON=0xffffdec5;
    private static final int CARD=0xffffffff, ACCENT=0xffb84d00;
    private final ExecutorService io=Executors.newFixedThreadPool(4);
    private final ExecutorService images=Executors.newFixedThreadPool(2);
    private final Handler ui=new Handler(Looper.getMainLooper());
    private LinearLayout root, body;
    private String screen="home", playingMovie="";
    private String selectedTorrent="";
    private String selectedRelease="";
    private boolean selectedSeries=false;
    private int selectedFile=0;
    private int generation=0;
    private JSONObject currentMovie, pendingDownload;
    private String preferredRelease="";
    private JSONObject chosenFile;
    private String chosenTorrent="", selectedEpisode="";
    private int sourceSelection;
    private String preferredEpisode="";
    private String playingEpisode="", playingTorrent="";
    private int playingFile;
    private SharedPreferences prefs;
    interface Job { JSONObject run() throws Exception; }
    interface Result { void accept(JSONObject data) throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); prefs=getSharedPreferences("settings",0);
        if(state!=null) { playingMovie=state.getString("playingMovie",""); playingEpisode=state.getString("playingEpisode",""); playingTorrent=state.getString("playingTorrent",""); playingFile=state.getInt("playingFile"); }
        handle(getIntent());
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("playingMovie",playingMovie); state.putString("playingEpisode",playingEpisode); state.putString("playingTorrent",playingTorrent); state.putInt("playingFile",playingFile); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handle(intent); }
    private void handle(Intent intent) {
        Uri uri=intent.getData();
        if(uri!=null && "mapp".equals(uri.getScheme()) && "pair".equals(uri.getHost())) { pair(uri); intent.setData(null); }
        else if(intent.getBooleanExtra("library",false)) library(); else home();
    }
    private int dp(int n) { return (int)(n*getResources().getDisplayMetrics().density); }
    private GradientDrawable rounded(int color) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(18)); return d; }
    private TextView text(String value,int size,int color) {
        TextView view=new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0,dp(8),0,dp(8)); return view;
    }
    private Button button(String title,Runnable click) {
        Button b=new Button(this); b.setText(title); b.setAllCaps(false); b.setTextColor(ACCENT);
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xff777777,contrast(BUTTON)}));
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},rounded(0xffe9eeeb));
        states.addState(new int[]{},rounded(BUTTON));
        var ripple=new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x25b84d00),states,rounded(Color.WHITE));
        b.setBackgroundTintList(null);
        b.setBackground(new android.graphics.drawable.InsetDrawable(ripple,0,dp(4),0,dp(4)));
        b.setMinHeight(dp(56)); b.setMinimumHeight(dp(56)); b.setPadding(dp(16),dp(8),dp(16),dp(8));
        b.setOnClickListener(v->click.run()); return b;
    }
    private void page(String name,String title) {
        BG=prefs.getInt("background_color",0xfffff8f2); BUTTON=prefs.getInt("button_color",0xffffdec5);
        TEXT=contrast(BG); MUTED=TEXT;
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(TEXT==Color.WHITE?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        screen=name; generation++;
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setPadding(dp(22),dp(16),dp(22),dp(16));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(dp(22)+bars.left,dp(12)+bars.top,dp(22)+bars.right,dp(12)+bars.bottom);
            } else v.setPadding(dp(22)+insets.getSystemWindowInsetLeft(),dp(12)+insets.getSystemWindowInsetTop(),dp(22)+insets.getSystemWindowInsetRight(),dp(12)+insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading=text(title,28,TEXT); heading.setTypeface(null,Typeface.BOLD);
        bar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        bar.addView(button(name.equals("home")?"Настройки":"Назад",()->{ if(name.equals("home")) settings(); else home(); })); root.addView(bar);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
    }
    private void section(String name) { TextView h=text(name,21,TEXT); h.setTypeface(null,Typeface.BOLD); body.addView(h); }
    private void divider() { View v=new View(this); v.setBackgroundColor(0xffd7e2db); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1)); p.setMargins(0,dp(22),0,dp(16)); body.addView(v,p); }
    private void notice(String message) { new AlertDialog.Builder(this).setMessage(message).setPositiveButton("Понятно",null).show(); }
    private void resetBusy(View view) {
        if(view instanceof Button && Boolean.TRUE.equals(view.getTag())) { view.setTag(null); view.setEnabled(true); ((Button)view).setText("Повторить"); }
        if(view instanceof android.view.ViewGroup group) for(int i=0;i<group.getChildCount();i++) resetBusy(group.getChildAt(i));
    }
    private void error(Exception e) { if(!isFinishing()) { if(body!=null) resetBusy(body); notice(e.getMessage()==null?"Не удалось выполнить запрос":e.getMessage()); } }
    private void task(Job job,Result result) {
        int version=generation;
        io.submit(()->{ try { JSONObject data=job.run(); ui.post(()->{ if(version==generation && !isFinishing()) try { result.accept(data); } catch(Exception e) { error(e); } }); }
            catch(Exception e) { ui.post(()->{ if(version==generation && !isFinishing()) error(e); }); } });
    }
    private JSONObject request(String method,String path,JSONObject data) throws Exception { return new Api(this).request(method,path,data); }
    private void pair(Uri uri) {
        page("pair","Подключение"); body.addView(text("Проверяем приглашение MCore…",17,MUTED));
        task(()->{
            JSONObject session=new JSONObject().put("server",uri.getQueryParameter("server")).put("pin",uri.getQueryParameter("pin"));
            Api api=new Api(session);
            JSONObject result=api.request("POST","/v1/pair",new JSONObject().put("token",uri.getQueryParameter("token"))
                .put("device",Settings.Secure.getString(getContentResolver(),Settings.Secure.ANDROID_ID)));
            session.put("key",result.getString("key")).put("user_id",result.getString("user_id")); Vault.save(this,session); prefs.edit().putString("last_server",session.getString("server")).apply(); return result;
        },result->home());
    }
    private void home() {
        page("home","MApp");
        try { if(Vault.load(this).optString("key").isEmpty()) { welcome(); return; } } catch(Exception e) { welcome(); return; }
        section("История"); LinearLayout history=new LinearLayout(this); body.addView(history);
        history.addView(text("Загружаем историю…",15,MUTED)); divider();
        section("Поиск"); body.addView(button("Название фильма или сериала",()->searchPage("")));
        LinearLayout recent=new LinearLayout(this); recent.setOrientation(LinearLayout.VERTICAL); body.addView(recent); divider();
        LinearLayout savedHeader=new LinearLayout(this); savedHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView savedTitle=text("Сохранённое",21,TEXT); savedTitle.setTypeface(null,Typeface.BOLD);
        savedHeader.addView(savedTitle,new LinearLayout.LayoutParams(0,-2,1)); savedHeader.addView(button("Подробнее →",this::library)); body.addView(savedHeader); strip(body,Library.grouped(this),true);
        task(()->request("GET","/v1/home",null),data->{
            history.removeAllViews(); strip(history,data.getJSONArray("history"),false);
            JSONArray queries=data.getJSONArray("queries");
            for(int i=0;i<Math.min(4,queries.length());i++) {
                String q=queries.getString(i); TextView t=text("↗  "+q,16,MUTED); t.setMaxLines(1); t.setEllipsize(TextUtils.TruncateAt.END); t.setOnClickListener(v->searchPage(q)); recent.addView(t);
            }
        });
    }
    private void welcome() {
        ImageView logo=new ImageView(this); logo.setImageResource(R.drawable.camera); body.addView(logo,new LinearLayout.LayoutParams(-1,dp(190)));
        section("Добро пожаловать"); body.addView(text("Откройте ссылку-приглашение от администратора MCore. Она действует две минуты и привяжет приложение к вашему серверу.",17,MUTED));
        EditText link=new EditText(this); link.setTextColor(TEXT); link.setHintTextColor(MUTED); link.setHint("mapp://pair?…"); link.setSingleLine(true); body.addView(link);
        body.addView(button("Подключиться по ссылке",()->{ Uri u=Uri.parse(link.getText().toString().trim()); if("mapp".equals(u.getScheme()) && "pair".equals(u.getHost())) pair(u); else notice("Нужна ссылка mapp://pair"); }));
        body.addView(button("Открыть сохранённые фильмы",this::library));
    }
    private void strip(LinearLayout target,JSONArray items,boolean saved) {
        if(items.length()==0) { TextView empty=text(saved?"Фильмы для офлайн-просмотра появятся здесь":"Здесь будут ваши просмотры",15,MUTED); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(112)); p.topMargin=dp(16); target.addView(empty,p); return; }
        HorizontalScrollView scroll=new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row=new LinearLayout(this); scroll.addView(row);
        for(int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i); LinearLayout card=new LinearLayout(this); card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(12),dp(10),dp(12),dp(10)); card.setBackground(rounded(CARD));
            ImageView poster=new ImageView(this); poster.setImageResource(R.drawable.camera); card.addView(poster,new LinearLayout.LayoutParams(dp(62),dp(88))); loadPoster(poster,item.optString(saved?"movie":"id"));
            if(saved) savedPoster(poster,item.optJSONObject("card"));
            LinearLayout info=new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(dp(12),0,0,0); card.addView(info,new LinearLayout.LayoutParams(0,-1,1));
            TextView title=text(item.optString("title"),15,0xff29201a); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); info.addView(title,new LinearLayout.LayoutParams(-1,0,1));
            TextView caption=text(saved?stateLabel(item):item.optString("year"),12,0xff6e625a); caption.setMaxLines(1); info.addView(caption);
            card.setOnClickListener(v->{ if(saved) savedCard(item); else movie(item.optString("id")); });
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(250),dp(112)); p.setMargins(0,0,dp(12),0); row.addView(card,p);
        }
        LinearLayout.LayoutParams spacing=new LinearLayout.LayoutParams(-1,dp(112)); spacing.topMargin=dp(16); target.addView(scroll,spacing);
    }
    private void searchPage(String initial) {
        page("search","Поиск"); EditText input=new EditText(this); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setSingleLine(true); input.setHint("Фильм или сериал"); input.setImeOptions(EditorInfo.IME_ACTION_SEARCH); body.addView(input);
        TextView status=text("Введите название — ищем и точные, и похожие совпадения",14,MUTED); body.addView(status);
        LinearLayout results=new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); body.addView(results);
        Runnable[] pending={null}; int[] sequence={0};
        java.util.function.Consumer<Boolean> find=live->{
            String q=input.getText().toString().trim(); if(q.length()<2) return;
            int n=++sequence[0]; status.setText(live?"Ищем на сервере…":"Подсказки из индекса…");
            task(()->request("GET","/v1/search?q="+Uri.encode(q)+"&live="+live,null),data->{
                if(n!=sequence[0]) return;
                results.removeAllViews(); JSONArray movies=data.getJSONArray("movies");
                status.setText(movies.length()==0?"Совпадений пока нет": "Найдено: "+movies.length());
                if(data.getJSONArray("unavailable_providers").length()>0) status.append(" • Часть трекеров недоступна");
                boolean approximate=false;
                for(int i=0;i<movies.length();i++) {
                    JSONObject item=movies.getJSONObject(i);
                    if(!item.optBoolean("exact") && !approximate) {
                        approximate=true; View line=new View(this); line.setBackgroundColor(0xffc8bdb5); results.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
                        results.addView(text("Похожие названия",16,MUTED));
                    }
                    results.addView(movieRow(item,()->movie(item.optString("id"))));
                }
            });
        };
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int st,int count,int after) { }
            public void onTextChanged(CharSequence s,int st,int before,int count) { if(pending[0]!=null) ui.removeCallbacks(pending[0]); pending[0]=()->find.accept(false); ui.postDelayed(pending[0],400); }
            public void afterTextChanged(Editable s) { }
        });
        input.setOnEditorActionListener((v,id,event)->{ if(id==EditorInfo.IME_ACTION_SEARCH) { find.accept(true); return true; } return false; });
        body.addView(button("Искать на всех трекерах",()->find.accept(true)),2);
        input.setText(initial); if(!initial.isEmpty()) { if(pending[0]!=null) ui.removeCallbacks(pending[0]); find.accept(true); }
    }
    private View movieRow(JSONObject item,Runnable click) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(12),dp(10),dp(12),dp(10)); row.setBackground(rounded(CARD));
        ImageView image=new ImageView(this); image.setImageResource(R.drawable.camera); row.addView(image,new LinearLayout.LayoutParams(dp(43),dp(61))); loadPoster(image,item.optString(item.has("state")?"movie":"id"));
        savedPoster(image,item.optJSONObject("card"));
        LinearLayout info=new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(dp(14),0,0,0);
        TextView title=text(item.optString("title"),17,0xff29201a); title.setMaxLines(2); info.addView(title);
        info.addView(text(item.optString("year")+"  "+item.optString("director"),13,0xff6e625a));
        if(item.has("state")) { TextView progress=text(stateLabel(item),13,ACCENT); progress.setTag(item.optString("id")); info.addView(progress); }
        row.addView(info,new LinearLayout.LayoutParams(0,-2,1)); row.setOnClickListener(v->click.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(6),0,dp(6)); row.setLayoutParams(p); return row;
    }
    private void movie(String id) {
        preferredRelease="";
        page("movie","Карточка фильма"); body.addView(text("Обновляем раздачи и доступность источников…",16,MUTED));
        task(()->request("GET","/v1/movies/"+id,null),this::showMovie);
    }
    private void loadPoster(ImageView image,String mid) {
        if(mid.isEmpty()) return;
        loadImage(image,"/v1/movies/"+mid+"/poster");
    }
    private void loadImage(ImageView image,String path) {
        int version=generation;
        image.setTag(path);
        images.submit(()->{
            if(version!=generation) return;
            try {
                String server=Vault.load(this).optString("server",prefs.getString("last_server",""));
                byte[] bytes=ImageCache.get(this,server,path);
                if(bytes==null) {
                    var connection=new Api(this).open(path);
                    try {
                        if(connection.getResponseCode()!=200) return;
                        try(var input=connection.getInputStream()) { bytes=Api.read(input,4*1024*1024); }
                        ImageCache.put(this,server,path,bytes);
                    } finally { connection.disconnect(); }
                }
                    android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options(); options.inJustDecodeBounds=true;
                    android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                    options.inSampleSize=Math.max(1,Math.max(options.outWidth,options.outHeight)/600); options.inJustDecodeBounds=false;
                    var bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                    ui.post(()->{ if(version==generation && path.equals(image.getTag()) && bitmap!=null) { image.setImageBitmap(bitmap); image.setScaleType(path.endsWith("/poster") && image.getLayoutParams().width<0?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP); image.setVisibility(View.VISIBLE); } });
            } catch(Exception ignored) { /* Source may not provide a poster; keep the local icon. */ }
        });
    }
    private void savedPoster(ImageView image,JSONObject card) {
        if(card==null || card.optString("poster_thumbnail").isEmpty()) return;
        try { byte[] bytes=android.util.Base64.decode(card.getString("poster_thumbnail"),android.util.Base64.NO_WRAP); var bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length); if(bitmap!=null) { image.setImageBitmap(bitmap); image.setScaleType(image.getLayoutParams().width<0?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP); } } catch(Exception ignored) { }
    }
    private void showMovie(JSONObject data) throws Exception {
        generation++; chosenFile=null; chosenTorrent=""; selectedEpisode=""; preferredEpisode="";
        currentMovie=data; body.removeAllViews(); section(data.getString("title"));
        ImageView poster=new ImageView(this); poster.setImageResource(R.drawable.camera); body.addView(poster,new LinearLayout.LayoutParams(-1,dp(156))); loadPoster(poster,data.optString("id"));
        savedPoster(poster,data);
        body.addView(text(data.optString("year")+"  "+data.optString("director"),15,MUTED));
        body.addView(text(data.optString("description").isEmpty()?"Описание пока не предоставлено источником":data.optString("description"),16,TEXT));
        JSONArray allReleases=data.getJSONArray("releases"); TreeSet<String> seasons=new TreeSet<>();
        for(int i=0;i<allReleases.length();i++) { String season=allReleases.optJSONObject(i).optString("season"); if(!season.isEmpty()) seasons.add(season); }
        String chosen=data.optString("chosen_season",seasons.isEmpty()?"":seasons.first());
        if(!data.has("chosen_season")) {
            for(JSONObject item:matchingSaved(data)) { if(!item.optString("state").equals("complete") || item.optString("release").equals(preferredRelease))
                for(int n=0;n<allReleases.length();n++) if(item.optString("release").equals(allReleases.optJSONObject(n).optString("id"))) chosen=allReleases.optJSONObject(n).optString("season"); }
        }
        if(!seasons.isEmpty()) {
            section("Сезон"); ArrayList<String> choices=new ArrayList<>(seasons); choices.add("Все сезоны"); String selection=chosen;
            Spinner season=new Spinner(this); season.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices));
            season.setSelection(Math.max(0,choices.indexOf(chosen))); body.addView(season);
            season.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onNothingSelected(AdapterView<?> p) { }
                public void onItemSelected(AdapterView<?> p,View v,int n,long id) {
                    if(v instanceof TextView) ((TextView)v).setTextColor(TEXT);
                    if(!choices.get(n).equals(selection)) try { data.put("chosen_season",choices.get(n)); showMovie(data); } catch(Exception e) { error(e); }
                }
            });
        }
        JSONArray releases=new JSONArray();
        for(int i=0;i<allReleases.length();i++) { JSONObject r=allReleases.optJSONObject(i); if(chosen.isEmpty() || chosen.equals("Все сезоны") || r.optString("season").equals(chosen)) releases.put(r); }
        if(releases.length()==0) { savedActions(data); return; }
        divider(); section("Качество");
        String[] labels=new String[releases.length()]; int selected=0;
        for(int i=0;i<releases.length();i++) { JSONObject r=releases.getJSONObject(i); labels[i]="●  "+r.getString("resolution")+" · "+r.getString("size")+(r.optString("season").isEmpty()?"":" · Сезон "+r.optString("season")); if(r.getString("resolution").equals(prefs.getString("quality","1080p"))) selected=i; }
        JSONObject resumeRelease=data.optJSONObject("resume");
        if(resumeRelease!=null) for(int i=0;i<releases.length();i++) if(releases.optJSONObject(i).optString("id").equals(resumeRelease.optString("release"))) selected=i;
        for(JSONObject saved:matchingSaved(data)) { if(!saved.optString("state").equals("complete")) for(int n=0;n<releases.length();n++) if(saved.optString("release").equals(releases.optJSONObject(n).optString("id"))) selected=n; }
        for(int i=0;i<releases.length();i++) if(releases.optJSONObject(i).optString("id").equals(preferredRelease)) selected=i;
        Spinner spinner=new Spinner(this); spinner.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels) {
            private View tint(View view,int position) {
                int seeds=releases.optJSONObject(position).optInt("seeds");
                android.text.SpannableString label=new android.text.SpannableString(labels[position]);
                label.setSpan(new android.text.style.ForegroundColorSpan(seeds>=10?0xff218739:seeds>=5?0xffe78016:seeds>0?0xffcf3030:Color.BLACK),0,1,0);
                ((TextView)view).setText(label); return view;
            }
            @Override public View getView(int p,View v,ViewGroup parent) { View row=tint(super.getView(p,v,parent),p); ((TextView)row).setTextColor(TEXT); return row; }
            @Override public View getDropDownView(int p,View v,ViewGroup parent) { return tint(super.getDropDownView(p,v,parent),p); }
        }); body.addView(spinner);
        TextView health=text("",14,MUTED), audio=text("",14,MUTED); audio.setGravity(Gravity.CENTER); body.addView(health); body.addView(audio);
        HorizontalScrollView screenshots=new HorizontalScrollView(this); screenshots.setHorizontalScrollBarEnabled(false);
        LinearLayout frames=new LinearLayout(this); screenshots.addView(frames); body.addView(screenshots);
        LinearLayout episodePanel=new LinearLayout(this); episodePanel.setOrientation(LinearLayout.VERTICAL); body.addView(episodePanel);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent,View v,int position,long id) {
                JSONObject r=releases.optJSONObject(position); int seeds=r.optInt("seeds");
                if(chosenFile!=null) preferredEpisode=chosenFile.optString("episode");
                chosenFile=null; chosenTorrent=""; selectedEpisode="";
                int selection=++sourceSelection;
                episodePanel.removeAllViews();
                loadSource(r,"",episodePanel,audio,selection,System.currentTimeMillis());
                health.setText(r.optString("release_title")+(r.optBoolean("swarm_stale")?"\nДоступность: последнее известное состояние":""));
                audio.setText(r.optString("audio").isEmpty()?"Дорожки и субтитры доступны в меню плеера":r.optString("audio"));
                frames.removeAllViews();
                for(int i=0;i<3;i++) {
                    ImageView frame=new ImageView(MainActivity.this); frame.setContentDescription("Скриншот раздачи "+(i+1)); frame.setVisibility(View.GONE);
                    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(260),dp(146)); p.setMargins(0,dp(12),dp(10),dp(12)); frames.addView(frame,p);
                    loadImage(frame,"/v1/releases/"+Uri.encode(r.optString("id"))+"/screenshots/"+i);
                }
            }
        }); spinner.setSelection(selected);
        final int initialSelection=selected;
        Button watch=button(data.optLong("position")>0?"Продолжить просмотр":"Смотреть",()->{});
        View.OnClickListener streamWatch=v->{
            watch.setTag(Boolean.TRUE);
            if(chosenFile!=null) {
                selectedRelease=releases.optJSONObject(spinner.getSelectedItemPosition()).optString("id");
                selectedSeries=releases.optJSONObject(spinner.getSelectedItemPosition()).optBoolean("series");
                buffer(chosenTorrent,chosenFile,watch,System.currentTimeMillis()); return;
            }
            JSONObject resume=data.optJSONObject("resume");
            if(resume!=null && data.optLong("position")>0 && resume.optString("release").equals(releases.optJSONObject(spinner.getSelectedItemPosition()).optString("id"))) {
                watch.setEnabled(false); watch.setText("Возвращаемся к просмотру…");
                task(()->{
                    try { return request("GET","/v1/torrents/"+resume.getString("torrent")+"?file="+resume.getInt("file"),null); }
                    catch(Api.Failure e) { if(e.code==404) return new JSONObject().put("evicted",true); throw e; }
                },status->{
                    if(status.optBoolean("evicted")) { prepare(releases.optJSONObject(spinner.getSelectedItemPosition()),false,watch); return; }
                    JSONArray files=status.getJSONArray("files");
                    for(int i=0;i<files.length();i++) if(files.getJSONObject(i).getInt("index")==resume.getInt("file")) {
                        buffer(resume.getString("torrent"),files.getJSONObject(i),watch,System.currentTimeMillis()); return;
                    }
                    watch.setEnabled(true); notice("Источник больше не находится в кеше. Выберите раздачу заново.");
                });
            } else { if(spinner.getSelectedItemPosition()!=initialSelection) try { data.put("position",0); } catch(JSONException ignored) { } prepare(releases.optJSONObject(spinner.getSelectedItemPosition()),false,watch); }
        }; watch.setOnClickListener(streamWatch); body.addView(watch);
        Button download=button("Скачать на телефон",()->{});
        download.setOnClickListener(v->{
            if(chosenFile!=null) { selectedRelease=releases.optJSONObject(spinner.getSelectedItemPosition()).optString("id"); selectedSeries=releases.optJSONObject(spinner.getSelectedItemPosition()).optBoolean("series"); saveFile(chosenTorrent,chosenFile); }
            else prepare(releases.optJSONObject(spinner.getSelectedItemPosition()),true,download);
        }); body.addView(download);
        int cardGeneration=generation;
        boolean[] checkingBuffer={false};
        Runnable refreshDownloads=new Runnable() { public void run() {
            if(cardGeneration!=generation || currentMovie!=data) return;
            JSONObject release=releases.optJSONObject(spinner.getSelectedItemPosition());
            JSONObject existing=null;
            for(JSONObject item:matchingSaved(data)) if(item.optString("release").equals(release.optString("id")) &&
                (!item.optBoolean("series") || (chosenFile!=null && item.optString("path").equals("/v1/torrents/"+chosenTorrent+"/files/"+chosenFile.optInt("index"))))) { existing=item; break; }
            if(!Boolean.TRUE.equals(download.getTag())) {
                download.setText(existing==null?"Скачать на телефон":stateLabel(existing));
                download.setEnabled(existing==null);
            }
            if(!Boolean.TRUE.equals(watch.getTag())) {
                watch.setOnClickListener(streamWatch);
                if(existing==null || existing.optString("state").equals("complete")) {
                    watch.setVisibility(View.VISIBLE); watch.setEnabled(true);
                    watch.setText(data.optLong("position")>0?"Продолжить просмотр":"Смотреть");
                }
                if(existing!=null && existing.optString("state").equals("complete")) {
                    JSONObject local=existing;
                    watch.setEnabled(true); watch.setText("Смотреть сохранённое");
                    watch.setOnClickListener(v->playSaved(local));
                } else if(existing!=null && !existing.optString("path").isEmpty() && !checkingBuffer[0]) {
                    JSONObject local=existing; String rid=release.optString("id"); checkingBuffer[0]=true;
                    io.submit(()->{
                        JSONObject status=null;
                        try { String[] parts=local.getString("path").split("/"); status=request("GET","/v1/torrents/"+parts[3]+"?file="+parts[5]+"&buffer="+prefs.getInt("buffer",15)+"&position="+data.optLong("position"),null); } catch(Exception ignored) { }
                        JSONObject result=status;
                        ui.post(()->{
                            checkingBuffer[0]=false;
                            if(cardGeneration!=generation || currentMovie!=data || !rid.equals(releases.optJSONObject(spinner.getSelectedItemPosition()).optString("id")) || Boolean.TRUE.equals(watch.getTag())) return;
                            boolean ready=result==null || result.optBoolean("ready");
                            watch.setVisibility(ready?View.VISIBLE:View.GONE); watch.setEnabled(ready);
                            watch.setText(data.optLong("position")>0?"Продолжить просмотр":"Смотреть");
                            if(!ready) download.setText(stateLabel(local)+" · буфер: "+(result.isNull("buffer_eta")?"ожидание пиров":result.optInt("buffer_eta")+" с"));
                        });
                    });
                }
            }
            ui.postDelayed(this,1500);
        }};
        refreshDownloads.run();
        savedActions(data);
    }
    private void loadSource(JSONObject release,String torrent,LinearLayout panel,TextView audio,int selection,long started) {
        int version=generation;
        io.submit(()->{
            try {
                String key=torrent.isEmpty()?request("POST","/v1/torrents",new JSONObject().put("release",release.getString("id"))).getString("id"):torrent;
                JSONObject status=request("GET","/v1/torrents/"+key,null);
                ui.post(()->{
                    if(version!=generation || selection!=sourceSelection) return;
                    JSONArray files=status.optJSONArray("files");
                    if(files==null || files.length()==0) {
                        if(System.currentTimeMillis()-started<120000) ui.postDelayed(()->{ if(version==generation && selection==sourceSelection) loadSource(release,key,panel,audio,selection,started); },2000);
                        return;
                    }
                    chosenTorrent=key;
                    ArrayList<JSONObject> ordered=new ArrayList<>(); for(int i=0;i<files.length();i++) ordered.add(files.optJSONObject(i));
                    ordered.sort(Comparator.comparingInt((JSONObject f)->f.optInt("season",0)).thenComparingInt(f->f.optInt("number",Integer.MAX_VALUE)).thenComparing(f->f.optString("name")));
                    String[] names=new String[ordered.size()]; int selected=0; JSONObject resume=currentMovie.optJSONObject("resume");
                    for(int i=0;i<names.length;i++) {
                        JSONObject file=ordered.get(i);
                        names[i]=file.isNull("number")?file.optString("name"):"Сезон "+file.optInt("season")+" · Серия "+file.optInt("number");
                        if(file.optBoolean("completed")) names[i]+=" · просмотрено"; else if(file.optLong("position")>0) names[i]+=" · продолжить";
                        if(resume!=null && key.equals(resume.optString("torrent")) && file.optInt("index")==resume.optInt("file")) selected=i;
                        if(!preferredEpisode.isEmpty() && preferredEpisode.equals(file.optString("episode"))) selected=i;
                    }
                    java.util.function.Consumer<JSONObject> choose=file->{
                        chosenFile=file; selectedEpisode=file.optString("episode");
                        try { if(file.has("episode")) currentMovie.put("position",file.optLong("position")); } catch(Exception ignored) { }
                        showMediaInfo(key,file,audio,selection,0);
                    };
                    panel.removeAllViews();
                    if(files.length()>1 || release.optBoolean("series")) {
                        panel.addView(text("Серия / файл",18,TEXT)); Spinner episodes=new Spinner(MainActivity.this);
                        episodes.setTag("episode-selector");
                        episodes.setAdapter(new ArrayAdapter<>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,names));
                        episodes.setSelection(selected); panel.addView(episodes);
                        episodes.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                            public void onNothingSelected(AdapterView<?> p) { }
                            public void onItemSelected(AdapterView<?> p,View v,int n,long id) { if(v instanceof TextView) ((TextView)v).setTextColor(TEXT); choose.accept(ordered.get(n)); }
                        });
                    }
                    choose.accept(ordered.get(selected));
                });
            } catch(Exception ignored) { /* Offline cards remain usable from the saved library. */ }
        });
    }
    private void showMediaInfo(String key,JSONObject file,TextView audio,int selection,int attempt) {
        int version=generation;
        io.submit(()->{
            try {
                JSONObject info=request("GET","/v1/torrents/"+key+"/files/"+file.optInt("index")+"/info",null);
                StringBuilder text=new StringBuilder("Длительность: "+info.optLong("duration_ms")/60000+" мин · "+String.format(Locale.ROOT,"%.1f",info.optLong("average_bitrate")/1000000.0)+" Мбит/с");
                JSONArray streams=info.optJSONArray("streams");
                if(streams!=null) for(int i=0;i<streams.length();i++) {
                    JSONObject stream=streams.optJSONObject(i); String type=stream.optString("codec_type"); JSONObject tags=stream.optJSONObject("tags");
                    if(type.equals("audio") || type.equals("subtitle")) text.append("\n").append(type.equals("audio")?"Аудио: ":"Субтитры: ").append(stream.optString("codec_name")).append(tags==null?"":" · "+tags.optString("language")+" "+tags.optString("title"));
                }
                ui.post(()->{ if(version==generation && selection==sourceSelection && chosenFile==file) audio.setText(text.toString()); });
            } catch(Exception ignored) {
                if(attempt<10) ui.postDelayed(()->{ if(version==generation && selection==sourceSelection && chosenFile==file) showMediaInfo(key,file,audio,selection,attempt+1); },3000);
            }
        });
    }
    private void playSaved(JSONObject item) {
        selectedTorrent=""; selectedEpisode=item.optString("episode");
        String[] parts=item.optString("path").split("/");
        if(parts.length==6) try { selectedTorrent=parts[3]; selectedFile=Integer.parseInt(parts[5]); } catch(Exception ignored) { }
        play(Uri.parse(item.optString("uri")),item.optString("movie"),item.optLong("position",currentMovie==null?0:currentMovie.optLong("position")));
    }
    private void savedActions(JSONObject data) {
        Button open=button("Открыть сохранённое",()->{
            ArrayList<JSONObject> matches=matchingSaved(data);
            String[] names=new String[matches.size()]; for(int i=0;i<names.length;i++) names[i]=matches.get(i).optString("name",matches.get(i).optString("title"))+" · "+stateLabel(matches.get(i));
            new AlertDialog.Builder(this).setTitle("Сохранённые файлы").setItems(names,(d,n)->openSaved(matches.get(n))).show();
        }); body.addView(open);
        int version=generation;
        Runnable refresh=new Runnable() { public void run() { if(version!=generation || currentMovie!=data) return; open.setEnabled(!matchingSaved(data).isEmpty()); ui.postDelayed(this,2000); } }; refresh.run();
    }
    private ArrayList<JSONObject> matchingSaved(JSONObject data) {
        ArrayList<JSONObject> matches=new ArrayList<>(); JSONArray saved=Library.all(this), releases=data.optJSONArray("releases"), aliases=data.optJSONArray("aliases");
        String server=data.optString("server"); if(server.isEmpty()) try { server=Vault.load(this).optString("server"); } catch(Exception ignored) { }
        for(int i=0;i<saved.length();i++) {
            JSONObject item=saved.optJSONObject(i); boolean match=!data.optString("id").isEmpty() && item.optString("movie").equals(data.optString("id"));
            if(!item.optString("server").isEmpty() && !server.isEmpty() && !server.equals(item.optString("server"))) continue;
            if(aliases!=null) for(int n=0;n<aliases.length();n++) if(item.optString("movie").equals(aliases.optString(n))) match=true;
            if(releases!=null) for(int n=0;n<releases.length();n++) if(!item.optString("release").isEmpty() && item.optString("release").equals(releases.optJSONObject(n).optString("id"))) match=true;
            if(match && FolderLibrary.exists(this,item)) matches.add(item);
        }
        matches.sort(Comparator.comparing(i->i.optString("name"),String.CASE_INSENSITIVE_ORDER)); return matches;
    }
    private void openSaved(JSONObject item) {
        ArrayList<String> choices=new ArrayList<>();
        if(item.optString("state").equals("complete")) choices.add("Смотреть");
        else { if(item.optString("state").equals("paused") && !item.optString("path").isEmpty()) choices.add("Продолжить загрузку"); if(!item.optString("path").isEmpty()) choices.add("Смотреть с сервера"); }
        choices.add("Удалить файл");
        new AlertDialog.Builder(this).setTitle(item.optString("name",item.optString("title"))).setItems(choices.toArray(new String[0]),(d,n)->{
            switch(choices.get(n)) {
                case "Смотреть" -> playSaved(item);
                case "Продолжить загрузку" -> { startForegroundService(new Intent(this,DownloadService.class).putExtra("item",item.toString())); library(); }
                case "Смотреть с сервера" -> { try {
                    String[] parts=item.getString("path").split("/"); selectedRelease=item.optString("release");
                    JSONObject file=new JSONObject().put("index",Integer.parseInt(parts[5])).put("size",item.getLong("size")).put("name",item.getString("name"));
                    Button watch=button("Буферизация…",()->{}); body.addView(watch); buffer(parts[3],file,watch,System.currentTimeMillis());
                } catch(Exception e) { error(e); } }
                default -> new AlertDialog.Builder(this).setMessage("Удалить "+item.optString("name")+"?").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(dialog,which)->{
                    try { if(DownloadService.queued.contains(item.optString("id"))) DownloadService.cancelled.add(item.optString("id")); else FolderLibrary.remove(this,item); library(); } catch(Exception e) { error(e); }
                }).show();
            }
        }).show();
    }
    private void prepare(JSONObject release,boolean download,Button button) {
        if(release==null) return;
        selectedRelease=release.optString("id"); selectedSeries=release.optBoolean("series");
        button.setTag(Boolean.TRUE); button.setEnabled(false); button.setText("Получаем раздачу…");
        task(()->request("POST","/v1/torrents",new JSONObject().put("release",release.getString("id"))),data->chooseFile(data.getString("id"),download,button,System.currentTimeMillis()));
    }
    private void chooseFile(String torrent,boolean download,Button button,long started) {
        task(()->request("GET","/v1/torrents/"+torrent,null),status->{
            JSONArray files=status.getJSONArray("files");
            if(files.length()==0) {
                if(System.currentTimeMillis()-started>120000) { button.setTag(null); button.setEnabled(true); button.setText("Повторить"); notice("Метаданные пока недоступны. Возможно, нет доступных пиров."); return; }
                button.setText("Ожидаем метаданные…"); int version=generation;
                ui.postDelayed(()->{ if(version==generation) chooseFile(torrent,download,button,started); },1500); return;
            }
            java.util.function.Consumer<JSONObject> select=file->{
                button.setTag(null); button.setEnabled(true);
                if(download) saveFile(torrent,file); else buffer(torrent,file,button,System.currentTimeMillis());
            };
            selectedSeries=selectedSeries || files.length()>1;
            if(files.length()==1) select.accept(files.getJSONObject(0));
            else chooseSeason(files,select,button);
        });
    }
    private void chooseSeason(JSONArray files,java.util.function.Consumer<JSONObject> select,Button button) {
        TreeMap<Integer,ArrayList<JSONObject>> seasons=new TreeMap<>();
        java.util.regex.Pattern pattern=java.util.regex.Pattern.compile("(?i)(?:(?:s|season[ ._-]*|сезон[ ._-]*)(\\d{1,3})|(\\d{1,2})x\\d{1,3})");
        for(int i=0;i<files.length();i++) {
            JSONObject file=files.optJSONObject(i); var match=pattern.matcher(file.optString("name"));
            int season=match.find()?Integer.parseInt(match.group(1)!=null?match.group(1):match.group(2)):0;
            seasons.computeIfAbsent(season,k->new ArrayList<>()).add(file);
        }
        java.util.function.Consumer<ArrayList<JSONObject>> episodes=items->{
            items.sort(Comparator.comparing(f->f.optString("name"),String.CASE_INSENSITIVE_ORDER));
            String[] names=new String[items.size()]; for(int i=0;i<names.length;i++) names[i]=items.get(i).optString("name");
            new AlertDialog.Builder(this).setTitle("Серия / файл").setItems(names,(d,n)->select.accept(items.get(n)))
                .setNegativeButton("Отмена",(d,n)->{ button.setTag(null); button.setEnabled(true); button.setText("Повторить"); }).setOnCancelListener(d->button.setTag(null)).show();
        };
        if(seasons.size()==1) episodes.accept(seasons.firstEntry().getValue());
        else {
            ArrayList<Integer> keys=new ArrayList<>(seasons.keySet()); String[] names=new String[keys.size()];
            for(int i=0;i<names.length;i++) names[i]=keys.get(i)==0?"Другие файлы":"Сезон "+keys.get(i);
            new AlertDialog.Builder(this).setTitle("Выберите сезон").setItems(names,(d,n)->episodes.accept(seasons.get(keys.get(n))))
                .setNegativeButton("Отмена",(d,n)->{ button.setTag(null); button.setEnabled(true); button.setText("Повторить"); }).setOnCancelListener(d->button.setTag(null)).show();
        }
    }
    private void buffer(String torrent,JSONObject file,Button button,long started) {
        button.setTag(Boolean.TRUE); button.setEnabled(false);
        int seconds=prefs.getInt("buffer",15);
        long position=file.has("episode")?file.optLong("position"):currentMovie.optLong("position");
        task(()->request("GET","/v1/torrents/"+torrent+"?file="+file.getInt("index")+"&buffer="+seconds+"&position="+position,null),status->{
            if(!status.isNull("error") && !status.optString("error").isEmpty()) {
                button.setTag(null); button.setEnabled(true); button.setText("Повторить"); notice(status.getString("error")); return;
            }
            if(status.optBoolean("ready")) {
                button.setTag(null); button.setEnabled(true); button.setText("Продолжить просмотр");
                selectedTorrent=torrent; selectedFile=file.getInt("index");
                selectedEpisode=file.optString("episode");
                Uri uri=MediaProvider.create(this,"/v1/torrents/"+torrent+"/files/"+file.getInt("index"),file.getLong("size"),file.getString("name"));
                play(uri,currentMovie.optString("id"),position); return;
            }
            if(System.currentTimeMillis()-started>300000) { button.setTag(null); button.setEnabled(true); button.setText("Повторить буферизацию"); notice("Буфер пока не готов. Попробуйте раздачу с большим числом сидов."); return; }
            button.setText(status.isNull("buffer_eta")?"Буферизация • ожидаем пиров…":"Буферизация • примерно "+status.optInt("buffer_eta")+" с");
            int version=generation; ui.postDelayed(()->{ if(version==generation) buffer(torrent,file,button,started); },1500);
        });
    }
    private void play(Uri uri,String movie,long position) {
        playingMovie=movie;
        playingEpisode=selectedEpisode; playingTorrent=selectedTorrent; playingFile=selectedFile;
        if(!prefs.getBoolean("external_player",false)) {
            Intent embedded=new Intent(this,PlayerActivity.class).setData(uri).putExtra("movie",movie).putExtra("position",position).putExtra("torrent",selectedTorrent).putExtra("file",selectedFile).putExtra("episode",selectedEpisode);
            if(!"local.mapp.media".equals(uri.getAuthority())) embedded.putExtra("saved_uri",uri.toString());
            startActivityForResult(embedded,200); return;
        }
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("MApp video",uri));
        intent.putExtra("position",(int)position); intent.putExtra("from_start",position==0); intent.putExtra("return_result",true);
        intent.putExtra("extra_position",position);
        String torrent=selectedTorrent, episode=selectedEpisode; int file=selectedFile;
        try { startActivityForResult(Intent.createChooser(intent,"Открыть в плеере"),200); if(!movie.isEmpty()) io.submit(()->{ try {
            JSONObject update=new JSONObject().put("position",position); if(!torrent.isEmpty()) update.put("torrent",torrent).put("file",file);
            if(!episode.isEmpty()) update.put("episode",episode);
            request("PUT","/v1/history/"+movie,update);
        } catch(Exception ignored) { } }); }
        catch(ActivityNotFoundException e) { notice("Установите внешний видеоплеер, например VLC или MX Player."); }
    }
    private void saveFile(String torrent,JSONObject file) {
        try {
            String mediaPath="/v1/torrents/"+torrent+"/files/"+file.getInt("index");
            JSONArray saved=Library.all(this); String server=new Api(this).server;
            for(int i=0;i<saved.length();i++) if(saved.optJSONObject(i).optString("path").equals(mediaPath) && saved.optJSONObject(i).optString("server").equals(server)) { library(); return; }
            String filename=file.getString("name"); filename=filename.substring(filename.lastIndexOf('/')+1);
            JSONObject cardCopy=new JSONObject(currentMovie.toString()); cardCopy.put("server",server).put("poster_thumbnail",ImageCache.thumbnail(this,server,currentMovie.optString("id")));
            pendingDownload=new JSONObject().put("id",UUID.randomUUID().toString()).put("movie",currentMovie.getString("id"))
                .put("title",currentMovie.getString("title")).put("year",currentMovie.optString("year")).put("director",currentMovie.optString("director"))
                .put("path",mediaPath).put("release",selectedRelease).put("series",selectedSeries).put("size",file.getLong("size"))
                .put("episode",file.optString("episode")).put("position",file.optLong("position"))
                .put("name",filename).put("server",new Api(this).server).put("state","queued").put("card",cardCopy);
            if(prefs.getBoolean("ask_path",true)) {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),103);
            } else {
                String folder=prefs.getString("folder",""); Uri uri;
                if(!folder.isEmpty()) {
                    Uri tree=Uri.parse(folder); uri=FolderLibrary.create(this,tree,filename); pendingDownload.put("folder",folder);
                } else {
                    ContentValues values=new ContentValues(); values.put(MediaStore.Downloads.DISPLAY_NAME,filename); values.put(MediaStore.Downloads.MIME_TYPE,"video/x-matroska");
                    values.put(MediaStore.Downloads.RELATIVE_PATH,"Download/MApp"); values.put(MediaStore.Downloads.IS_PENDING,1);
                    uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values); pendingDownload.put("mediastore",true);
                }
                startDownload(uri);
            }
        } catch(Exception e) { error(e); }
    }
    private void startDownload(Uri uri) throws Exception {
        if(uri==null || pendingDownload==null) throw new java.io.IOException("Не удалось создать файл");
        try(var cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) { if(cursor!=null && cursor.moveToFirst()) pendingDownload.put("name",cursor.getString(0)); }
        pendingDownload.put("uri",uri.toString()).put("queued_at",System.currentTimeMillis()); Library.put(this,pendingDownload);
        FolderLibrary.backup(this,pendingDownload);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},300);
        startForegroundService(new Intent(this,DownloadService.class).putExtra("item",pendingDownload.toString())); pendingDownload=null; library();
    }
    private String stateLabel(JSONObject item) {
        return switch(item.optString("state")) {
            case "complete" -> "Скачано";
            case "paused" -> "Пауза · продолжить";
            default -> "Загрузка · "+(item.optLong("downloaded")*100/Math.max(1,item.optLong("size")))+" %";
        };
    }
    private void library() {
        page("library","Сохранённое");
        ArrayList<JSONObject> items=new ArrayList<>(); JSONArray all=Library.all(this); for(int i=0;i<all.length();i++) items.add(all.optJSONObject(i));
        for(JSONObject item:items) {
            String state=item.optString("state");
            if((state.equals("downloading") || (state.equals("queued") && System.currentTimeMillis()-item.optLong("queued_at")>10000)) && !DownloadService.queued.contains(item.optString("id"))) {
                try { item.put("state","paused").put("error","Загрузка была прервана. Нажмите, чтобы продолжить."); Library.put(this,item); } catch(Exception ignored) { }
            }
        }
        items.sort(Comparator.comparing((JSONObject j)->j.optString("state").equals("complete")).thenComparing(j->j.optString("title"),String.CASE_INSENSITIVE_ORDER));
        if(items.isEmpty()) body.addView(text("Пока пусто. Скачайте фильм из его карточки. После переустановки выберите прежнюю папку в настройках.",16,MUTED));
        JSONArray grouped=Library.grouped(this);
        for(int i=0;i<grouped.length();i++) {
            JSONObject item=grouped.optJSONObject(i); LinearLayout row=(LinearLayout)movieRow(item,()->savedCard(item));
            row.addView(button("Удалить",()->deleteSavedGroup(item))); body.addView(row);
        }
        int version=generation;
        Runnable refresh=new Runnable() { public void run() {
            if(version!=generation) return;
            JSONArray latest=Library.all(MainActivity.this);
            for(int i=0;i<latest.length();i++) {
                JSONObject item=latest.optJSONObject(i);
                updateDownloadLabels(body,item);
            }
            ui.postDelayed(this,1500);
        }};
        ui.postDelayed(refresh,1500);
    }
    private void updateDownloadLabels(View view,JSONObject item) {
        if(view instanceof TextView && item.optString("id").equals(view.getTag())) ((TextView)view).setText(stateLabel(item));
        if(view instanceof android.view.ViewGroup group) for(int i=0;i<group.getChildCount();i++) updateDownloadLabels(group.getChildAt(i),item);
    }
    private void deleteSavedGroup(JSONObject item) {
        ArrayList<JSONObject> files=new ArrayList<>(); JSONArray all=Library.all(this);
        for(int i=0;i<all.length();i++) { JSONObject file=all.optJSONObject(i);
            if(file.optString("server").equals(item.optString("server")) && file.optString("movie",file.optString("title")).equals(item.optString("movie",item.optString("title")))) files.add(file);
        }
        new AlertDialog.Builder(this).setTitle("Удалить сохранённое?").setMessage(item.optString("title")+"\nФайлов: "+files.size())
            .setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,n)->{
                task(()->{ for(JSONObject file:files) {
                    if(DownloadService.queued.contains(file.optString("id"))) DownloadService.cancelled.add(file.optString("id"));
                    else FolderLibrary.remove(this,file);
                } return new JSONObject(); },result->library());
            }).show();
    }
    private void savedCard(JSONObject item) {
        if(item.optString("movie").isEmpty()) try { item.put("movie","local-file:"+item.optString("id").hashCode()); Library.put(this,item); } catch(Exception ignored) { }
        preferredRelease=item.optString("release");
        page("movie","Карточка фильма");
        body.addView(text("Открываем карточку…",16,MUTED));
        if(item.optJSONObject("card")!=null) try { showMovie(new JSONObject(item.optJSONObject("card").toString()).put("position",item.optLong("position"))); } catch(Exception ignored) { }
        task(()->{
            if(!item.optString("movie").isEmpty() && !item.optString("movie").startsWith("local-file:")) try {
                if(item.optString("server").isEmpty() || item.optString("server").equals(new Api(this).server)) return request("GET","/v1/movies/"+item.optString("movie"),null);
            } catch(Exception ignored) { }
            if(item.optJSONObject("card")!=null) return new JSONObject(item.optJSONObject("card").toString()).put("position",item.optLong("position"));
            return new JSONObject(item.toString()).put("id",item.optString("movie")).put("releases",new JSONArray());
        },this::showMovie);
    }
    private void settings() {
        page("settings","Настройки"); section("Сервер");
        try {
            JSONObject session=Vault.load(this);
            body.addView(text(session.optString("server","Не подключён"),15,MUTED));
            if(!session.optString("key").isEmpty()) body.addView(button("Выйти из аккаунта",this::confirmLogout));
            else body.addView(button("Войти по приглашению",this::home));
        } catch(Exception e) { body.addView(button("Сбросить подключение",this::confirmLogout)); }
        divider(); section("Оформление");
        body.addView(button("Цвет фона",()->chooseColor("background_color",BG)));
        body.addView(button("Цвет кнопок",()->chooseColor("button_color",BUTTON)));
        divider(); section("Воспроизведение");
        Switch external=new Switch(this); external.setText("Использовать внешний плеер"); external.setTextColor(TEXT); external.setChecked(prefs.getBoolean("external_player",false)); body.addView(external);
        external.setOnCheckedChangeListener((b,value)->prefs.edit().putBoolean("external_player",value).apply());
        Switch audioPrompt=new Switch(this); audioPrompt.setText("Спрашивать аудиодорожку при новом просмотре"); audioPrompt.setTextColor(TEXT); audioPrompt.setChecked(prefs.getBoolean("ask_audio",false)); body.addView(audioPrompt);
        audioPrompt.setOnCheckedChangeListener((b,value)->prefs.edit().putBoolean("ask_audio",value).apply());
        body.addView(text("Выбор перед просмотром поддерживается встроенным плеером. Во внешнем — через его меню дорожек.",14,MUTED));
        String[] quality={"480p","720p","1080p","2160p"}; Spinner q=new Spinner(this); q.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,quality));
        q.setSelection(Math.max(0,Arrays.asList(quality).indexOf(prefs.getString("quality","1080p"))));
        body.addView(text("Предпочитаемое разрешение",16,TEXT)); body.addView(q);
        q.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { public void onNothingSelected(AdapterView<?> p) { } public void onItemSelected(AdapterView<?> p,View v,int n,long id) { if(v instanceof TextView) ((TextView)v).setTextColor(TEXT); prefs.edit().putString("quality",quality[n]).apply(); } });
        TextView buffer=text("Буферизация: "+prefs.getInt("buffer",15)+" с",16,TEXT); body.addView(buffer);
        SeekBar seek=new SeekBar(this); seek.setMin(5); seek.setMax(120); seek.setProgress(prefs.getInt("buffer",15)); body.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onStartTrackingTouch(SeekBar s) { } public void onStopTrackingTouch(SeekBar s) { } public void onProgressChanged(SeekBar s,int n,boolean user) { prefs.edit().putInt("buffer",n).apply(); buffer.setText("Буферизация: "+n+" с"); } });
        body.addView(text("Встроенный плеер: AVI/MKV/MP4, перемотка, аудиодорожки, субтитры и сохранение позиции. Внешний плеер можно выбрать выше.",14,MUTED));
        divider(); section("Загрузки"); Switch ask=new Switch(this); ask.setTextColor(TEXT); ask.setText("Спрашивать место сохранения"); ask.setChecked(prefs.getBoolean("ask_path",true)); body.addView(ask);
        ask.setOnCheckedChangeListener((b,value)->prefs.edit().putBoolean("ask_path",value).apply());
        body.addView(text(prefs.getString("folder","").isEmpty()?"По умолчанию: Download/MApp":"Выбрана папка на устройстве",15,MUTED));
        body.addView(button("Выбрать папку по умолчанию",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),101)));
        body.addView(button("Использовать Download/MApp",()->{ prefs.edit().remove("folder").apply(); settings(); }));
        body.addView(text("Скачанные файлы остаются после удаления MApp. Выберите прежнюю папку — библиотека восстановится автоматически только из неё.",14,MUTED));
        try { body.addView(text("MApp "+getPackageManager().getPackageInfo(getPackageName(),0).versionName,13,MUTED)); } catch(Exception ignored) { }
        divider(); section("Каталог сервера"); TextView sync=text("Получаем состояние синхронизации…",14,MUTED); body.addView(sync);
        syncProgress(sync,generation);
    }
    private void syncProgress(TextView label,int version) {
        io.submit(()->{ try {
            JSONObject state=request("GET","/v1/health",null).getJSONObject("sync"); StringBuilder value=new StringBuilder("Раздач в индексе: "+state.optInt("known_releases")); JSONArray jobs=state.optJSONArray("jobs");
            if(jobs!=null) for(int i=0;i<jobs.length();i++) { JSONObject job=jobs.optJSONObject(i); String kind=job.optString("kind"); value.append("\n").append(kind.equals("archive")?"Страницы архива":kind.equals("detail")?"Карточки":"Поисковые обновления").append(": ").append(job.optInt("visited")).append(" / ").append(job.optInt("total")); if(job.optInt("retrying")>0) value.append(" · повтор: ").append(job.optInt("retrying")); }
            ui.post(()->{ if(version==generation) { label.setText(value.toString()); ui.postDelayed(()->{ if(version==generation) syncProgress(label,version); },10000); } });
        } catch(Exception ignored) { ui.post(()->{ if(version==generation) label.setText("Сервер сейчас недоступен"); }); } });
    }
    private static int contrast(int color) {
        return (Color.red(color)*299+Color.green(color)*587+Color.blue(color)*114)>150000?0xff29201a:Color.WHITE;
    }
    private void chooseColor(String key,int color) {
        EditText value=new EditText(this); value.setSingleLine(true); value.setHint("#RRGGBB"); value.setText(String.format(Locale.ROOT,"#%06X",color&0xffffff));
        LinearLayout panel=new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(20),dp(8),dp(20),dp(8));
        LinearLayout palette=new LinearLayout(this); panel.addView(palette);
        int[] colors=key.equals("background_color")?new int[]{0xfffff8f2,0xfff0f8f0,0xffedf4ff,0xfff6eeff,0xff202329}:new int[]{0xffffdec5,0xffbce6c3,0xffbdd7ff,0xffd7b9ef,0xff40444b};
        for(int choice:colors) { View swatch=new View(this); swatch.setBackground(rounded(choice)); swatch.setContentDescription(String.format(Locale.ROOT,"#%06X",choice&0xffffff)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1); p.setMargins(dp(3),0,dp(3),dp(8)); palette.addView(swatch,p); swatch.setOnClickListener(v->value.setText(String.format(Locale.ROOT,"#%06X",choice&0xffffff))); }
        panel.addView(value); TextView preview=text("Пример",18,contrast(color)); preview.setGravity(Gravity.CENTER); preview.setBackground(rounded(color)); panel.addView(preview);
        value.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int start,int count,int after) { } public void onTextChanged(CharSequence s,int start,int before,int count) { if(s.toString().matches("#[0-9a-fA-F]{6}")) { int c=Color.parseColor(s.toString()); preview.setTextColor(contrast(c)); preview.setBackground(rounded(c)); } } public void afterTextChanged(Editable s) { } });
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(key.equals("background_color")?"Цвет фона":"Цвет кнопок").setView(panel).setNegativeButton("Отмена",null).setPositiveButton("Применить",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String hex=value.getText().toString().trim();
            if(!hex.matches("#[0-9a-fA-F]{6}")) { value.setError("Например: #FFF8F2"); return; }
            prefs.edit().putInt(key,Color.parseColor(hex)).apply(); dialog.dismiss(); settings();
        })); dialog.show();
    }
    private void confirmLogout() {
        new AlertDialog.Builder(this).setTitle("Выйти из аккаунта?")
            .setMessage("История останется на сервере, скачанные файлы — на телефоне. Текущие загрузки остановятся. Для входа понадобится новая ссылка-приглашение.")
            .setNegativeButton("Отмена",null).setPositiveButton("Выйти",(dialog,which)->{
                try {
                    Vault.clear(this);
                    stopService(new Intent(this,DownloadService.class));
                    playingMovie=""; selectedTorrent=""; selectedRelease="";
                    currentMovie=null; pendingDownload=null;
                    getIntent().setData(null);
                    home();
                } catch(Exception e) { error(e); }
            }).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        try {
            if(request==200 && data!=null && !playingMovie.isEmpty()) {
                long position=data.hasExtra("extra_position")?data.getLongExtra("extra_position",-1):data.getIntExtra("position",-1);
                if(position>=0 && currentMovie!=null && currentMovie.optString("id").equals(playingMovie)) currentMovie.put("position",position);
                if(position>=0 && chosenFile!=null && chosenTorrent.equals(playingTorrent) && chosenFile.optInt("index")==playingFile) chosenFile.put("position",position);
                if(position>=0 && prefs.getBoolean("external_player",false)) { String movie=playingMovie, episode=playingEpisode, torrent=playingTorrent; int file=playingFile; io.submit(()->{ try {
                    JSONObject update=new JSONObject().put("position",position);
                    if(!torrent.isEmpty()) update.put("torrent",torrent).put("file",file);
                    if(!episode.isEmpty()) update.put("episode",episode);
                    request("PUT","/v1/history/"+movie,update);
                } catch(Exception ignored) { } }); }
            }
            if(result!=RESULT_OK || data==null) return;
            if(request==100) { persist(data.getData(),data.getFlags()); startDownload(data.getData()); }
            if(request==101 || request==103) {
                Uri tree=data.getData(); persist(tree,data.getFlags());
                if(request==101) prefs.edit().putString("folder",tree.toString()).apply();
                page("restore","Сохранённое"); body.addView(text("Проверяем выбранную папку…",16,MUTED));
                task(()->new JSONObject().put("restored",FolderLibrary.restore(this,tree)),restored->{
                    if(request==103 && pendingDownload!=null) { pendingDownload.put("folder",tree.toString()); startDownload(FolderLibrary.create(this,tree,pendingDownload.getString("name"))); }
                    else { settings(); Toast.makeText(this,"Добавлено файлов: "+restored.optInt("restored"),Toast.LENGTH_LONG).show(); }
                });
            }
        } catch(Exception e) { error(e); }
    }
    private void persist(Uri uri,int flags) { getContentResolver().takePersistableUriPermission(uri,flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); }
    @Override public void onBackPressed() {
        if(!screen.equals("home")) { home(); return; }
        new AlertDialog.Builder(this).setTitle("Выйти из MApp?").setPositiveButton("Выйти",(d,n)->finish()).setNegativeButton("Остаться",null).show();
    }
    @Override protected void onDestroy() { generation++; ui.removeCallbacksAndMessages(null); io.shutdown(); images.shutdownNow(); super.onDestroy(); }
}
