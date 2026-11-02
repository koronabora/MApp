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
    private final ExecutorService images=Executors.newFixedThreadPool(6);
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
    private Dialog screenshotDialog;
    private LinearLayout searchReturnRoot, searchReturnBody;
    private boolean sourceLoading;
    private boolean folderPrompt, batchDownload;
    private String playingUri="";
    private final ArrayList<JSONObject> pendingBatch=new ArrayList<>();
    interface Job { JSONObject run() throws Exception; }
    interface Result { void accept(JSONObject data) throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); prefs=getSharedPreferences("settings",0);
        Diagnostics.init(this); Diagnostics.event("lifecycle","MainActivity created");
        if(state!=null) { playingMovie=state.getString("playingMovie",""); playingEpisode=state.getString("playingEpisode",""); playingTorrent=state.getString("playingTorrent",""); playingFile=state.getInt("playingFile"); playingUri=state.getString("playingUri",""); try { if(state.containsKey("pendingDownload")) pendingDownload=new JSONObject(state.getString("pendingDownload")); JSONArray batch=new JSONArray(state.getString("pendingBatch","[]")); for(int i=0;i<batch.length();i++) pendingBatch.add(batch.getJSONObject(i)); } catch(Exception ignored) { } }
        if(state!=null && state.getString("card")!=null) {
            try { page("movie","Карточка фильма"); preferredRelease=state.getString("release",""); preferredEpisode=state.getString("episode",""); showMovie(new JSONObject(state.getString("card"))); }
            catch(Exception e) { handle(getIntent()); }
        } else handle(getIntent());
        if(!prefs.getBoolean("folder_prompted",false) && prefs.getString("folder","").isEmpty()) ui.post(()->{
            if(isFinishing() || folderPrompt) return; folderPrompt=true;
            new AlertDialog.Builder(this).setTitle("Папка для скачивания").setMessage("Выберите папку для видео. Если есть карта памяти, можно выбрать папку на ней. Файлы останутся после удаления приложения.")
                .setPositiveButton("Выбрать папку",(d,n)->pickFolder(101)).setNegativeButton("Позже",(d,n)->prefs.edit().putBoolean("folder_prompted",true).apply()).show();
        });
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("playingMovie",playingMovie); state.putString("playingEpisode",playingEpisode); state.putString("playingTorrent",playingTorrent); state.putInt("playingFile",playingFile);
        if(screen.equals("movie") && currentMovie!=null) { state.putString("card",currentMovie.toString()); state.putString("release",selectedRelease); state.putString("episode",selectedEpisode); }
        if(pendingDownload!=null) state.putString("pendingDownload",pendingDownload.toString());
        state.putString("pendingBatch",new JSONArray(pendingBatch).toString()); state.putString("playingUri",playingUri);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handle(intent); }
    private void handle(Intent intent) {
        Uri uri=intent.getData();
        if(uri!=null && "mapp".equals(uri.getScheme()) && "pair".equals(uri.getHost())) { pair(uri); intent.setData(null); }
        else if(intent.getBooleanExtra("library",false)) { intent.removeExtra("library"); library(); } else if(root==null) home();
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
        screen=name; generation++; Diagnostics.event("screen",name);
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
        if(name.equals("home")) { heading.setTextSize(32); heading.setTypeface(Typeface.create(getResources().getFont(R.font.manrope),700,false)); heading.setTag("home-heading"); }
        bar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button navigation=button(name.equals("home")?"Настройки":"Назад",()->{ if(name.equals("home")) settings(); else back(); });
        bar.addView(navigation,new LinearLayout.LayoutParams(name.equals("home")?homeButtonWidth():-2,-2)); root.addView(bar);
        if(name.equals("movie")) { View line=new View(this); line.setTag("fixed-card-divider"); line.setBackgroundColor(0xffd8d8d2); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1)); p.setMargins(0,dp(10),0,dp(10)); root.addView(line,p); }
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
    }
    private void section(String name) { TextView h=text(name,21,TEXT); h.setTypeface(null,Typeface.BOLD); body.addView(h); }
    private void divider() { View v=new View(this); v.setBackgroundColor(0xffd7e2db); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(screen.equals("home")?2:1)); p.setMargins(0,dp(22),0,dp(16)); body.addView(v,p); }
    private int homeButtonWidth() { return Math.min(dp(124),(getResources().getDisplayMetrics().widthPixels-dp(44))/2); }
    private void cardAction(Button action) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams((int)(getResources().getDisplayMetrics().widthPixels/1.61803398875),-2); p.gravity=Gravity.CENTER_HORIZONTAL; body.addView(action,p);
    }
    private void notice(String message) { new AlertDialog.Builder(this).setMessage(message).setPositiveButton("Понятно",null).show(); }
    private void resetBusy(View view) {
        if(view instanceof Button && Boolean.TRUE.equals(view.getTag())) { view.setTag(null); view.setEnabled(true); ((Button)view).setText("Повторить"); }
        if(view instanceof android.view.ViewGroup group) for(int i=0;i<group.getChildCount();i++) resetBusy(group.getChildAt(i));
    }
    private void error(Exception e) { Diagnostics.event("ui-error",e.getClass().getSimpleName()); if(!isFinishing()) { if(body!=null) resetBusy(body); notice(e.getMessage()==null?"Не удалось выполнить запрос":e.getMessage()); } }
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
        searchReturnRoot=null; searchReturnBody=null;
        page("home","Кинозал");
        try { if(Vault.load(this).optString("key").isEmpty()) { welcome(); return; } } catch(Exception e) { welcome(); return; }
        LinearLayout homeBody=body;
        LinearLayout historySection=new LinearLayout(this); historySection.setOrientation(LinearLayout.VERTICAL); homeBody.addView(historySection,new LinearLayout.LayoutParams(-1,-2,1)); body=historySection;
        divider(); section("История просмотра"); LinearLayout history=new LinearLayout(this); body.addView(history);
        history.addView(text("Загружаем историю…",15,MUTED)); divider();
        LinearLayout searchSection=new LinearLayout(this); searchSection.setOrientation(LinearLayout.VERTICAL); homeBody.addView(searchSection,new LinearLayout.LayoutParams(-1,-2,1)); body=searchSection;
        LinearLayout searchHeader=new LinearLayout(this); searchHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView searchTitle=text("Поиск",21,TEXT); searchTitle.setTypeface(null,Typeface.BOLD); searchHeader.addView(searchTitle,new LinearLayout.LayoutParams(0,-2,1));
        ImageButton search=new ImageButton(this); search.setImageResource(R.drawable.search); search.setImageTintList(android.content.res.ColorStateList.valueOf(contrast(BUTTON))); search.setBackground(rounded(BUTTON)); search.setContentDescription("Поиск фильма или сериала"); search.setOnClickListener(v->searchPage("")); searchHeader.addView(search,new LinearLayout.LayoutParams(homeButtonWidth(),dp(56))); body.addView(searchHeader);
        LinearLayout recent=new LinearLayout(this); recent.setOrientation(LinearLayout.VERTICAL); body.addView(recent); divider();
        LinearLayout savedSection=new LinearLayout(this); savedSection.setOrientation(LinearLayout.VERTICAL); homeBody.addView(savedSection,new LinearLayout.LayoutParams(-1,-2,1)); body=savedSection;
        LinearLayout savedHeader=new LinearLayout(this); savedHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView savedTitle=text("Скачанное",21,TEXT); savedTitle.setTypeface(null,Typeface.BOLD);
        savedHeader.addView(savedTitle,new LinearLayout.LayoutParams(0,-2,1)); savedHeader.addView(button("Подробнее",this::library),new LinearLayout.LayoutParams(homeButtonWidth(),-2)); body.addView(savedHeader); strip(body,Library.grouped(this),true);
        body=homeBody; for(LinearLayout part:new LinearLayout[]{historySection,searchSection,savedSection}) part.setMinimumHeight(dp(204));
        task(()->request("GET","/v1/home",null),data->{
            history.removeAllViews(); strip(history,data.getJSONArray("history"),false);
            JSONArray visits=new JSONArray(prefs.getString(visitKey(),"[]"));
            if(visits.length()>0) strip(recent,visits,false);
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
            TextView director=text(director(item),12,0xff6e625a); director.setTag("director"); director.setPadding(0,0,0,0); director.setMaxLines(1); director.setEllipsize(TextUtils.TruncateAt.END); info.addView(director);
            TextView caption=text(saved?caption(item)+" · "+stateLabel(item):caption(item),12,0xff6e625a); caption.setMaxLines(1); info.addView(caption);
            card.setOnClickListener(v->{ if(saved) savedCard(item); else movie(item.optString("id")); });
            int width=Math.max(180,Math.min(300,(int)(title.getPaint().measureText(item.optString("title"))/getResources().getDisplayMetrics().density)+100));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(width),dp(112)); p.setMargins(0,0,dp(12),0); row.addView(card,p);
        }
        LinearLayout.LayoutParams spacing=new LinearLayout.LayoutParams(-1,dp(112)); spacing.topMargin=dp(16); target.addView(scroll,spacing);
    }
    private void searchPage(String initial) {
        searchReturnRoot=null; searchReturnBody=null;
        page("search","Поиск"); EditText input=new EditText(this); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setSingleLine(true); input.setHint("Фильм или сериал"); input.setImeOptions(EditorInfo.IME_ACTION_SEARCH); body.addView(input);
        LinearLayout searchRoot=root;
        GradientDrawable border=rounded(BG); border.setStroke(dp(1),MUTED); input.setBackground(border); input.setPadding(dp(14),dp(12),dp(14),dp(12));
        LinearLayout.LayoutParams inputParams=new LinearLayout.LayoutParams(-1,dp(56)); inputParams.setMargins(0,dp(16),0,dp(16)); input.setLayoutParams(inputParams);
        TextView status=text("Введите название — ищем и точные, и похожие совпадения",14,MUTED); body.addView(status);
        LinearLayout results=new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); body.addView(results);
        LinearLayout recent=new LinearLayout(this); recent.setOrientation(LinearLayout.VERTICAL); body.addView(recent);
        task(()->request("GET","/v1/home",null),data->{
            View line=new View(this); line.setBackgroundColor(0xffd7e2db); LinearLayout.LayoutParams lineParams=new LinearLayout.LayoutParams(-1,dp(1)); lineParams.setMargins(0,dp(16),0,dp(8)); recent.addView(line,lineParams);
            TextView historyHeading=text("История поиска",18,TEXT); historyHeading.setTypeface(null,Typeface.BOLD); recent.addView(historyHeading); JSONArray queries=data.getJSONArray("queries");
            for(int i=0;i<queries.length();i++) { String q=queries.getString(i); TextView row=text(q,16,TEXT==Color.WHITE?0xffb8b8b8:0xff737373); row.setTag("search-history-entry"); row.setPadding(dp(14),dp(8),0,dp(8)); row.setMinHeight(dp(48)); row.setOnClickListener(v->searchPage(q)); recent.addView(row); }
            recent.setVisibility(input.length()==0?View.VISIBLE:View.GONE);
        });
        Runnable[] pending={null}; int[] sequence={0};
        java.util.function.Consumer<Boolean> find=live->{
            if(root!=searchRoot || !screen.equals("search")) return;
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
                    if(!item.optBoolean("exact") && !item.optBoolean("keyword_match") && !approximate) {
                        approximate=true; View line=new View(this); line.setBackgroundColor(0xffc8bdb5); results.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
                        results.addView(text("Похожие названия",16,MUTED));
                    }
                    results.addView(movieRow(item,()->{ rememberVisit(item); movie(item.optString("id")); }));
                }
            });
        };
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int st,int count,int after) { }
            public void onTextChanged(CharSequence s,int st,int before,int count) { recent.setVisibility(s.length()==0?View.VISIBLE:View.GONE); if(s.length()<2) { sequence[0]++; results.removeAllViews(); } if(pending[0]!=null) ui.removeCallbacks(pending[0]); pending[0]=()->find.accept(false); ui.postDelayed(pending[0],400); }
            public void afterTextChanged(Editable s) { }
        });
        input.setOnEditorActionListener((v,id,event)->{ if(id==EditorInfo.IME_ACTION_SEARCH) { find.accept(true); return true; } return false; });
        body.addView(button("Искать",()->find.accept(true)),2);
        input.setText(initial); if(!initial.isEmpty()) { if(pending[0]!=null) ui.removeCallbacks(pending[0]); find.accept(true); }
    }
    private View movieRow(JSONObject item,Runnable click) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(12),dp(10),dp(12),dp(10)); row.setBackground(rounded(CARD));
        ImageView image=new ImageView(this); image.setImageResource(R.drawable.camera); row.addView(image,new LinearLayout.LayoutParams(dp(43),dp(61))); loadPoster(image,item.optString(item.has("state")?"movie":"id"));
        savedPoster(image,item.optJSONObject("card"));
        LinearLayout info=new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(dp(14),0,0,0);
        TextView title=text(item.optString("title"),17,0xff29201a); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); info.addView(title);
        if(item.optBoolean("series") || !item.optString("episode").isEmpty()) { TextView episode=text(episodeLabel(item),14,ACCENT); episode.setMaxLines(1); episode.setEllipsize(TextUtils.TruncateAt.END); info.addView(episode); }
        info.addView(text(caption(item),13,0xff6e625a));
        TextView director=text(director(item),13,0xff6e625a); director.setTag("director"); director.setMaxLines(2); director.setEllipsize(TextUtils.TruncateAt.END); info.addView(director);
        if(item.has("state")) { TextView progress=text(stateLabel(item),13,ACCENT); progress.setTag(item.optString("id")); info.addView(progress); }
        int maxText=Math.max(dp(100),getResources().getDisplayMetrics().widthPixels-dp(130));
        title.setMaxWidth(maxText); director.setMaxWidth(maxText);
        row.addView(info,new LinearLayout.LayoutParams(0,-2,1)); row.setOnClickListener(v->click.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(6),0,dp(6)); row.setLayoutParams(p); return row;
    }
    private String director(JSONObject item) {
        JSONObject card=item.optJSONObject("card"); String value=item.optString("director",card==null?"":card.optString("director"));
        return value.isEmpty()?"Режиссёр не указан":"Режиссёр: "+primaryDirector(value);
    }
    private String primaryDirector(String value) { return value.split("(?i)\\s+(?:О фильме|О сериале|Описание|В ролях|Жанр|Страна)\\s*:",2)[0].split("[/,;]",2)[0].trim(); }
    private String visitKey() {
        try { JSONObject session=Vault.load(this); return "search_visits:"+session.optString("server")+":"+session.optString("user_id"); }
        catch(Exception e) { return "search_visits:offline"; }
    }
    private void rememberVisit(JSONObject item) {
        try { String key=visitKey(); JSONArray old=new JSONArray(prefs.getString(key,"[]")), next=new JSONArray(); next.put(item);
            for(int i=0;i<old.length() && next.length()<20;i++) if(!old.getJSONObject(i).optString("id").equals(item.optString("id"))) next.put(old.getJSONObject(i));
            prefs.edit().putString(key,next.toString()).apply();
        } catch(Exception e) { error(e); }
    }
    private void movie(String id) {
        if(screen.equals("search")) { searchReturnRoot=root; searchReturnBody=body; }
        preferredRelease=""; preferredEpisode="";
        page("movie","Карточка фильма"); body.addView(text("Обновляем раздачи и доступность источников…",16,MUTED));
        task(()->request("GET","/v1/movies/"+id,null),this::showMovie);
    }
    private void loadPoster(ImageView image,String mid) {
        image.setVisibility(View.GONE);
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
                        String director=connection.getHeaderField("X-MCore-Director");
                        if(director!=null && !director.isEmpty()) prefs.edit().putString("image-director:"+server+path,Uri.decode(director)).apply();
                        try(var input=connection.getInputStream()) { bytes=Api.read(input,4*1024*1024); }
                        ImageCache.put(this,server,path,bytes);
                    } finally { connection.disconnect(); }
                }
                    android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options(); options.inJustDecodeBounds=true;
                    android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                    if(path.contains("/screenshots/") && (options.outWidth<320 || options.outHeight<180 || options.outWidth<options.outHeight || options.outWidth>options.outHeight*2.5)) { ui.post(()->{ if(path.equals(image.getTag())) image.setVisibility(View.GONE); }); return; }
                    if(path.endsWith("/poster") && (options.outWidth<180 || options.outHeight<240 || options.outWidth>options.outHeight*1.35)) { ui.post(()->{ if(path.equals(image.getTag())) image.setVisibility(View.GONE); }); return; }
                    options.inSampleSize=Math.max(1,Math.max(options.outWidth,options.outHeight)/600); options.inJustDecodeBounds=false;
                    var bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                    ui.post(()->{ if(version==generation && path.equals(image.getTag()) && bitmap!=null) {
                        image.setImageBitmap(bitmap); image.setScaleType(path.endsWith("/poster") && image.getLayoutParams().width<0?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP); image.setVisibility(View.VISIBLE);
                        String director=prefs.getString("image-director:"+server+path,"");
                        if(!director.isEmpty() && image.getParent() instanceof android.view.ViewGroup group) {
                            TextView label=group.findViewWithTag("director"); if(label!=null) label.setText("Режиссёр: "+primaryDirector(director));
                        }
                    } });
            } catch(Exception ignored) { /* Source may not provide a poster; keep the local icon. */ }
        });
    }
    private void savedPoster(ImageView image,JSONObject card) {
        if(card==null || card.optString("poster_thumbnail").isEmpty()) return;
        try { byte[] bytes=android.util.Base64.decode(card.getString("poster_thumbnail"),android.util.Base64.NO_WRAP); var bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length); if(bitmap!=null && bitmap.getWidth()>=100 && bitmap.getHeight()>=140 && bitmap.getWidth()<bitmap.getHeight()*1.35) { image.setImageBitmap(bitmap); image.setVisibility(View.VISIBLE); image.setScaleType(image.getLayoutParams().width<0?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP); } } catch(Exception ignored) { }
    }
    private void showScreenshot(String path) {
        Dialog dialog=new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        if(screenshotDialog!=null) screenshotDialog.dismiss(); screenshotDialog=dialog;
        dialog.setOnDismissListener(d->{ if(screenshotDialog==dialog) screenshotDialog=null; });
        FrameLayout frame=new FrameLayout(this); frame.setBackgroundColor(Color.BLACK);
        ZoomImage image=new ZoomImage(this); frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        TextView loading=text("Загружаем скриншот…",16,Color.WHITE);
        FrameLayout.LayoutParams center=new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER); frame.addView(loading,center);
        Button close=button("×",dialog::dismiss); close.setContentDescription("Закрыть скриншот");
        FrameLayout.LayoutParams corner=new FrameLayout.LayoutParams(dp(64),dp(64),Gravity.TOP|Gravity.END); corner.topMargin=dp(28); frame.addView(close,corner);
        dialog.setContentView(frame); dialog.show();
        images.submit(()->{ try {
            var connection=new Api(this).open(path); byte[] bytes;
            try { if(connection.getResponseCode()!=200) throw new java.io.IOException(); try(var in=connection.getInputStream()) { bytes=Api.read(in,16*1024*1024); } } finally { connection.disconnect(); }
            var options=new android.graphics.BitmapFactory.Options(); options.inJustDecodeBounds=true;
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            options.inSampleSize=1;
            while((long)options.outWidth*options.outHeight/options.inSampleSize/options.inSampleSize>8000000) options.inSampleSize*=2;
            options.inJustDecodeBounds=false;
            var bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            if(bitmap==null) throw new java.io.IOException();
            ui.post(()->{ if(dialog.isShowing()) { loading.setVisibility(View.GONE); image.setImageBitmap(bitmap); image.fit(); } });
        } catch(Exception e) { ui.post(()->{ if(dialog.isShowing()) loading.setText("Скриншот сейчас недоступен"); }); } });
    }
    private void showMovie(JSONObject data) throws Exception {
        addSavedSources(data);
        generation++; sourceSelection++; currentMovie=data; chosenFile=null; chosenTorrent=""; sourceLoading=false;
        body.removeAllViews(); new MovieCard(data).show();
    }
    private void addSavedSources(JSONObject data) throws Exception {
        JSONArray works=data.optJSONArray("works"); if(works==null) { works=new JSONArray(); data.put("works",works); }
        JSONArray releases=data.optJSONArray("releases"); if(releases==null) { releases=new JSONArray(); data.put("releases",releases); }
        for(JSONObject saved:matchingSaved(data)) {
            String id=saved.optString("episode"); if(id.isEmpty()) id=saved.optBoolean("series")?"saved:"+saved.optString("id"):"movie";
            JSONObject work=null; for(int i=0;i<works.length();i++) if(id.equals(works.optJSONObject(i).optString("id"))) work=works.optJSONObject(i);
            if(work==null) { work=new JSONObject().put("id",id).put("name",saved.optString("name")).put("season",saved.opt("season")).put("number",saved.opt("number")).put("position",saved.optLong("position")).put("sources",new JSONArray()); works.put(work); }
            String rid=saved.optString("release","saved:"+saved.optString("id"));
            boolean found=false; for(int i=0;i<releases.length();i++) if(rid.equals(releases.optJSONObject(i).optString("id"))) found=true;
            if(!found) releases.put(new JSONObject().put("id",rid).put("series",saved.optBoolean("series")).put("provider","На устройстве").put("resolution","N\\A").put("size",saved.optLong("size")/1048576+" MiB"));
            JSONArray sources=work.getJSONArray("sources"); String[] path=saved.optString("path").split("/"); String torrent=path.length==6?path[3]:""; int index=path.length==6?Integer.parseInt(path[5]):0;
            found=false; for(int i=0;i<sources.length();i++) if(sources.optJSONObject(i).optString("torrent").equals(torrent) && sources.optJSONObject(i).optInt("index")==index) found=true;
            if(!found) sources.put(new JSONObject().put("release",rid).put("torrent",torrent).put("index",index).put("name",saved.optString("name")).put("size",saved.optLong("size")).put("local_id",saved.optString("id")).put("episode",id).put("season",saved.opt("season")).put("number",saved.opt("number")));
        }
    }
    private final class MovieCard {
        final JSONObject data; final int version=generation;
        final LinearLayout selectors=new LinearLayout(MainActivity.this), sources=new LinearLayout(MainActivity.this);
        final TextView status=text("",14,MUTED), details=text("",14,MUTED);
        final TextView mediaInfo=text("",14,MUTED);
        final LinearLayout frames=new LinearLayout(MainActivity.this);
        final Button watch=button("Просмотр",()->watch()), download=button("Скачать",()->download()), seasonDownload=button("Скачать сезон",()->seasonDownload());
        final View seasonDivider=new View(MainActivity.this);
        JSONObject work, source, release;
        ArrayList<JSONObject> seasonWorks=new ArrayList<>();
        String workId=preferredEpisode, releaseId=preferredRelease, signature="";
        int season=-1, polls=0; boolean polling;
        ArrayAdapter<String> sourceAdapter;
        MovieCard(JSONObject data) { this.data=data; }
        boolean alive() { return generation==version && currentMovie==data; }
        void show() throws Exception {
            section(data.getString("title"));
            ImageView poster=new ImageView(MainActivity.this); poster.setVisibility(View.GONE);
            LinearLayout.LayoutParams posterSize=new LinearLayout.LayoutParams(dp(118),dp(174)); posterSize.setMargins(0,dp(24),0,dp(24)); posterSize.gravity=Gravity.START;
            body.addView(poster,posterSize); loadPoster(poster,data.optString("id")); savedPoster(poster,data);
            body.addView(text(caption(data)+"  "+data.optString("director"),15,MUTED));
            TextView description=text(cleanDescription(data.optString("description","")),16,TEXT); description.setTag("card-description"); body.addView(description);
            HorizontalScrollView screenshots=new HorizontalScrollView(MainActivity.this); screenshots.setTag("card-screenshots"); screenshots.setHorizontalScrollBarEnabled(false); screenshots.addView(frames); body.addView(screenshots);
            selectors.setOrientation(LinearLayout.VERTICAL); sources.setOrientation(LinearLayout.VERTICAL);
            body.addView(selectors); body.addView(sources);
            body.addView(details); body.addView(mediaInfo);
            cardAction(watch); cardAction(download); status.setTag("download-status"); status.setGravity(Gravity.CENTER); body.addView(status,new LinearLayout.LayoutParams(-1,-2));
            watch.setTag("card-watch"); download.setTag("card-download"); seasonDownload.setTag("season-download");
            seasonDivider.setBackgroundColor(0xffd7e2db); seasonDivider.setTag("season-divider");
            LinearLayout.LayoutParams separator=new LinearLayout.LayoutParams(-1,dp(1)); separator.setMargins(0,dp(8),0,dp(4)); body.addView(seasonDivider,separator); cardAction(seasonDownload);
            render(); signature=String.valueOf(data.optJSONArray("works"))+data.optJSONObject("complete_seasons"); tick.run();
        }
        boolean isSeries() { JSONArray rs=data.optJSONArray("releases"); if(rs!=null) for(int i=0;i<rs.length();i++) if(rs.optJSONObject(i).optBoolean("series")) return true; return false; }
        Spinner selector(LinearLayout parent,String label,ArrayList<String> labels,int selected,java.util.function.IntConsumer choose) {
            LinearLayout row=new LinearLayout(MainActivity.this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(text(label+":",17,TEXT)); Spinner spinner=new Spinner(MainActivity.this); spinner.setTag(label.equals("Серия")?"episode-selector":label.equals("Сезон")?"file-season-selector":"source-selector");
            spinner.setAdapter(new ArrayAdapter<>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,labels));
            row.addView(spinner,new LinearLayout.LayoutParams(0,-2,1)); parent.addView(row); spinner.setSelection(Math.max(0,selected));
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onNothingSelected(AdapterView<?> p) { }
                public void onItemSelected(AdapterView<?> p,View v,int n,long id) { if(alive() && row.getParent()==parent) { if(v instanceof TextView) ((TextView)v).setTextColor(TEXT); choose.accept(n); } }
            }); return spinner;
        }
        void render() {
            selectors.removeAllViews(); sources.removeAllViews();
            JSONArray works=data.optJSONArray("works");
            if(works==null || works.length()==0) { source=null; work=null; release=null; status.setText("Индексируем источники: получаем списки файлов от трекеров и пиров…"); update(); return; }
            ArrayList<JSONObject> all=new ArrayList<>(); TreeSet<Integer> seasons=new TreeSet<>();
            for(int i=0;i<works.length();i++) { JSONObject w=works.optJSONObject(i); all.add(w); seasons.add(w.isNull("season")?0:w.optInt("season")); }
            String last=WatchState.lastEpisode(MainActivity.this,data.optString("id"));
            if(!last.isEmpty()) for(JSONObject w:all) try { w.put("last_watched",last.equals(w.optString("id"))); } catch(Exception ignored) { }
            if(season<0) {
                JSONObject resume=data.optJSONObject("resume");
                for(JSONObject w:all) { if(w.optBoolean("last_watched")) { workId=w.optString("id"); season=w.optInt("season",0); }
                    JSONArray ss=w.optJSONArray("sources"); if(resume!=null && ss!=null) for(int j=0;j<ss.length();j++) { JSONObject s=ss.optJSONObject(j); if(s.optString("torrent").equals(resume.optString("torrent")) && s.optInt("index")==resume.optInt("file")) { if(workId.isEmpty()) workId=w.optString("id"); } }
                }
                for(JSONObject w:all) if(w.optString("id").equals(workId)) season=w.optInt("season",0);
                if(season<0) season=seasons.first();
            }
            if(isSeries()) {
                ArrayList<Integer> numbers=new ArrayList<>(seasons); ArrayList<String> labels=new ArrayList<>(); for(int n:numbers) labels.add(n==0?"Без установленного сезона":""+n);
                selector(selectors,"Сезон",labels,numbers.indexOf(season),n->{ if(season!=numbers.get(n)) { season=numbers.get(n); workId=""; render(); } });
            }
            seasonWorks.clear(); for(JSONObject w:all) if(!isSeries() || w.optInt("season",0)==season) seasonWorks.add(w);
            if(seasonWorks.isEmpty()) { season=seasons.first(); render(); return; }
            int selected=0; ArrayList<String> labels=new ArrayList<>();
            for(int i=0;i<seasonWorks.size();i++) { JSONObject w=seasonWorks.get(i); if(w.optString("id").equals(workId)) selected=i;
                labels.add((w.isNull("number")?w.optString("name"):""+w.optInt("number"))+(w.optBoolean("last_watched")?" · последняя просмотренная":"")); }
            work=seasonWorks.get(selected); workId=work.optString("id"); preferredEpisode=workId;
            if(isSeries() || seasonWorks.size()>1) selector(selectors,isSeries()?"Серия":"Файл",labels,selected,n->{ String id=seasonWorks.get(n).optString("id"); if(!id.equals(workId)) { workId=id; render(); } });
            renderSources();
        }
        JSONObject release(String id) { JSONArray rs=data.optJSONArray("releases"); if(rs!=null) for(int i=0;i<rs.length();i++) if(id.equals(rs.optJSONObject(i).optString("id"))) return rs.optJSONObject(i); return null; }
        String label(JSONObject s) {
            JSONObject r=release(s.optString("release")); if(r==null) return "Источник недоступен";
            JSONObject local=local(s); String arrow=local!=null && DownloadService.queued.contains(local.optString("id"))?" ↓":"";
            return "●  "+resolution(r)+" · "+r.optString("size")+arrow;
        }
        void renderSources() {
            sources.removeAllViews(); JSONArray available=work.optJSONArray("sources");
            if(available==null || available.length()==0) { source=null; release=null; chosenFile=null; chosenTorrent=""; sourceAdapter=null; sourceSelection++; details.setText(""); mediaInfo.setText(""); frames.removeAllViews(); update(); return; }
            ArrayList<JSONObject> list=new ArrayList<>();
            for(int i=0;i<available.length();i++) list.add(available.optJSONObject(i));
            // Sort by the release size shown in the selector, not an episode's file size.
            list.sort((a,b)->Double.compare(sourceSize(b),sourceSize(a)));
            ArrayList<String> labels=new ArrayList<>(); int selected=0;
            for(int i=0;i<list.size();i++) { JSONObject s=list.get(i); labels.add(label(s)); JSONObject r=release(s.optString("release"));
                if(releaseId.isEmpty() && r!=null && r.optString("resolution").equals(prefs.getString("quality","1080p"))) selected=i;
                if(s.optString("release").equals(releaseId)) selected=i;
            }
            select(list.get(selected));
            Spinner spinner=selector(sources,"Источник",labels,selected,n->{ JSONObject s=list.get(n); if(source!=s) select(s); });
            sourceAdapter=new ArrayAdapter<String>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,labels) {
                private View tint(View row,int n) { JSONObject r=release(list.get(n).optString("release")); int seeds=r==null?0:r.optInt("seeds"); android.text.SpannableString value=new android.text.SpannableString(label(list.get(n))); value.setSpan(new android.text.style.ForegroundColorSpan(seeds>=10?0xff218739:seeds>=5?0xffe78016:seeds>0?0xffcf3030:Color.BLACK),0,1,0); ((TextView)row).setText(value); return row; }
                @Override public View getView(int n,View v,ViewGroup p) { View row=tint(super.getView(n,v,p),n); ((TextView)row).setTextColor(TEXT); return row; }
                @Override public View getDropDownView(int n,View v,ViewGroup p) { return tint(super.getDropDownView(n,v,p),n); }
            }; spinner.setAdapter(sourceAdapter); spinner.setSelection(selected);
        }
        double sourceSize(JSONObject s) {
            JSONObject r=release(s.optString("release"));
            return r==null?-1:displaySizeBytes(r.optString("size"));
        }
        void select(JSONObject s) {
            source=s; releaseId=s.optString("release"); release=release(releaseId); selectedRelease=releaseId; preferredRelease=releaseId;
            chosenTorrent=s.optString("torrent"); selectedTorrent=chosenTorrent; selectedFile=s.optInt("index"); selectedEpisode=workId; selectedSeries=isSeries();
            try { chosenFile=new JSONObject(s.toString()).put("position",WatchState.position(MainActivity.this,data.optString("id"),workId,chosenTorrent,selectedFile,isSeries()?work.optLong("position"):data.optLong("position"))); } catch(Exception ignored) { }
            sourceSelection++; watch.setTag("card-watch"); download.setTag("card-download");
            details.setText(release==null?"":release.optString("release_title")+(release.optBoolean("swarm_stale")?"\nНе удалось обновить число раздающих. Показаны сохранённые данные.":"")); update();
            mediaInfo.setGravity(Gravity.CENTER);
            mediaInfo.setText(release==null?"":release.optString("audio").isEmpty()?"Дорожки и субтитры доступны в меню плеера":release.optString("audio"));
            frames.removeAllViews();
            if(release!=null && !releaseId.startsWith("saved:")) for(int i=0;i<3;i++) {
                ImageView frame=new ImageView(MainActivity.this); frame.setVisibility(View.GONE); frame.setContentDescription("Скриншот источника "+(i+1));
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(260),dp(146)); p.setMargins(0,dp(12),dp(10),dp(12)); frames.addView(frame,p);
                String path="/v1/releases/"+Uri.encode(releaseId)+"/screenshots/"+i; loadImage(frame,path); frame.setOnClickListener(v->showScreenshot(path));
            }
        }
        JSONObject local(JSONObject s) {
            if(s==null) return null; String path="/v1/torrents/"+s.optString("torrent")+"/files/"+s.optInt("index");
            for(JSONObject item:matchingSaved(data)) if(path.equals(item.optString("path")) || item.optString("id").equals(s.optString("local_id","-"))) return item;
            return null;
        }
        boolean completeSeason() {
            JSONObject complete=data.optJSONObject("complete_seasons"); JSONArray seasons=complete==null?null:complete.optJSONArray(releaseId);
            if(seasons!=null) for(int i=0;i<seasons.length();i++) if(seasons.optInt(i)==season) return true;
            return false;
        }
        void update() {
            JSONObject local=local(source);
            if(local!=null && Boolean.TRUE.equals(watch.getTag())) { sourceSelection++; watch.setTag("card-watch"); }
            if(!Boolean.TRUE.equals(watch.getTag())) {
                watch.setText(local!=null?"Просмотр":chosenFile!=null && chosenFile.optLong("position")>0?"Продолжить просмотр":"Просмотр");
                watch.setEnabled(source!=null && (local==null || localReady(local)));
            }
            if(!Boolean.TRUE.equals(download.getTag())) {
                boolean active=local!=null && DownloadService.queued.contains(local.optString("id"));
                download.setText(active?"Остановить скачивание":"Скачать"); download.setEnabled(source!=null && (local==null || !local.optString("state").equals("complete")));
            }
            if(source!=null) status.setText(local==null?(data.optBoolean("indexing")?"Индексируем остальные источники…":""):stateLabel(local)+(localReady(local)?"":" · просмотр после "+prefs.getInt("download_percent",10)+" %"));
            seasonDownload.setEnabled(source!=null && completeSeason());
            seasonDownload.setVisibility(isSeries()?View.VISIBLE:View.GONE); seasonDivider.setVisibility(isSeries()?View.VISIBLE:View.GONE);
            if(sourceAdapter!=null) sourceAdapter.notifyDataSetChanged();
            if(source==null && !data.optBoolean("indexing",true)) status.setText("Для выбранного произведения пока нет проиндексированных источников.");
            status.setVisibility(status.getText().length()==0?View.GONE:View.VISIBLE);
        }
        void watch() {
            if(source==null) return; JSONObject local=local(source); if(local!=null) { playSaved(local); return; }
            int selection=sourceSelection; JSONObject file=chosenFile, r=release;
            watch.setTag(Boolean.TRUE); watch.setEnabled(false);
            task(()->request("POST","/v1/torrents",new JSONObject().put("release",r.getString("id"))),state->{ if(alive() && selection==sourceSelection) buffer(state.getString("id"),file,watch,System.currentTimeMillis()); });
        }
        void download() {
            if(source==null) return; JSONObject local=local(source);
            if(local!=null) {
                if(DownloadService.queued.contains(local.optString("id"))) DownloadService.paused.add(local.optString("id"));
                else { DownloadService.paused.remove(local.optString("id")); startForegroundService(new Intent(MainActivity.this,DownloadService.class).putExtra("item",local.toString())); }
                update(); return;
            }
            saveFile(source.optString("torrent"),chosenFile);
        }
        void seasonDownload() {
            if(!completeSeason() || release==null) return;
            ArrayList<JSONObject> files=new ArrayList<>();
            for(JSONObject w:seasonWorks) { JSONArray ss=w.optJSONArray("sources"); if(ss!=null) for(int i=0;i<ss.length();i++) { JSONObject s=ss.optJSONObject(i); if(releaseId.equals(s.optString("release"))) { files.add(s); break; } } }
            saveBatch(source.optString("torrent"),files,release);
        }
        final Runnable tick=new Runnable() { public void run() {
            if(!alive()) return; update();
            if(!polling && (polls<60 || data.optBoolean("indexing"))) {
                polls++; polling=true; io.submit(()->{ try {
                    JSONObject result=request("GET","/v1/movies/"+data.optString("id")+"/sources",null);
                    ui.post(()->{ polling=false; if(!alive()) return; try {
                        String changed=result.optJSONArray("works").toString()+result.optJSONObject("complete_seasons");
                        for(var keys=result.keys();keys.hasNext();) { String key=keys.next(); data.put(key,result.get(key)); }
                        addSavedSources(data);
                        if(!signature.equals(changed) && !Boolean.TRUE.equals(watch.getTag()) && !Boolean.TRUE.equals(download.getTag())) { signature=changed; render(); }
                    } catch(Exception e) { Diagnostics.event("catalog",e.getClass().getSimpleName()); } });
                } catch(Exception e) { ui.post(()->polling=false); } });
            }
            ui.postDelayed(this,polls<60?2000:5000);
        }};
    }
    private String resolution(JSONObject release) { String value=release.optString("resolution"); return value.isEmpty() || value.toLowerCase(Locale.ROOT).contains("не указано")?"N\\A":value; }
    static double displaySizeBytes(String value) {
        String normalized=value.toUpperCase(Locale.ROOT).replace('\u00a0',' ').replace('\u202f',' ').trim();
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("^([0-9]+(?:[.,][0-9]+)?)\\s*([A-ZА-Я]*)$").matcher(normalized);
        if(!match.matches()) return -1;
        double amount=Double.parseDouble(match.group(1).replace(',','.'));
        String unit=match.group(2);
        int power=switch(unit) {
            case "", "B", "Б" -> 0;
            case "KB", "KIB", "КБ", "КИБ" -> 1;
            case "MB", "MIB", "МБ", "МИБ" -> 2;
            case "GB", "GIB", "ГБ", "ГИБ" -> 3;
            case "TB", "TIB", "ТБ", "ТИБ" -> 4;
            default -> -1;
        };
        return power<0?-1:amount*Math.pow(1024,power);
    }
    static String cleanDescription(String value) {
        for(int n=0;n<3 && value.matches("(?s).*(?:<[^>]+>|&(?:lt|gt|amp|quot|#\\d+);).*" );n++) value=android.text.Html.fromHtml(value,android.text.Html.FROM_HTML_MODE_LEGACY).toString();
        if(value.toLowerCase(Locale.ROOT).contains("uploader:")) return "";
        return value.trim();
    }
    private String caption(JSONObject item) { return item.optString("year")+(item.optBoolean("animated") && item.optBoolean("series")?" · Мульт.":""); }
    private void legacyShowMovie(JSONObject data) throws Exception {
        generation++; chosenFile=null; chosenTorrent=""; selectedEpisode="";
        currentMovie=data; body.removeAllViews(); section(data.getString("title"));
        ImageView poster=new ImageView(this); poster.setImageResource(R.drawable.camera); body.addView(poster,new LinearLayout.LayoutParams(-1,dp(156))); loadPoster(poster,data.optString("id"));
        savedPoster(poster,data);
        body.addView(text(data.optString("year")+"  "+data.optString("director"),15,MUTED));
        body.addView(text(data.optString("description").isEmpty()?"Описание пока не предоставлено источником":data.optString("description"),16,TEXT));
        JSONArray allReleases=data.getJSONArray("releases"); TreeSet<String> seasons=new TreeSet<>((a,b)->{ try { return Integer.compare(Integer.parseInt(a),Integer.parseInt(b)); } catch(Exception e) { return a.compareTo(b); } });
        for(int i=0;i<allReleases.length();i++) seasons.addAll(releaseSeasons(allReleases.optJSONObject(i)));
        String chosen=data.optString("chosen_season",seasons.isEmpty()?"":seasons.first());
        if(!data.has("chosen_season")) {
            for(JSONObject item:matchingSaved(data)) { if(!item.optString("state").equals("complete") || item.optString("release").equals(preferredRelease))
                for(int n=0;n<allReleases.length();n++) if(item.optString("release").equals(allReleases.optJSONObject(n).optString("id"))) { var candidates=releaseSeasons(allReleases.optJSONObject(n)); if(!candidates.isEmpty()) chosen=item.has("season") && !item.isNull("season")?""+item.optInt("season"):candidates.iterator().next(); } }
            JSONObject resume=data.optJSONObject("resume");
            if(resume!=null) for(int n=0;n<allReleases.length();n++) if(resume.optString("release").equals(allReleases.optJSONObject(n).optString("id"))) { var candidates=releaseSeasons(allReleases.optJSONObject(n)); if(!candidates.isEmpty()) chosen=candidates.iterator().next(); }
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
            if(seasons.size()>1) body.addView(button("Скачать все сезоны",()->saveAllSeasons(data)));
        }
        JSONArray releases=new JSONArray();
        for(int i=0;i<allReleases.length();i++) { JSONObject r=allReleases.optJSONObject(i); if(chosen.isEmpty() || chosen.equals("Все сезоны") || releaseSeasons(r).contains(chosen)) releases.put(r); }
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
                sourceLoading=true;
                int selection=++sourceSelection;
                episodePanel.removeAllViews(); episodePanel.addView(text("Получаем список файлов раздачи…",14,MUTED));
                loadSource(r,"",episodePanel,audio,selection,System.currentTimeMillis());
                health.setText(r.optString("release_title")+(r.optBoolean("swarm_stale")?"\nНе удалось обновить число раздающих. Показаны сохранённые данные.":""));
                audio.setText(r.optString("audio").isEmpty()?"Дорожки и субтитры доступны в меню плеера":r.optString("audio"));
                frames.removeAllViews();
                for(int i=0;i<3;i++) {
                    ImageView frame=new ImageView(MainActivity.this); frame.setContentDescription("Скриншот раздачи "+(i+1)); frame.setVisibility(View.GONE);
                    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(260),dp(146)); p.setMargins(0,dp(12),dp(10),dp(12)); frames.addView(frame,p);
                    String path="/v1/releases/"+Uri.encode(r.optString("id"))+"/screenshots/"+i;
                    loadImage(frame,path); frame.setOnClickListener(tapped->showScreenshot(path));
                }
            }
        }); spinner.setSelection(selected);
        final int initialSelection=selected;
        Button watch=button(data.optLong("position")>0?"Продолжить просмотр":"Смотреть",()->{});
        View.OnClickListener streamWatch=v->{
            if(sourceLoading) return;
            ArrayList<JSONObject> local=selectedSaved(data);
            if(!local.isEmpty()) { playSavedChoices(local); return; }
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
            if(sourceLoading) return;
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
                             boolean ready=result!=null && result.optBoolean("ready");
                            watch.setVisibility(ready?View.VISIBLE:View.GONE); watch.setEnabled(ready);
                            watch.setText(data.optLong("position")>0?"Продолжить просмотр":"Смотреть");
                            if(!ready) download.setText(stateLabel(local)+" · буфер: "+(result.isNull("buffer_eta")?"ожидание пиров":result.optInt("buffer_eta")+" с"));
                        });
                    });
                }
            }
            if(sourceLoading && chosenFile==null && existing==null) {
                watch.setEnabled(false); watch.setText("Ожидаем список файлов…");
                download.setEnabled(false); download.setText("Получаем раздачу…");
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
                        if(!status.isNull("error") && !status.optString("error").isEmpty()) { sourceLoading=false; panel.removeAllViews(); panel.addView(text(status.optString("error"),14,MUTED)); return; }
                        panel.removeAllViews(); panel.addView(text("Ожидаем метаданные от пиров • пиров: "+status.optInt("peers")+" • "+(System.currentTimeMillis()-started)/1000+" с",14,MUTED));
                        if(System.currentTimeMillis()-started<120000) ui.postDelayed(()->{ if(version==generation && selection==sourceSelection) loadSource(release,key,panel,audio,selection,started); },2000);
                        else { sourceLoading=false; panel.addView(text("Пиры пока не передали список файлов. Попробуйте другую раздачу или повторите позже.",14,MUTED)); }
                        return;
                    }
                    sourceLoading=false;
                    chosenTorrent=key;
                    ArrayList<JSONObject> ordered=new ArrayList<>(); for(int i=0;i<files.length();i++) ordered.add(files.optJSONObject(i));
                    ordered.sort(Comparator.comparingInt((JSONObject f)->f.optInt("season",0)).thenComparingInt(f->f.optInt("number",Integer.MAX_VALUE)).thenComparing(f->f.optString("name")));
                     int selected=0; JSONObject resume=currentMovie.optJSONObject("resume");
                     for(int i=0;i<ordered.size();i++) {
                         JSONObject file=ordered.get(i);
                        if(resume!=null && key.equals(resume.optString("torrent")) && file.optInt("index")==resume.optInt("file")) selected=i;
                        if(!preferredEpisode.isEmpty() && preferredEpisode.equals(file.optString("episode"))) selected=i;
                    }
                     java.util.function.Consumer<JSONObject> choose=file->{
                         chosenFile=file; selectedEpisode=file.optString("episode");
                         try { file.put("position",WatchState.position(this,currentMovie.optString("id"),selectedEpisode,key,file.optInt("index"),file.optLong("position"))); } catch(Exception ignored) { }
                        try { if(file.has("episode")) currentMovie.put("position",file.optLong("position")); } catch(Exception ignored) { }
                        showMediaInfo(key,file,audio,selection,0);
                    };
                     panel.removeAllViews();
                     if(files.length()>1 || release.optBoolean("series")) {
                         TreeMap<Integer,ArrayList<JSONObject>> groups=new TreeMap<>();
                         for(JSONObject file:ordered) groups.computeIfAbsent(file.optInt("season",0),n->new ArrayList<>()).add(file);
                         ArrayList<Integer> keys=new ArrayList<>(groups.keySet());
                         ArrayList<String> seasonNames=new ArrayList<>(); for(int n:keys) seasonNames.add(n==0?"Другие файлы":"Сезон "+n);
                         panel.addView(text("Сезон",18,TEXT)); Spinner seasons=new Spinner(MainActivity.this); seasons.setTag("file-season-selector");
                         seasons.setAdapter(new ArrayAdapter<>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,seasonNames)); panel.addView(seasons);
                         LinearLayout episodeBox=new LinearLayout(MainActivity.this); episodeBox.setOrientation(LinearLayout.VERTICAL); panel.addView(episodeBox);
                         JSONObject initial=ordered.get(selected); int seasonIndex=keys.indexOf(initial.optInt("season",0));
                         String requested=currentMovie.optString("chosen_season");
                         try { int explicit=keys.indexOf(Integer.parseInt(requested)); if(explicit>=0) seasonIndex=explicit; } catch(Exception ignored) { }
                         seasons.setSelection(Math.max(0,seasonIndex));
                         seasons.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                             public void onNothingSelected(AdapterView<?> p) { }
                             public void onItemSelected(AdapterView<?> p,View v,int n,long id) {
                                 if(v instanceof TextView) ((TextView)v).setTextColor(TEXT);
                                 ArrayList<JSONObject> items=groups.get(keys.get(n)); episodeBox.removeAllViews();
                                 ArrayList<String> labels=new ArrayList<>(); int index=0;
                                 for(int j=0;j<items.size();j++) { JSONObject f=items.get(j); labels.add(episodeLabel(f)+(f.optBoolean("completed")?" · просмотрено":f.optLong("position")>0?" · продолжить":"")); if(f==initial) index=j; }
                                 episodeBox.addView(text("Серия",18,TEXT)); Spinner episodes=new Spinner(MainActivity.this); episodes.setTag("episode-selector");
                                 episodes.setAdapter(new ArrayAdapter<>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,labels)); episodes.setSelection(index); episodeBox.addView(episodes);
                                 episodes.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                                     public void onNothingSelected(AdapterView<?> parent) { }
                                     public void onItemSelected(AdapterView<?> parent,View view,int i,long value) { if(view instanceof TextView) ((TextView)view).setTextColor(TEXT); choose.accept(items.get(i)); }
                                 });
                                 choose.accept(items.get(index));
                                 episodeBox.addView(button("Скачать сезон",()->saveBatch(key,items,release)));
                             }
                         });
                         if(groups.size()>1) panel.addView(button("Скачать все сезоны раздачи",()->saveBatch(key,ordered,release)));
                     } else choose.accept(ordered.get(selected));
                });
            } catch(Exception e) {
                Diagnostics.event("source-error",e.getClass().getSimpleName());
                ui.post(()->{ if(version==generation && selection==sourceSelection) { sourceLoading=false; panel.removeAllViews(); panel.addView(text(e.getMessage()==null?"Раздача сейчас недоступна":e.getMessage(),14,MUTED)); } });
            }
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
        selectedTorrent=""; selectedFile=0; selectedEpisode=item.optString("episode");
        String[] parts=item.optString("path").split("/");
        if(parts.length==6) try { selectedTorrent=parts[3]; selectedFile=Integer.parseInt(parts[5]); } catch(Exception ignored) { }
        long position=item.optLong("position");
        if(chosenFile!=null && selectedEpisode.equals(chosenFile.optString("episode"))) position=chosenFile.optLong("position",position);
        position=WatchState.position(this,item.optString("movie"),selectedEpisode,selectedTorrent,selectedFile,position);
        if(!item.optString("state").equals("complete")) {
            try {
                if(!localReady(item)) { notice("Для просмотра нужно скачать "+prefs.getInt("download_percent",10)+" % файла"); return; }
                playingMovie=item.optString("movie"); playingEpisode=selectedEpisode; playingTorrent=selectedTorrent; playingFile=selectedFile; playingUri=item.getString("uri");
                if(prefs.getBoolean("external_player",false)) {
                    PlaybackBridge local=new PlaybackBridge(this,item);
                    launchExternal(Uri.parse(PlaybackService.open(this,local)),playingMovie,position);
                } else startActivityForResult(new Intent(this,PlayerActivity.class).setData(Uri.parse(playingUri))
                    .putExtra("local_item",item.toString()).putExtra("saved_uri",playingUri).putExtra("movie",playingMovie)
                    .putExtra("position",position).putExtra("episode",selectedEpisode).putExtra("torrent",selectedTorrent).putExtra("file",selectedFile),200);
            } catch(Exception e) { error(e); } return;
        }
        play(Uri.parse(item.optString("uri")),item.optString("movie"),position);
    }
    private boolean localReady(JSONObject item) { return item.optString("state").equals("complete") || (item.optLong("size")>0 && item.optLong("downloaded")*100/item.optLong("size")>=prefs.getInt("download_percent",10)); }
    private void savedActions(JSONObject data) {
        Button open=button("Открыть сохранённое",()->{
            playSavedChoices(selectedSaved(data));
        }); body.addView(open);
        int version=generation;
        Runnable refresh=new Runnable() { public void run() { if(version!=generation || currentMovie!=data) return; open.setEnabled(!selectedSaved(data).isEmpty()); ui.postDelayed(this,2000); } }; refresh.run();
    }
    private static java.util.Set<String> releaseSeasons(JSONObject release) {
        TreeSet<String> values=new TreeSet<>(Comparator.comparingInt(s->{ try { return Integer.parseInt(s); } catch(Exception e) { return Integer.MAX_VALUE; } }));
        String value=release.optString("season");
        if(value.matches("\\d{1,3}-\\d{1,3}")) { String[] ends=value.split("-"); int a=Integer.parseInt(ends[0]), b=Integer.parseInt(ends[1]); if(b-a<=100) for(int n=a;n<=b;n++) values.add(""+n); }
        else if(!value.isEmpty()) values.add(value); return values;
    }
    private String episodeLabel(JSONObject item) {
        if(!item.isNull("number") && item.has("number")) return "Сезон "+item.optInt("season")+" · Серия "+item.optInt("number");
        var match=java.util.regex.Pattern.compile("s(\\d+)e(\\d+)").matcher(item.optString("episode"));
        if(match.matches()) return "Сезон "+Integer.parseInt(match.group(1))+" · Серия "+Integer.parseInt(match.group(2));
        return item.optString("name","Серия / файл");
    }
    private ArrayList<JSONObject> selectedSaved(JSONObject data) {
        ArrayList<JSONObject> result=matchingSaved(data);
        if(chosenFile!=null) result.removeIf(item->item.optBoolean("series") && !(item.optString("path").equals("/v1/torrents/"+chosenTorrent+"/files/"+chosenFile.optInt("index")) || (!chosenFile.optString("episode").isEmpty() && chosenFile.optString("episode").equals(item.optString("episode")))));
        return result;
    }
    private void playSavedChoices(ArrayList<JSONObject> items) {
        if(items.isEmpty()) return;
        if(items.size()==1) { playSaved(items.get(0)); return; }
        String[] labels=new String[items.size()]; for(int i=0;i<labels.length;i++) labels[i]=items.get(i).optString("name")+" · "+stateLabel(items.get(i));
        new AlertDialog.Builder(this).setTitle("Выберите качество").setItems(labels,(d,n)->playSaved(items.get(n))).show();
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
                    try { DownloadService.delete(this,item); library(); } catch(Exception e) { error(e); }
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
            if(!status.isNull("error") && !status.optString("error").isEmpty()) throw new java.io.IOException(status.optString("error"));
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
        if(System.currentTimeMillis()-started<500) button.setText("Подготовка видео • проверяем индекс и буфер…");
        int selection=sourceSelection; JSONObject choice=chosenFile;
        int seconds=prefs.getInt("buffer",15);
        long position=file.has("episode")?file.optLong("position"):currentMovie.optLong("position");
        task(()->request("GET","/v1/torrents/"+torrent+"?file="+file.getInt("index")+"&buffer="+seconds+"&position="+position,null),status->{
            if(selection!=sourceSelection || choice!=chosenFile) return;
            for(JSONObject saved:matchingSaved(currentMovie)) if(saved.optString("path").equals("/v1/torrents/"+torrent+"/files/"+file.optInt("index"))) { sourceSelection++; button.setTag("card-watch"); button.setText("Просмотр"); button.setEnabled(localReady(saved)); return; }
            Diagnostics.event("buffer","ready="+status.optBoolean("ready")+" percent="+status.optInt("buffer_percent")+" missing="+status.optLong("buffer_missing_bytes")+" position="+position);
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
            button.setText((status.isNull("media")?"Получаем метаданные и индекс…":"Буферизация")+" • "+status.optInt("buffer_percent")+"%"+(status.isNull("buffer_eta")?" • ожидаем данные":" • примерно "+status.optInt("buffer_eta")+" с"));
            int version=generation; ui.postDelayed(()->{ if(version==generation) {
                if(selection==sourceSelection && choice==chosenFile) buffer(torrent,file,button,started);
            } },1500);
        });
    }
    private void play(Uri uri,String movie,long position) {
        playingMovie=movie;
        playingUri=uri.toString();
        playingEpisode=selectedEpisode; playingTorrent=selectedTorrent; playingFile=selectedFile;
        if(!prefs.getBoolean("external_player",false)) {
            Intent embedded=new Intent(this,PlayerActivity.class).setData(uri).putExtra("movie",movie).putExtra("position",position).putExtra("torrent",selectedTorrent).putExtra("file",selectedFile).putExtra("episode",selectedEpisode);
            if(!"local.mapp.media".equals(uri.getAuthority())) embedded.putExtra("saved_uri",uri.toString());
            startActivityForResult(embedded,200); return;
        }
        if("local.mapp.media".equals(uri.getAuthority())) {
            int version=generation;
            Toast.makeText(this,"Буферизация на телефоне…",Toast.LENGTH_LONG).show();
            io.submit(()->{ PlaybackBridge stream=null; try {
                JSONObject media=MediaProvider.describe(this,uri); stream=new PlaybackBridge(this,media.getString("path"),media.getLong("size"));
                JSONObject info=request("GET",media.getString("path")+"/info",null);
                stream.prime(position,info.optLong("duration_ms"),prefs.getInt("buffer",15));
                PlaybackBridge ready=stream;
                ui.post(()->{ if(version!=generation || isFinishing()) { ready.close(); return; } launchExternal(Uri.parse(PlaybackService.open(this,ready)),movie,position); });
            } catch(Exception e) { if(stream!=null) stream.close(); ui.post(()->{ if(version==generation) error(e); }); } });
        } else launchExternal(uri,movie,position);
    }
    private void launchExternal(Uri uri,String movie,long position) {
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("MApp video",uri));
        intent.putExtra("position",(int)position); intent.putExtra("from_start",position==0); intent.putExtra("return_result",true);
        intent.putExtra("extra_position",position);
        String torrent=selectedTorrent, episode=selectedEpisode; int file=selectedFile;
        java.util.function.Consumer<android.content.pm.ResolveInfo> launch=handler->{ try {
            intent.setComponent(new ComponentName(handler.activityInfo.packageName,handler.activityInfo.name));
            String name=DecoderPolicy.name(this,Uri.parse(playingUri));
            if(handler.activityInfo.packageName.equals("org.videolan.vlc")) {
                intent.putExtra("title",name);
                if(DecoderPolicy.software(this,name)) intent.putExtra("disable_hardware",true);
                else intent.removeExtra("disable_hardware");
            }
            Diagnostics.event("external-player","launch "+handler.activityInfo.packageName+" position="+position);
            startActivityForResult(intent,200); if(!movie.isEmpty()) io.submit(()->{ try {
            JSONObject update=new JSONObject().put("position",position); if(!torrent.isEmpty()) update.put("torrent",torrent).put("file",file);
            if(!episode.isEmpty()) update.put("episode",episode);
            request("PUT","/v1/history/"+movie,update);
        } catch(Exception ignored) { } });
        } catch(ActivityNotFoundException e) { stopService(new Intent(this,PlaybackService.class)); notice("Внешний плеер недоступен"); } };
        var handlers=getPackageManager().queryIntentActivities(intent,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        String remembered=prefs.getString("external_component","");
        for(var handler:handlers) if(new ComponentName(handler.activityInfo.packageName,handler.activityInfo.name).flattenToString().equals(remembered)) { launch.accept(handler); return; }
        if(handlers.isEmpty()) { stopService(new Intent(this,PlaybackService.class)); notice("Установите внешний видеоплеер, например VLC, mpv-android или MX Player."); }
        else if(handlers.size()==1) launch.accept(handlers.get(0));
        else { String[] labels=new String[handlers.size()]; for(int i=0;i<labels.length;i++) labels[i]=handlers.get(i).loadLabel(getPackageManager()).toString();
            CheckBox always=new CheckBox(this); always.setText("Всегда открывать в этом плеере"); int[] choice={0};
            new AlertDialog.Builder(this).setTitle("Открыть в плеере").setSingleChoiceItems(labels,0,(d,n)->choice[0]=n).setView(always)
                .setPositiveButton("Открыть",(d,n)->{ var handler=handlers.get(choice[0]); if(always.isChecked()) prefs.edit().putString("external_component",new ComponentName(handler.activityInfo.packageName,handler.activityInfo.name).flattenToString()).apply(); launch.accept(handler); })
                .setNegativeButton("Отмена",(d,n)->stopService(new Intent(this,PlaybackService.class))).setOnCancelListener(d->stopService(new Intent(this,PlaybackService.class))).show(); }
    }
    private void saveFile(String torrent,JSONObject file) {
        try {
            String mediaPath="/v1/torrents/"+torrent+"/files/"+file.getInt("index");
            JSONArray saved=Library.all(this); String server=new Api(this).server;
            for(int i=0;i<saved.length();i++) if(saved.optJSONObject(i).optString("path").equals(mediaPath) && saved.optJSONObject(i).optString("server").equals(server)) {
                JSONObject existing=saved.optJSONObject(i);
                if(!existing.optString("state").equals("complete") && !DownloadService.queued.contains(existing.optString("id"))) { DownloadService.paused.remove(existing.optString("id")); startForegroundService(new Intent(this,DownloadService.class).putExtra("item",existing.toString())); }
                if(!batchDownload && !screen.equals("movie")) library(); return;
            }
            for(JSONObject item:pendingBatch) if(item.optString("path").equals(mediaPath) && item.optString("server").equals(server)) return;
            String filename=file.getString("name"); filename=filename.substring(filename.lastIndexOf('/')+1);
            JSONObject cardCopy=new JSONObject(currentMovie.toString()); cardCopy.put("server",server).put("poster_thumbnail",ImageCache.thumbnail(this,server,currentMovie.optString("id")));
            pendingDownload=new JSONObject().put("id",UUID.randomUUID().toString()).put("movie",currentMovie.getString("id"))
                .put("title",currentMovie.getString("title")).put("year",currentMovie.optString("year")).put("director",currentMovie.optString("director"))
                .put("path",mediaPath).put("release",selectedRelease).put("series",selectedSeries).put("animated",currentMovie.optBoolean("animated")).put("size",file.getLong("size"))
                .put("episode",file.optString("episode")).put("season",file.opt("season")).put("number",file.opt("number")).put("position",file.optLong("position"))
                .put("name",filename).put("server",new Api(this).server).put("state","queued").put("card",cardCopy);
            if(batchDownload) { pendingBatch.add(pendingDownload); pendingDownload=null; return; }
            if(prefs.getBoolean("ask_path",false) || prefs.getString("folder","").isEmpty()) {
                pickFolder(103);
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
    private void pickFolder(int request) {
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        String folder=prefs.getString("folder","");
        if(!folder.isEmpty()) picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(folder));
        else for(var volume:getSystemService(android.os.storage.StorageManager.class).getStorageVolumes()) if(volume.isRemovable() && volume.getState().equals(Environment.MEDIA_MOUNTED)) { picker=volume.createOpenDocumentTreeIntent(); break; }
        startActivityForResult(picker,request);
    }
    private void saveBatch(String torrent,ArrayList<JSONObject> files,JSONObject release) {
        if(!pendingBatch.isEmpty()) return;
        selectedRelease=release.optString("id"); selectedSeries=true; batchDownload=true;
        try { for(JSONObject file:files) saveFile(torrent,file); } finally { batchDownload=false; }
        if(pendingBatch.isEmpty()) { Toast.makeText(this,"Эти серии уже добавлены",Toast.LENGTH_SHORT).show(); return; }
        if(prefs.getBoolean("ask_path",false) || prefs.getString("folder","").isEmpty()) pickFolder(104);
        else startBatch(Uri.parse(prefs.getString("folder","")));
    }
    private void saveAllSeasons(JSONObject card) {
        JSONArray releases=card.optJSONArray("releases"); if(releases==null) return;
        LinkedHashMap<String,JSONObject> choices=new LinkedHashMap<>(); String quality=prefs.getString("quality","1080p");
        for(int i=0;i<releases.length();i++) { JSONObject r=releases.optJSONObject(i);
            for(String season:releaseSeasons(r)) { JSONObject prior=choices.get(season);
                if(prior==null || (!prior.optString("resolution").equals(quality) && r.optString("resolution").equals(quality)) || (prior.optString("resolution").equals(r.optString("resolution")) && r.optInt("seeds")>prior.optInt("seeds"))) choices.put(season,r);
            }
        }
        LinkedHashMap<String,JSONObject> unique=new LinkedHashMap<>(); for(JSONObject r:choices.values()) unique.put(r.optString("id"),r);
        if(unique.isEmpty()) return;
        boolean[] cancelled={false}; int version=generation;
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Подготовка всех сезонов").setMessage("Получаем списки серий. Качество: "+quality+" (при наличии).")
            .setNegativeButton("Отмена",(d,n)->cancelled[0]=true).setOnCancelListener(d->cancelled[0]=true).show();
        io.submit(()->{ ArrayList<JSONObject> ready=new ArrayList<>(); try {
            for(JSONObject release:unique.values()) {
                if(cancelled[0] || version!=generation) return;
                String key=request("POST","/v1/torrents",new JSONObject().put("release",release.optString("id"))).getString("id"); long until=System.currentTimeMillis()+120000;
                while(true) {
                    if(cancelled[0] || version!=generation) return;
                    JSONObject state=request("GET","/v1/torrents/"+key,null); JSONArray files=state.getJSONArray("files");
                    if(!state.isNull("error") && !state.optString("error").isEmpty()) throw new java.io.IOException(state.optString("error"));
                    if(files.length()>0) { for(int i=0;i<files.length();i++) { JSONObject file=files.getJSONObject(i); String season=file.isNull("season")?"":""+file.optInt("season");
                        if(season.isEmpty() || choices.get(season)==release) ready.add(new JSONObject().put("torrent",key).put("file",file).put("release",release)); } break; }
                    if(System.currentTimeMillis()>until) throw new java.io.IOException("Не удалось получить список серий. Выберите другую раздачу.");
                    Thread.sleep(1500);
                }
            }
            ui.post(()->{ dialog.dismiss(); if(cancelled[0] || version!=generation) return; batchDownload=true;
                try { for(JSONObject item:ready) { selectedRelease=item.optJSONObject("release").optString("id"); selectedSeries=true; saveFile(item.optString("torrent"),item.optJSONObject("file")); } }
                finally { batchDownload=false; }
                if(pendingBatch.isEmpty()) { Toast.makeText(this,"Все серии уже добавлены",Toast.LENGTH_SHORT).show(); return; }
                if(prefs.getBoolean("ask_path",false) || prefs.getString("folder","").isEmpty()) pickFolder(104); else startBatch(Uri.parse(prefs.getString("folder","")));
            });
        } catch(Exception e) { ui.post(()->{ dialog.dismiss(); if(!cancelled[0] && version==generation) error(e); }); }
        finally { if(cancelled[0] || version!=generation) ui.post(dialog::dismiss); } });
    }
    private void startBatch(Uri tree) {
        batchDownload=true;
        try { while(!pendingBatch.isEmpty()) { pendingDownload=pendingBatch.get(0); pendingDownload.put("folder",tree.toString()); startDownload(FolderLibrary.create(this,tree,pendingDownload.getString("name"))); pendingBatch.remove(0); } }
        catch(Exception e) { pendingBatch.clear(); pendingDownload=null; error(e); }
        finally { batchDownload=false; if(!screen.equals("movie")) library(); }
    }
    private void startDownload(Uri uri) throws Exception {
        if(uri==null || pendingDownload==null) throw new java.io.IOException("Не удалось создать файл");
        try(var cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) { if(cursor!=null && cursor.moveToFirst()) pendingDownload.put("name",cursor.getString(0)); }
        pendingDownload.put("uri",uri.toString()).put("queued_at",System.currentTimeMillis()); Library.put(this,pendingDownload);
        FolderLibrary.backup(this,pendingDownload);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},300);
        startForegroundService(new Intent(this,DownloadService.class).putExtra("item",pendingDownload.toString())); pendingDownload=null; if(!batchDownload && !screen.equals("movie")) library();
    }
    private String stateLabel(JSONObject item) {
        return switch(item.optString("state")) {
            case "complete" -> "Скачано";
            case "paused" -> "Пауза";
            default -> "Загрузка · "+(item.optLong("downloaded")*100/Math.max(1,item.optLong("size")))+" %";
        };
    }
    private void library() {
        page("library","Скачанное");
        ArrayList<JSONObject> items=new ArrayList<>(); JSONArray all=Library.all(this); for(int i=0;i<all.length();i++) items.add(all.optJSONObject(i));
        for(JSONObject item:items) {
            String state=item.optString("state");
            if((state.equals("downloading") || (state.equals("queued") && System.currentTimeMillis()-item.optLong("queued_at")>10000)) && !DownloadService.queued.contains(item.optString("id"))) {
                try { item.put("state","paused").put("error","Загрузка была прервана. Нажмите, чтобы продолжить."); Library.put(this,item); } catch(Exception ignored) { }
            }
        }
        items.sort(Comparator.comparing((JSONObject j)->j.optString("state").equals("complete")).thenComparing(j->j.optString("title"),String.CASE_INSENSITIVE_ORDER));
        if(items.isEmpty()) body.addView(text("Пока пусто. Скачайте фильм из его карточки. После переустановки выберите прежнюю папку в настройках.",16,MUTED));
        for(JSONObject item:items) {
            LinearLayout row=(LinearLayout)movieRow(item,()->playSaved(latestSaved(item)));
            LinearLayout actions=new LinearLayout(this); actions.setOrientation(LinearLayout.VERTICAL); actions.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageButton delete=new ImageButton(this); delete.setImageResource(R.drawable.trash); delete.setContentDescription("Удалить файл"); delete.setBackground(rounded(BUTTON)); delete.setPadding(dp(12),dp(12),dp(12),dp(12));
            delete.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Удалить файл?").setMessage(item.optString("title")+"\n"+episodeLabel(item))
                .setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,n)->{
                    task(()->{ DownloadService.delete(this,item); return new JSONObject(); },r->library());
                }).show());
            actions.addView(delete,new LinearLayout.LayoutParams(dp(48),dp(48)));
            ImageButton transfer=new ImageButton(this); transfer.setTag("download-control:"+item.optString("id")); transfer.setImageTintList(android.content.res.ColorStateList.valueOf(contrast(BUTTON))); transfer.setBackground(rounded(BUTTON)); transfer.setPadding(dp(12),dp(12),dp(12),dp(12));
            transfer.setOnClickListener(v->{ JSONObject latest=latestSaved(item); String id=latest.optString("id");
                if(DownloadService.queued.contains(id)) DownloadService.paused.add(id);
                else { DownloadService.paused.remove(id); startForegroundService(new Intent(this,DownloadService.class).putExtra("item",latest.toString())); }
                updateTransferButton(transfer,latest);
            });
            LinearLayout.LayoutParams transferParams=new LinearLayout.LayoutParams(dp(48),dp(48)); transferParams.topMargin=dp(32); actions.addView(transfer,transferParams); updateTransferButton(transfer,item);
            LinearLayout.LayoutParams fixed=new LinearLayout.LayoutParams(dp(48),-2); fixed.leftMargin=dp(16); row.addView(actions,fixed); body.addView(row);
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
        if(view instanceof ImageButton && ("download-control:"+item.optString("id")).equals(view.getTag())) updateTransferButton((ImageButton)view,item);
        if(view instanceof TextView && item.optString("id").equals(view.getTag())) ((TextView)view).setText(stateLabel(item));
        if(view instanceof android.view.ViewGroup group) for(int i=0;i<group.getChildCount();i++) updateDownloadLabels(group.getChildAt(i),item);
    }
    private JSONObject latestSaved(JSONObject fallback) { JSONArray items=Library.all(this); for(int n=0;n<items.length();n++) if(items.optJSONObject(n).optString("id").equals(fallback.optString("id"))) return items.optJSONObject(n); return fallback; }
    private void updateTransferButton(ImageButton button,JSONObject item) {
        boolean complete=item.optString("state").equals("complete"), active=DownloadService.queued.contains(item.optString("id")), stopping=active && DownloadService.paused.contains(item.optString("id"));
        button.setVisibility(complete?View.GONE:View.VISIBLE); button.setEnabled(!stopping);
        button.setImageResource(active?R.drawable.player_pause:R.drawable.player_play_arrow);
        button.setContentDescription(stopping?"Остановка загрузки…":active?"Приостановить загрузку":"Продолжить загрузку");
        button.setAlpha(stopping?.5f:1f);
    }
    private void deleteSavedGroup(JSONObject item) {
        ArrayList<JSONObject> files=new ArrayList<>(); JSONArray all=Library.all(this);
        for(int i=0;i<all.length();i++) { JSONObject file=all.optJSONObject(i);
            if(file.optString("server").equals(item.optString("server")) && file.optString("movie",file.optString("title")).equals(item.optString("movie",item.optString("title")))) files.add(file);
        }
        new AlertDialog.Builder(this).setTitle("Удалить сохранённое?").setMessage(item.optString("title")+"\nФайлов: "+files.size())
            .setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,n)->{
                task(()->{ for(JSONObject file:files) {
                    DownloadService.delete(this,file);
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
        body.addView(button("Сбросить выбор внешнего плеера",()->{ prefs.edit().remove("external_component").apply(); Toast.makeText(this,"Плеер можно выбрать при следующем просмотре",Toast.LENGTH_SHORT).show(); }));
        body.addView(text("Декодирование видео (встроенный плеер и VLC)",16,TEXT));
        Spinner decoder=new Spinner(this); String[] decoderKeys={"auto","software","system"};
        decoder.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Авто: Xvid/DivX программно","Программное (VLC)","Системное (MediaCodec)"}));
        decoder.setSelection(Math.max(0,Arrays.asList(decoderKeys).indexOf(prefs.getString("decoder","auto")))); body.addView(decoder);
        decoder.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent,View view,int n,long id) { prefs.edit().putString("decoder",decoderKeys[n]).apply(); }
        });
        Switch audioPrompt=new Switch(this); audioPrompt.setText("Спрашивать аудиодорожку при новом просмотре"); audioPrompt.setTextColor(TEXT); audioPrompt.setChecked(prefs.getBoolean("ask_audio",false)); body.addView(audioPrompt);
        audioPrompt.setOnCheckedChangeListener((b,value)->prefs.edit().putBoolean("ask_audio",value).apply());
        body.addView(text("Выбор перед просмотром поддерживается встроенным плеером. Во внешнем — через его меню дорожек.",14,MUTED));
        String[] quality={"480p","720p","1080p","2160p"}; Spinner q=new Spinner(this); q.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,quality));
        q.setSelection(Math.max(0,Arrays.asList(quality).indexOf(prefs.getString("quality","1080p"))));
        body.addView(text("Предпочитаемое разрешение",16,TEXT)); body.addView(q);
        q.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { public void onNothingSelected(AdapterView<?> p) { } public void onItemSelected(AdapterView<?> p,View v,int n,long id) { if(v instanceof TextView) ((TextView)v).setTextColor(TEXT); prefs.edit().putString("quality",quality[n]).apply(); } });
        TextView buffer=text("Буферизация: "+prefs.getInt("buffer",15)+" с",16,TEXT); body.addView(buffer);
        TextView percent=text("Скачать % для просмотра: "+prefs.getInt("download_percent",10)+" %",16,TEXT); body.addView(percent);
        SeekBar threshold=new SeekBar(this); threshold.setMin(1); threshold.setMax(100); threshold.setProgress(prefs.getInt("download_percent",10)); body.addView(threshold);
        threshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onStartTrackingTouch(SeekBar s) { } public void onStopTrackingTouch(SeekBar s) { } public void onProgressChanged(SeekBar s,int n,boolean user) { prefs.edit().putInt("download_percent",n).apply(); percent.setText("Скачать % для просмотра: "+n+" %"); } });
        SeekBar seek=new SeekBar(this); seek.setMin(5); seek.setMax(120); seek.setProgress(prefs.getInt("buffer",15)); body.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onStartTrackingTouch(SeekBar s) { } public void onStopTrackingTouch(SeekBar s) { } public void onProgressChanged(SeekBar s,int n,boolean user) { prefs.edit().putInt("buffer",n).apply(); buffer.setText("Буферизация: "+n+" с"); } });
        body.addView(text("Встроенный плеер: AVI/MKV/MP4, перемотка, аудиодорожки, субтитры и сохранение позиции. Внешний плеер можно выбрать выше.",14,MUTED));
         divider(); section("Загрузки"); Switch ask=new Switch(this); ask.setTextColor(TEXT); ask.setText("Спрашивать место сохранения"); ask.setChecked(prefs.getBoolean("ask_path",false)); body.addView(ask);
        ask.setOnCheckedChangeListener((b,value)->prefs.edit().putBoolean("ask_path",value).apply());
        body.addView(text(prefs.getString("folder","").isEmpty()?"Выберите папку перед скачиванием":"Выбрана папка на устройстве",15,MUTED));
        body.addView(button("Выбрать папку",()->pickFolder(101)));
        body.addView(text("Скачанные файлы остаются после удаления MApp. Выберите прежнюю папку — библиотека восстановится автоматически только из неё.",14,MUTED));
        Switch diagnostics=new Switch(this); diagnostics.setText("Расширенное логирование"); diagnostics.setTextColor(TEXT); diagnostics.setChecked(prefs.getBoolean("diagnostics",false)); body.addView(diagnostics);
        body.addView(text("MApp-debug.txt в папке сохранения видео. Доступен по USB. При выключении логи удаляются.",14,MUTED));
        diagnostics.setOnCheckedChangeListener((b,value)->{ diagnostics.setEnabled(false); Diagnostics.enable(this,value,message->ui.post(()->{ diagnostics.setEnabled(true); notice(message); })); });
        try { body.addView(text("MApp "+getPackageManager().getPackageInfo(getPackageName(),0).versionName,13,MUTED)); } catch(Exception ignored) { }
        divider(); section("Каталог сервера"); TextView sync=text("Получаем состояние синхронизации…",14,MUTED); body.addView(sync);
        syncProgress(sync,generation);
        body.post(()->{
            if(!screen.equals("settings")) return;
            int width=Math.min(getResources().getDisplayMetrics().widthPixels-dp(44),(int)(getResources().getDisplayMetrics().widthPixels/1.618));
            for(int i=0;i<body.getChildCount();i++) if(body.getChildAt(i) instanceof Button && !(body.getChildAt(i) instanceof CompoundButton)) {
                View control=body.getChildAt(i); LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(width,-2); params.gravity=Gravity.CENTER_HORIZONTAL; control.setLayoutParams(params);
            }
        });
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
                Object value=data.getExtras()==null?null:data.getExtras().get(data.hasExtra("extra_position")?"extra_position":"position");
                long position=value instanceof Number?((Number)value).longValue():-1;
                Diagnostics.event("external-result","position="+position+" result="+result);
                if(position>=0 && !playingUri.isEmpty()) Library.position(this,playingUri,position);
                if(position>=0) WatchState.save(this,playingMovie,playingEpisode,playingTorrent,playingFile,position);
                if(position>=0 && currentMovie!=null && currentMovie.optString("id").equals(playingMovie)) currentMovie.put("position",position);
                if(position>=0 && chosenFile!=null && chosenTorrent.equals(playingTorrent) && chosenFile.optInt("index")==playingFile) chosenFile.put("position",position);
                if(position>=0 && prefs.getBoolean("external_player",false)) { String movie=playingMovie, episode=playingEpisode, torrent=playingTorrent; int file=playingFile; io.submit(()->{ try {
                    JSONObject update=new JSONObject().put("position",position);
                    if(!torrent.isEmpty()) update.put("torrent",torrent).put("file",file);
                    if(!episode.isEmpty()) update.put("episode",episode);
                    request("PUT","/v1/history/"+movie,update);
                } catch(Exception ignored) { } }); }
            }
            if(request==200) { stopService(new Intent(this,PlaybackService.class)); if(data==null) Diagnostics.event("player-result","no position returned"); }
            if(result!=RESULT_OK || data==null) { if(request==104) pendingBatch.clear(); return; }
            if(request==100) { persist(data.getData(),data.getFlags()); startDownload(data.getData()); }
            if(request==101 || request==103 || request==104) {
                Uri tree=data.getData(); persist(tree,data.getFlags());
                if(request==101 || prefs.getString("folder","").isEmpty()) prefs.edit().putString("folder",tree.toString()).putBoolean("folder_prompted",true).apply();
                page("restore","Сохранённое"); body.addView(text("Проверяем выбранную папку…",16,MUTED));
                task(()->new JSONObject().put("restored",FolderLibrary.restore(this,tree)),restored->{
                    if(request==104) startBatch(tree);
                    else if(request==103 && pendingDownload!=null) { pendingDownload.put("folder",tree.toString()); startDownload(FolderLibrary.create(this,tree,pendingDownload.getString("name"))); }
                    else { settings(); Toast.makeText(this,"Добавлено файлов: "+restored.optInt("restored"),Toast.LENGTH_LONG).show(); }
                });
            }
        } catch(Exception e) { error(e); }
    }
    private void persist(Uri uri,int flags) { getContentResolver().takePersistableUriPermission(uri,flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); }
    @Override public void onBackPressed() {
        if(!screen.equals("home")) { back(); return; }
        new AlertDialog.Builder(this).setTitle("Выйти из MApp?").setPositiveButton("Выйти",(d,n)->finish()).setNegativeButton("Остаться",null).show();
    }
    private void back() {
        if(screen.equals("movie") && searchReturnRoot!=null) {
            generation++; root=searchReturnRoot; body=searchReturnBody; screen="search"; setContentView(root);
            reloadImages(root); return;
        }
        home();
    }
    private void reloadImages(View view) {
        if(view instanceof ImageView image && image.getTag() instanceof String path && path.startsWith("/v1/")) loadImage(image,path);
        if(view instanceof android.view.ViewGroup group) for(int i=0;i<group.getChildCount();i++) reloadImages(group.getChildAt(i));
    }
    @Override protected void onDestroy() { if(screenshotDialog!=null) screenshotDialog.dismiss(); generation++; ui.removeCallbacksAndMessages(null); io.shutdown(); images.shutdownNow(); super.onDestroy(); }
}
