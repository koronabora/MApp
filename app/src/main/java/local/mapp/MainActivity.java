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
    private static final int BG=0xfff5f8f6, CARD=0xffffffff, TEXT=0xff182b25, MUTED=0xff596d65, ACCENT=0xff176b58;
    private final ExecutorService io=Executors.newFixedThreadPool(4);
    private final Handler ui=new Handler(Looper.getMainLooper());
    private LinearLayout root, body;
    private String screen="home", playingMovie="";
    private String selectedTorrent="";
    private String selectedRelease="";
    private boolean selectedSeries=false;
    private int selectedFile=0;
    private int generation=0;
    private JSONObject currentMovie, pendingDownload;
    private SharedPreferences prefs;
    interface Job { JSONObject run() throws Exception; }
    interface Result { void accept(JSONObject data) throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); prefs=getSharedPreferences("settings",0);
        if(state!=null) playingMovie=state.getString("playingMovie","");
        handle(getIntent());
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("playingMovie",playingMovie); }
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
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{MUTED,ACCENT}));
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},rounded(0xffe9eeeb));
        states.addState(new int[]{},rounded(0xffdeeee5));
        var ripple=new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x25176b58),states,rounded(Color.WHITE));
        b.setBackgroundTintList(null);
        b.setBackground(new android.graphics.drawable.InsetDrawable(ripple,0,dp(4),0,dp(4)));
        b.setMinHeight(dp(56)); b.setMinimumHeight(dp(56)); b.setPadding(dp(16),dp(8),dp(16),dp(8));
        b.setOnClickListener(v->click.run()); return b;
    }
    private void page(String name,String title) {
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
    private void error(Exception e) { if(!isFinishing()) notice(e.getMessage()==null?"Не удалось выполнить запрос":e.getMessage()); }
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
            session.put("key",result.getString("key")).put("user_id",result.getString("user_id")); Vault.save(this,session); return result;
        },result->home());
    }
    private void home() {
        page("home","MApp"); body.addView(text("Ваше кино. Ваш сервер.",14,MUTED));
        try { if(Vault.load(this).optString("key").isEmpty()) { welcome(); return; } } catch(Exception e) { welcome(); return; }
        section("История"); LinearLayout history=new LinearLayout(this); body.addView(history);
        history.addView(text("Загружаем историю…",15,MUTED)); divider();
        section("Поиск"); body.addView(button("Название фильма или сериала",()->searchPage("")));
        LinearLayout recent=new LinearLayout(this); recent.setOrientation(LinearLayout.VERTICAL); body.addView(recent); divider();
        LinearLayout savedHeader=new LinearLayout(this); savedHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView savedTitle=text("Сохранённое",21,TEXT); savedTitle.setTypeface(null,Typeface.BOLD);
        savedHeader.addView(savedTitle,new LinearLayout.LayoutParams(0,-2,1)); savedHeader.addView(button("Подробнее →",this::library)); body.addView(savedHeader); strip(body,Library.all(this),true);
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
        EditText link=new EditText(this); link.setHint("mapp://pair?…"); link.setSingleLine(true); body.addView(link);
        body.addView(button("Подключиться по ссылке",()->{ Uri u=Uri.parse(link.getText().toString().trim()); if("mapp".equals(u.getScheme()) && "pair".equals(u.getHost())) pair(u); else notice("Нужна ссылка mapp://pair"); }));
        body.addView(button("Открыть сохранённые фильмы",this::library));
    }
    private void strip(LinearLayout target,JSONArray items,boolean saved) {
        if(items.length()==0) { target.addView(text(saved?"Фильмы для офлайн-просмотра появятся здесь":"Здесь будут ваши просмотры",15,MUTED)); return; }
        HorizontalScrollView scroll=new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row=new LinearLayout(this); scroll.addView(row);
        for(int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i); LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12),dp(10),dp(12),dp(10)); card.setBackground(rounded(CARD));
            ImageView poster=new ImageView(this); poster.setImageResource(R.drawable.camera); card.addView(poster,new LinearLayout.LayoutParams(-1,dp(88))); loadPoster(poster,item.optString(saved?"movie":"id"));
            TextView title=text(item.optString("title"),15,TEXT); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); card.addView(title);
            card.addView(text(saved?stateLabel(item):item.optString("year"),12,MUTED));
            card.setOnClickListener(v->{ if(saved) savedCard(item); else movie(item.optString("id")); });
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(120),-2); p.setMargins(0,0,dp(12),0); row.addView(card,p);
        }
        target.addView(scroll);
    }
    private void searchPage(String initial) {
        page("search","Поиск"); EditText input=new EditText(this); input.setSingleLine(true); input.setHint("Фильм или сериал"); input.setImeOptions(EditorInfo.IME_ACTION_SEARCH); body.addView(input);
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
                for(int i=0;i<movies.length();i++) { JSONObject item=movies.getJSONObject(i); results.addView(movieRow(item,()->movie(item.optString("id")))); }
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
        LinearLayout info=new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(dp(14),0,0,0);
        TextView title=text(item.optString("title"),17,TEXT); title.setMaxLines(2); info.addView(title);
        info.addView(text(item.optString("year")+"  "+item.optString("director"),13,MUTED));
        if(item.has("state")) { TextView progress=text(stateLabel(item),13,ACCENT); progress.setTag(item.optString("id")); info.addView(progress); }
        row.addView(info,new LinearLayout.LayoutParams(0,-2,1)); row.setOnClickListener(v->click.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(6),0,dp(6)); row.setLayoutParams(p); return row;
    }
    private void movie(String id) {
        page("movie","Карточка фильма"); body.addView(text("Обновляем раздачи и доступность источников…",16,MUTED));
        task(()->request("GET","/v1/movies/"+id,null),this::showMovie);
    }
    private void loadPoster(ImageView image,String mid) {
        if(mid.isEmpty()) return;
        loadImage(image,"/v1/movies/"+mid+"/poster");
    }
    private void loadImage(ImageView image,String path) {
        int version=generation;
        io.submit(()->{
            try {
                var connection=new Api(this).open(path);
                try {
                    if(connection.getResponseCode()!=200) return;
                    byte[] bytes; try(var input=connection.getInputStream()) { bytes=Api.read(input,4*1024*1024); }
                    android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options(); options.inJustDecodeBounds=true;
                    android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                    options.inSampleSize=Math.max(1,Math.max(options.outWidth,options.outHeight)/600); options.inJustDecodeBounds=false;
                    var bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                    ui.post(()->{ if(version==generation && bitmap!=null) { image.setImageBitmap(bitmap); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setVisibility(View.VISIBLE); } });
                } finally { connection.disconnect(); }
            } catch(Exception ignored) { /* Source may not provide a poster; keep the local icon. */ }
        });
    }
    private void showMovie(JSONObject data) throws Exception {
        currentMovie=data; body.removeAllViews(); section(data.getString("title"));
        body.addView(text(data.optString("year")+"  "+data.optString("director"),15,MUTED));
        body.addView(text(data.optString("description").isEmpty()?"Описание пока не предоставлено источником":Html.fromHtml(data.optString("description"),Html.FROM_HTML_MODE_LEGACY).toString(),16,TEXT));
        divider(); section("Качество и источник"); JSONArray releases=data.getJSONArray("releases");
        String[] labels=new String[releases.length()]; int selected=0;
        for(int i=0;i<releases.length();i++) { JSONObject r=releases.getJSONObject(i); labels[i]=r.getString("resolution")+" · "+r.getString("size")+" · "+r.getString("provider")+" · сиды "+r.getInt("seeds"); if(r.getString("resolution").equals(prefs.getString("quality","1080p"))) selected=i; }
        Spinner spinner=new Spinner(this); spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels)); body.addView(spinner);
        TextView health=text("",14,ACCENT), audio=text("",14,MUTED); body.addView(health); body.addView(audio);
        HorizontalScrollView screenshots=new HorizontalScrollView(this); screenshots.setHorizontalScrollBarEnabled(false);
        LinearLayout frames=new LinearLayout(this); screenshots.addView(frames); body.addView(screenshots);
        Button[] downloadButton={null};
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent,View v,int position,long id) {
                JSONObject r=releases.optJSONObject(position); int seeds=r.optInt("seeds");
                health.setText("●  "+seeds+" сидов · "+r.optInt("peers")+" пиров\n"+r.optString("release_title"));
                health.setTextColor(seeds>=10?ACCENT:seeds>0?0xff875a16:0xffb33732);
                audio.setText(r.optString("audio").isEmpty()?"Дорожки и субтитры доступны в меню внешнего плеера":r.optString("audio"));
                if(downloadButton[0]!=null) {
                    Button b=downloadButton[0]; b.setText("Скачать на телефон"); b.setEnabled(true);
                    b.setOnClickListener(view->prepare(r,true,b));
                    JSONArray saved=Library.all(MainActivity.this);
                    for(int i=0;i<saved.length();i++) {
                        JSONObject item=saved.optJSONObject(i);
                        if(item.optString("release").equals(r.optString("id")) && !item.optBoolean("series")) {
                            b.setText(stateLabel(item)+" → библиотека"); b.setOnClickListener(view->library()); break;
                        }
                    }
                }
                frames.removeAllViews();
                for(int i=0;i<3;i++) {
                    ImageView frame=new ImageView(MainActivity.this); frame.setContentDescription("Скриншот раздачи "+(i+1)); frame.setVisibility(View.GONE);
                    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(260),dp(146)); p.setMargins(0,dp(12),dp(10),dp(12)); frames.addView(frame,p);
                    loadImage(frame,"/v1/releases/"+Uri.encode(r.optString("id"))+"/screenshots/"+i);
                }
            }
        }); spinner.setSelection(selected);
        Button watch=button(data.optLong("position")>0?"Продолжить просмотр":"Смотреть",()->{});
        watch.setOnClickListener(v->{
            JSONObject resume=data.optJSONObject("resume");
            if(resume!=null && data.optLong("position")>0) {
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
            } else prepare(releases.optJSONObject(spinner.getSelectedItemPosition()),false,watch);
        }); body.addView(watch);
        Button download=button("Скачать на телефон",()->{});
        downloadButton[0]=download;
        download.setOnClickListener(v->prepare(releases.optJSONObject(spinner.getSelectedItemPosition()),true,download)); body.addView(download);
        JSONArray saved=Library.all(this);
        for(int i=0;i<saved.length();i++) if(saved.optJSONObject(i).optString("movie").equals(data.optString("id"))) {
            body.addView(button("Открыть сохранённое",this::library)); break;
        }
    }
    private void prepare(JSONObject release,boolean download,Button button) {
        if(release==null) return;
        selectedRelease=release.optString("id");
        button.setEnabled(false); button.setText("Получаем раздачу…");
        task(()->request("POST","/v1/torrents",new JSONObject().put("release",release.getString("id"))),data->chooseFile(data.getString("id"),download,button,System.currentTimeMillis()));
    }
    private void chooseFile(String torrent,boolean download,Button button,long started) {
        task(()->request("GET","/v1/torrents/"+torrent,null),status->{
            JSONArray files=status.getJSONArray("files");
            if(files.length()==0) {
                if(System.currentTimeMillis()-started>120000) { button.setEnabled(true); button.setText("Повторить"); notice("Метаданные пока недоступны. Возможно, нет доступных пиров."); return; }
                button.setText("Ожидаем метаданные…"); int version=generation;
                ui.postDelayed(()->{ if(version==generation) chooseFile(torrent,download,button,started); },1500); return;
            }
            java.util.function.Consumer<JSONObject> select=file->{
                if(download) saveFile(torrent,file); else buffer(torrent,file,button,System.currentTimeMillis());
            };
            selectedSeries=files.length()>1;
            if(files.length()==1) select.accept(files.getJSONObject(0));
            else chooseSeason(files,select,button);
        });
    }
    private void chooseSeason(JSONArray files,java.util.function.Consumer<JSONObject> select,Button button) {
        TreeMap<Integer,ArrayList<JSONObject>> seasons=new TreeMap<>();
        java.util.regex.Pattern pattern=java.util.regex.Pattern.compile("(?i)(?:s|season[ ._-]*|сезон[ ._-]*)(\\d{1,3})");
        for(int i=0;i<files.length();i++) {
            JSONObject file=files.optJSONObject(i); var match=pattern.matcher(file.optString("name"));
            int season=match.find()?Integer.parseInt(match.group(1)):0;
            seasons.computeIfAbsent(season,k->new ArrayList<>()).add(file);
        }
        java.util.function.Consumer<ArrayList<JSONObject>> episodes=items->{
            items.sort(Comparator.comparing(f->f.optString("name"),String.CASE_INSENSITIVE_ORDER));
            String[] names=new String[items.size()]; for(int i=0;i<names.length;i++) names[i]=items.get(i).optString("name");
            new AlertDialog.Builder(this).setTitle("Серия / файл").setItems(names,(d,n)->select.accept(items.get(n)))
                .setNegativeButton("Отмена",(d,n)->{ button.setEnabled(true); button.setText("Повторить"); }).show();
        };
        if(seasons.size()==1) episodes.accept(seasons.firstEntry().getValue());
        else {
            ArrayList<Integer> keys=new ArrayList<>(seasons.keySet()); String[] names=new String[keys.size()];
            for(int i=0;i<names.length;i++) names[i]=keys.get(i)==0?"Другие файлы":"Сезон "+keys.get(i);
            new AlertDialog.Builder(this).setTitle("Выберите сезон").setItems(names,(d,n)->episodes.accept(seasons.get(keys.get(n))))
                .setNegativeButton("Отмена",(d,n)->{ button.setEnabled(true); button.setText("Повторить"); }).show();
        }
    }
    private void buffer(String torrent,JSONObject file,Button button,long started) {
        int seconds=prefs.getInt("buffer",15);
        task(()->request("GET","/v1/torrents/"+torrent+"?file="+file.getInt("index")+"&buffer="+seconds,null),status->{
            if(!status.isNull("error") && !status.optString("error").isEmpty()) {
                button.setEnabled(true); button.setText("Повторить"); notice(status.getString("error")); return;
            }
            if(status.optBoolean("ready")) {
                button.setEnabled(true); button.setText("Продолжить просмотр");
                selectedTorrent=torrent; selectedFile=file.getInt("index");
                Uri uri=MediaProvider.create(this,"/v1/torrents/"+torrent+"/files/"+file.getInt("index"),file.getLong("size"),file.getString("name"));
                play(uri,currentMovie.optString("id"),currentMovie.optLong("position")); return;
            }
            if(System.currentTimeMillis()-started>300000) { button.setEnabled(true); button.setText("Повторить буферизацию"); notice("Буфер пока не готов. Попробуйте раздачу с большим числом сидов."); return; }
            button.setText(status.isNull("buffer_eta")?"Буферизация • ожидаем пиров…":"Буферизация • примерно "+status.optInt("buffer_eta")+" с");
            int version=generation; ui.postDelayed(()->{ if(version==generation) buffer(torrent,file,button,started); },1500);
        });
    }
    private void play(Uri uri,String movie,long position) {
        playingMovie=movie;
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("MApp video",uri));
        intent.putExtra("position",(int)position); intent.putExtra("from_start",position==0); intent.putExtra("return_result",true);
        intent.putExtra("extra_position",position);
        String torrent=selectedTorrent; int file=selectedFile;
        try { startActivityForResult(Intent.createChooser(intent,"Открыть в плеере"),200); if(!movie.isEmpty()) io.submit(()->{ try {
            JSONObject update=new JSONObject().put("position",position); if(!torrent.isEmpty()) update.put("torrent",torrent).put("file",file);
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
            pendingDownload=new JSONObject().put("id",UUID.randomUUID().toString()).put("movie",currentMovie.getString("id"))
                .put("title",currentMovie.getString("title")).put("year",currentMovie.optString("year")).put("director",currentMovie.optString("director"))
                .put("path",mediaPath).put("release",selectedRelease).put("series",selectedSeries).put("size",file.getLong("size"))
                .put("name",filename).put("server",new Api(this).server).put("state","queued");
            if(prefs.getBoolean("ask_path",true)) {
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,filename),100);
            } else {
                String folder=prefs.getString("folder",""); Uri uri;
                if(!folder.isEmpty()) {
                    Uri tree=Uri.parse(folder); uri=DocumentsContract.createDocument(getContentResolver(),DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree)),"video/*",filename);
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
        pendingDownload.put("uri",uri.toString()).put("queued_at",System.currentTimeMillis()); Library.put(this,pendingDownload);
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
        page("library","Сохранённое"); body.addView(button("Добавить ранее скачанные файлы",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),102)));
        ArrayList<JSONObject> items=new ArrayList<>(); JSONArray all=Library.all(this); for(int i=0;i<all.length();i++) items.add(all.optJSONObject(i));
        for(JSONObject item:items) {
            String state=item.optString("state");
            if((state.equals("downloading") || (state.equals("queued") && System.currentTimeMillis()-item.optLong("queued_at")>10000)) && !DownloadService.queued.contains(item.optString("id"))) {
                try { item.put("state","paused").put("error","Загрузка была прервана. Нажмите, чтобы продолжить."); Library.put(this,item); } catch(Exception ignored) { }
            }
        }
        items.sort(Comparator.comparing((JSONObject j)->j.optString("state").equals("complete")).thenComparing(j->j.optString("title"),String.CASE_INSENSITIVE_ORDER));
        if(items.isEmpty()) body.addView(text("Пока пусто. Скачайте фильм из его карточки или добавьте файл с телефона.",16,MUTED));
        for(JSONObject item:items) {
            LinearLayout row=(LinearLayout)movieRow(item,()->savedCard(item));
            row.addView(button("Удалить",()->new AlertDialog.Builder(this).setTitle("Удалить фильм?").setMessage(item.optString("title"))
                .setPositiveButton("Удалить",(d,n)->{
                    try {
                        if(DownloadService.queued.contains(item.optString("id"))) { DownloadService.cancelled.add(item.optString("id")); notice("Загрузка отменяется; файл будет удалён."); return; }
                        Uri uri=Uri.parse(item.getString("uri"));
                        if(DocumentsContract.isDocumentUri(this,uri)) DocumentsContract.deleteDocument(getContentResolver(),uri); else getContentResolver().delete(uri,null,null);
                        Library.remove(this,item.getString("id")); library();
                    } catch(Exception e) { error(e); }
                }).setNegativeButton("Отмена",null).show())); body.addView(row);
        }
        body.addView(button("Обновить статусы",this::library));
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
    private void savedCard(JSONObject item) {
        page("saved",item.optString("title")); section(item.optString("title")); body.addView(text(item.optString("year")+"  "+item.optString("director"),16,MUTED));
        body.addView(text(stateLabel(item),18,ACCENT));
        if(item.optString("state").equals("complete")) body.addView(button("Смотреть",()->{ selectedTorrent=""; play(Uri.parse(item.optString("uri")),item.optString("movie"),0); }));
        else if(item.optString("state").equals("paused")) {
            body.addView(text(item.optString("error"),15,MUTED));
            body.addView(button("Продолжить загрузку",()->{ startForegroundService(new Intent(this,DownloadService.class).putExtra("item",item.toString())); library(); }));
        } else {
            long target=Math.min(item.optLong("size"),Math.max(4,prefs.getInt("buffer",15))*1024L*1024L);
            long missing=Math.max(0,target-item.optLong("downloaded")), rate=item.optLong("download_rate");
            body.addView(text(missing==0?"Буфер готов":rate==0?"Ожидаем данные для буферизации":"До просмотра примерно "+(missing/rate)+" с",15,MUTED));
            if(missing==0) body.addView(button("Смотреть с сервера",()->{
                try {
                    generation++;
                    String[] parts=item.getString("path").split("/");
                    currentMovie=new JSONObject().put("id",item.optString("movie")).put("position",0);
                    JSONObject file=new JSONObject().put("index",Integer.parseInt(parts[5])).put("size",item.getLong("size")).put("name",item.getString("name"));
                    Button watch=button("Буферизация…",()->{}); body.addView(watch); watch.setEnabled(false);
                    buffer(parts[3],file,watch,System.currentTimeMillis());
                } catch(Exception e) { error(e); }
            }));
            int version=generation;
            ui.postDelayed(()->{
                if(version!=generation) return;
                JSONArray latest=Library.all(this);
                for(int i=0;i<latest.length();i++) if(latest.optJSONObject(i).optString("id").equals(item.optString("id"))) { savedCard(latest.optJSONObject(i)); return; }
                library();
            },2000);
        }
    }
    private void settings() {
        page("settings","Настройки"); section("Сервер");
        try {
            JSONObject session=Vault.load(this);
            body.addView(text(session.optString("server","Не подключён"),15,MUTED));
            if(!session.optString("key").isEmpty()) body.addView(button("Выйти из аккаунта",this::confirmLogout));
            else body.addView(button("Войти по приглашению",this::home));
        } catch(Exception e) { body.addView(button("Сбросить подключение",this::confirmLogout)); }
        divider(); section("Воспроизведение");
        String[] quality={"480p","720p","1080p","2160p","Original"}; Spinner q=new Spinner(this); q.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,quality));
        q.setSelection(Math.max(0,Arrays.asList(quality).indexOf(prefs.getString("quality","1080p"))));
        body.addView(text("Предпочитаемое разрешение",16,TEXT)); body.addView(q);
        q.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { public void onNothingSelected(AdapterView<?> p) { } public void onItemSelected(AdapterView<?> p,View v,int n,long id) { prefs.edit().putString("quality",quality[n]).apply(); } });
        TextView buffer=text("Буферизация: "+prefs.getInt("buffer",15)+" с",16,TEXT); body.addView(buffer);
        SeekBar seek=new SeekBar(this); seek.setMin(5); seek.setMax(120); seek.setProgress(prefs.getInt("buffer",15)); body.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onStartTrackingTouch(SeekBar s) { } public void onStopTrackingTouch(SeekBar s) { } public void onProgressChanged(SeekBar s,int n,boolean user) { prefs.edit().putInt("buffer",n).apply(); buffer.setText("Буферизация: "+n+" с"); } });
        body.addView(text("Выбор аудио и субтитров — в меню внешнего плеера. Продолжение позиции работает с плеерами, поддерживающими возврат результата.",14,MUTED));
        divider(); section("Загрузки"); Switch ask=new Switch(this); ask.setText("Спрашивать место сохранения"); ask.setChecked(prefs.getBoolean("ask_path",true)); body.addView(ask);
        ask.setOnCheckedChangeListener((b,value)->prefs.edit().putBoolean("ask_path",value).apply());
        body.addView(text(prefs.getString("folder","").isEmpty()?"По умолчанию: Download/MApp":"Выбрана папка на устройстве",15,MUTED));
        body.addView(button("Выбрать папку по умолчанию",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),101)));
        body.addView(button("Использовать Download/MApp",()->{ prefs.edit().remove("folder").apply(); settings(); }));
        body.addView(text("Скачанные файлы остаются после удаления MApp. После переустановки добавьте их в библиотеку через системный выбор файлов.",14,MUTED));
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
                if(position>=0) { String movie=playingMovie; io.submit(()->{ try { request("PUT","/v1/history/"+movie,new JSONObject().put("position",position)); } catch(Exception ignored) { } }); }
            }
            if(result!=RESULT_OK || data==null) return;
            if(request==100) { persist(data.getData(),data.getFlags()); startDownload(data.getData()); }
            if(request==101) { persist(data.getData(),data.getFlags()); prefs.edit().putString("folder",data.getData().toString()).apply(); settings(); }
            if(request==102) {
                if(data.getClipData()!=null) for(int i=0;i<data.getClipData().getItemCount();i++) importFile(data.getClipData().getItemAt(i).getUri(),data.getFlags());
                else importFile(data.getData(),data.getFlags()); library();
            }
        } catch(Exception e) { error(e); }
    }
    private void persist(Uri uri,int flags) { getContentResolver().takePersistableUriPermission(uri,flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); }
    private void importFile(Uri uri,int flags) throws Exception {
        persist(uri,flags); String title="Фильм";
        try(var cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) { if(cursor!=null && cursor.moveToFirst()) title=cursor.getString(0); }
        Library.put(this,new JSONObject().put("id",uri.toString()).put("uri",uri.toString()).put("title",title).put("state","complete"));
    }
    @Override public void onBackPressed() {
        if(!screen.equals("home")) { home(); return; }
        new AlertDialog.Builder(this).setTitle("Выйти из MApp?").setPositiveButton("Выйти",(d,n)->finish()).setNegativeButton("Остаться",null).show();
    }
    @Override protected void onDestroy() { generation++; ui.removeCallbacksAndMessages(null); io.shutdown(); super.onDestroy(); }
}
