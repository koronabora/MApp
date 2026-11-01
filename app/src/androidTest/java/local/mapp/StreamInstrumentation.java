package local.mapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONObject;

/** Integration test against the explicitly installed local MCore demo and a paired account. */
public final class StreamInstrumentation extends Instrumentation {
    private void integrityChecks() throws Exception {
        byte[] payload={1,2,3,4}; StringBuilder hex=new StringBuilder();
        for(byte value:MessageDigest.getInstance("SHA-256").digest(payload)) hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        String hash=hex.toString();
        VerifiedChunk.validate(payload,"bytes 10-13/20",hash,10,13,20);
        VerifiedChunk.Fetch[] invalid={
            ()->VerifiedChunk.validate(new byte[]{1,2,3,5},"bytes 10-13/20",hash,10,13,20),
            ()->VerifiedChunk.validate(payload,"bytes 11-14/20",hash,10,13,20),
            ()->VerifiedChunk.validate(new byte[]{1,2,3},"bytes 10-13/20",hash,10,13,20),
            ()->VerifiedChunk.validate(payload,"bytes 10-13/20",null,10,13,20)};
        for(var check:invalid) {
            boolean denied=false; try { check.run(); } catch(java.io.IOException expected) { denied=true; }
            if(!denied) throw new AssertionError("Invalid chunk accepted");
        }
        int[] attempts={0};
        byte[] recovered=VerifiedChunk.retry(()->{ if(++attempts[0]<3) return invalid[0].run(); return payload; });
        if(attempts[0]!=3 || !Arrays.equals(recovered,payload)) throw new AssertionError("Chunk retry failed");
        attempts[0]=0; boolean denied=false;
        try { VerifiedChunk.retry(()->{ attempts[0]++; return invalid[0].run(); }); } catch(java.io.IOException expected) { denied=true; }
        if(!denied || attempts[0]!=3) throw new AssertionError("Retry limit failed");
    }
    private Bundle arguments;
    private void checkNavigation(Api api) throws Exception {
        var context=getTargetContext();
        var activity=(MainActivity)startActivitySync(new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            waitForIdleSync();
            runOnMainSync(()->{
                var decor=activity.getWindow().getDecorView();
                var settings=find(decor,v->v instanceof android.widget.Button && ((android.widget.Button)v).getText().toString().equals("Настройки"));
                var details=find(decor,v->v instanceof android.widget.Button && ((android.widget.Button)v).getText().toString().equals("Подробнее"));
                var search=find(decor,v->"Поиск фильма или сериала".contentEquals(v.getContentDescription()==null?"":v.getContentDescription()));
                if(settings==null || details==null || search==null || settings.getWidth()!=details.getWidth() || settings.getWidth()!=search.getWidth()) throw new AssertionError("Home button widths");
                search.performClick();
            });
            waitForIdleSync();
            var rootField=MainActivity.class.getDeclaredField("root"); rootField.setAccessible(true);
            var bodyField=MainActivity.class.getDeclaredField("body"); bodyField.setAccessible(true);
            var searchRoot=(android.view.View)rootField.get(activity);
            var scroll=(android.widget.ScrollView)find(searchRoot,v->v instanceof android.widget.ScrollView);
            var input=(android.widget.EditText)find(searchRoot,v->v instanceof android.widget.EditText);
            runOnMainSync(()->{ input.setText("MCore"); });
            Thread.sleep(2000); waitForIdleSync();
            runOnMainSync(()->{ try {
                var body=(android.widget.LinearLayout)bodyField.get(activity);
                for(int i=0;i<25;i++) { var row=new android.widget.TextView(activity); row.setText("Scroll fixture "+i); row.setHeight(100); body.addView(row); }
            } catch(Exception e) { throw new RuntimeException(e); } });
            waitForIdleSync(); runOnMainSync(()->scroll.scrollTo(0,500));
            int position=scroll.getScrollY(); if(position==0) throw new AssertionError("Scroll fixture did not scroll");
            var movies=api.request("GET","/v1/search?q=MCore&live=false",null).getJSONArray("movies"); String mid="";
            for(int i=0;i<movies.length();i++) if(movies.getJSONObject(i).optString("title").equals("MCore demo")) mid=movies.getJSONObject(i).getString("id");
            if(mid.isEmpty()) throw new AssertionError("Missing demo"); String movieId=mid;
            runOnMainSync(()->{ try { var method=MainActivity.class.getDeclaredMethod("movie",String.class); method.setAccessible(true); method.invoke(activity,movieId); } catch(Exception e) { throw new RuntimeException(e); } });
            Thread.sleep(1500);
            runOnMainSync(activity::onBackPressed); waitForIdleSync();
            if(rootField.get(activity)!=searchRoot || scroll.getScrollY()!=position || !input.getText().toString().equals("MCore")) throw new AssertionError("Search state was not restored");
            runOnMainSync(()->{ try { var method=MainActivity.class.getDeclaredMethod("movie",String.class); method.setAccessible(true); method.invoke(activity,movieId); } catch(Exception e) { throw new RuntimeException(e); } });
            waitForIdleSync();
            runOnMainSync(()->{
                var back=find(activity.getWindow().getDecorView(),v->v instanceof android.widget.Button && ((android.widget.Button)v).getText().toString().equals("Назад"));
                if(back==null) throw new AssertionError("Missing toolbar Back");
                back.performClick();
            });
            waitForIdleSync();
            if(rootField.get(activity)!=searchRoot || scroll.getScrollY()!=position || !input.getText().toString().equals("MCore")) throw new AssertionError("Toolbar Back lost search state");
            runOnMainSync(()->{ try { var method=MainActivity.class.getDeclaredMethod("settings"); method.setAccessible(true); method.invoke(activity); } catch(Exception e) { throw new RuntimeException(e); } });
            waitForIdleSync();
            runOnMainSync(()->{ try {
                var body=(android.widget.LinearLayout)bodyField.get(activity); int count=0;
                for(int i=0;i<body.getChildCount();i++) if(body.getChildAt(i) instanceof android.widget.Button && !(body.getChildAt(i) instanceof android.widget.CompoundButton)) {
                    int width=body.getChildAt(i).getWidth(); if(width>=body.getWidth()) throw new AssertionError("Settings button is full width"); count++;
                }
                if(count==0) throw new AssertionError("No settings buttons");
            } catch(RuntimeException e) { throw e; } catch(Exception e) { throw new RuntimeException(e); } });
        } finally { runOnMainSync(activity::finish); }
    }
    private void checkFeedback(Api api) throws Exception {
        var context=getTargetContext(); var prefs=context.getSharedPreferences("settings",0);
        var enabled=new java.util.concurrent.CountDownLatch(1);
        Diagnostics.enable(context,true,message->enabled.countDown());
        if(!enabled.await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Log creation timed out");
        api.request("GET","/v1/health",null);
        Thread.sleep(2500);
        String path=prefs.getString("diagnostic_uri","");
        if(path.isEmpty()) throw new AssertionError("No shared log file");
        String log;
        try(var input=context.getContentResolver().openInputStream(android.net.Uri.parse(path))) { log=new String(Api.read(input,3*1024*1024),java.nio.charset.StandardCharsets.UTF_8); }
        if(!log.contains("/v1/health") || log.contains(api.key)) throw new AssertionError("Missing diagnostics or exposed key");
        var disabled=new java.util.concurrent.CountDownLatch(1);
        Diagnostics.enable(context,false,message->disabled.countDown());
        if(!disabled.await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Log deletion timed out");
        if(new org.json.JSONArray(prefs.getString("diagnostic_files","[]")).length()!=0) throw new AssertionError("Log files not removed");
        boolean exists=false;
        try(var input=context.getContentResolver().openInputStream(android.net.Uri.parse(path))) { exists=input!=null; } catch(java.io.FileNotFoundException | IllegalArgumentException expected) { }
        if(exists) throw new AssertionError("Disabled log remains readable");
        var activity=(MainActivity)startActivitySync(new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            waitForIdleSync();
            if(find(activity.getWindow().getDecorView(),v->v instanceof android.widget.TextView && ((android.widget.TextView)v).getText().toString().equals("Кинозал"))==null) throw new AssertionError("Home title");
            runOnMainSync(()->{ try {
                var method=MainActivity.class.getDeclaredMethod("showScreenshot",String.class); method.setAccessible(true); method.invoke(activity,"/v1/releases/local:2/screenshots/0");
            } catch(Exception e) { throw new RuntimeException(e); } });
            var dialogField=MainActivity.class.getDeclaredField("screenshotDialog"); dialogField.setAccessible(true);
            var dialog=(android.app.Dialog)dialogField.get(activity);
            long deadline=System.currentTimeMillis()+30000;
            android.widget.ImageView[] image={null};
            while(System.currentTimeMillis()<deadline) {
                runOnMainSync(()->image[0]=(android.widget.ImageView)find(dialog.getWindow().getDecorView(),v->v instanceof ZoomImage));
                if(image[0]!=null && image[0].getDrawable()!=null) break;
                Thread.sleep(100);
            }
            if(image[0]==null || image[0].getDrawable()==null) throw new AssertionError("Fullscreen screenshot did not load");
            try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"feedback-viewer.png"))) {
                getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
            }
            runOnMainSync(()->find(dialog.getWindow().getDecorView(),v->"Закрыть скриншот".contentEquals(v.getContentDescription()==null?"":v.getContentDescription())).performClick());
            if(dialog.isShowing()) throw new AssertionError("Screenshot close button");
        } finally { runOnMainSync(activity::finish); }
    }
    private void checkEpisodes(Api api) throws Exception {
        var context=getTargetContext();
        String key=api.request("POST","/v1/torrents",new JSONObject().put("release","local:3")).getString("id");
        var files=api.request("GET","/v1/torrents/"+key,null).getJSONArray("files");
        JSONObject card=null; var movies=api.request("GET","/v1/search?q=MCore&live=false",null).getJSONArray("movies");
        for(int i=0;i<movies.length();i++) if(movies.optJSONObject(i).optString("title").equals("MCore series demo")) card=movies.optJSONObject(i);
        if(card==null || files.length()!=2) throw new AssertionError("Install synthetic series fixture first");
        String mid=card.getString("id");
        for(int i=0;i<files.length();i++) { var file=files.getJSONObject(i); api.request("PUT","/v1/history/"+mid,new JSONObject().put("position",i==0?3000:8000).put("torrent",key).put("file",file.getInt("index")).put("episode",file.getString("episode"))); }
        var activity=(MainActivity)startActivitySync(new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        var chosen=MainActivity.class.getDeclaredField("chosenFile"); chosen.setAccessible(true);
        try {
            runOnMainSync(()->{ try { var method=MainActivity.class.getDeclaredMethod("movie",String.class); method.setAccessible(true); method.invoke(activity,mid); } catch(Exception e) { throw new RuntimeException(e); } });
            long deadline=System.currentTimeMillis()+30000;
            while(chosen.get(activity)==null && System.currentTimeMillis()<deadline) Thread.sleep(100);
            if(chosen.get(activity)==null) throw new AssertionError("Episode selector did not load");
            JSONObject selected=(JSONObject)chosen.get(activity);
            if(selected.optLong("position")!=8000) throw new AssertionError("Last episode was not preselected");
            runOnMainSync(()->{
                var selector=(android.widget.Spinner)find(activity.getWindow().getDecorView(),v->v instanceof android.widget.Spinner && "episode-selector".equals(v.getTag()));
                if(selector==null) throw new AssertionError("Episode list absent"); selector.setSelection(0);
            });
            waitForIdleSync();
            selected=(JSONObject)chosen.get(activity);
            if(selected.optLong("position")!=3000) throw new AssertionError("Episode positions mixed");
            var monitor=addMonitor(PlayerActivity.class.getName(),null,false);
            runOnMainSync(()->{ var watch=find(activity.getWindow().getDecorView(),v->v instanceof android.widget.Button && ((android.widget.Button)v).getText().toString().equals("Продолжить просмотр")); if(watch==null) throw new AssertionError("Resume button absent"); watch.performClick(); });
            var playerActivity=(PlayerActivity)waitForMonitorWithTimeout(monitor,30000); removeMonitor(monitor);
            if(playerActivity==null) throw new AssertionError("Selected episode did not launch");
            try {
                var field=PlayerActivity.class.getDeclaredField("player"); field.setAccessible(true); var player=(org.videolan.libvlc.MediaPlayer)field.get(playerActivity);
                deadline=System.currentTimeMillis()+20000;
                while(player.getTime()<5000 && System.currentTimeMillis()<deadline) Thread.sleep(100);
                if(player.getTime()<5000 || player.getSpuTracksCount()<1) throw new AssertionError("Episode playback or embedded subtitles unavailable");
            } finally { runOnMainSync(playerActivity::finish); }
            deadline=System.currentTimeMillis()+15000;
            boolean saved=false;
            while(System.currentTimeMillis()<deadline) {
                var episodes=api.request("GET","/v1/movies/"+mid,null).getJSONArray("episodes");
                if(episodes.getJSONObject(0).optLong("position")>=5000 && episodes.getJSONObject(1).optLong("position")==8000) { saved=true; break; }
                Thread.sleep(200);
            }
            if(!saved) throw new AssertionError("Independent episode checkpoint did not persist");
        } finally { runOnMainSync(activity::finish); }
    }
    private android.view.View find(android.view.View root,java.util.function.Predicate<android.view.View> match) {
        if(match.test(root)) return root;
        if(root instanceof android.view.ViewGroup group) for(int i=0;i<group.getChildCount();i++) { var result=find(group.getChildAt(i),match); if(result!=null) return result; }
        return null;
    }
    private void checkCard(Api api) throws Exception {
        var context=getTargetContext(); String folder=context.getSharedPreferences("settings",0).getString("folder","");
        var tree=android.net.Uri.parse(folder); var uri=FolderLibrary.create(context,tree,"MApp-card-test-"+java.util.UUID.randomUUID()+".mp4");
        JSONObject item=new JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("movie","card-test").put("release","local:8").put("uri",uri.toString()).put("server",api.server).put("state","downloading").put("size",100).put("downloaded",25);
        JSONObject data=new JSONObject().put("id","card-test").put("title","Card fixture").put("description","Offline card fixture").put("releases",new org.json.JSONArray()
            .put(new JSONObject().put("id","local:7").put("resolution","1080p").put("size","1 MiB").put("seeds",10))
            .put(new JSONObject().put("id","local:8").put("resolution","720p").put("size","1 MiB").put("seeds",5)));
        item.put("card",data); Library.put(context,item);
        var activity=(MainActivity)startActivitySync(new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            runOnMainSync(()->{ try {
                var page=MainActivity.class.getDeclaredMethod("page",String.class,String.class); page.setAccessible(true); page.invoke(activity,"movie","Карточка фильма");
                var show=MainActivity.class.getDeclaredMethod("showMovie",JSONObject.class); show.setAccessible(true); show.invoke(activity,data);
            } catch(Exception e) { throw new RuntimeException(e); } });
            waitForIdleSync();
            runOnMainSync(()->{
                var root=activity.getWindow().getDecorView();
                var spinner=(android.widget.Spinner)find(root,v->v instanceof android.widget.Spinner);
                var open=(android.widget.Button)find(root,v->v instanceof android.widget.Button && ((android.widget.Button)v).getText().toString().equals("Открыть сохранённое"));
                if(spinner.getSelectedItemPosition()!=1 || open==null || !open.isEnabled()) throw new AssertionError("Downloading release not selected or saved access disabled");
                var hint=(android.widget.TextView)find(root,v->v instanceof android.widget.TextView && ((android.widget.TextView)v).getText().toString().contains("Дорожки и субтитры"));
                if(hint==null || hint.getGravity()!=android.view.Gravity.CENTER) throw new AssertionError("Track hint not centered");
                var download=(android.widget.Button)find(root,v->v instanceof android.widget.Button && ((android.widget.Button)v).getText().toString().contains("25 %"));
                if(download==null || download.isEnabled()) throw new AssertionError("Duplicate download enabled or progress missing");
            });
            item.put("state","complete"); Library.put(context,item); Thread.sleep(1800); waitForIdleSync();
            runOnMainSync(()->{
                var root=activity.getWindow().getDecorView();
                var download=(android.widget.Button)find(root,v->v instanceof android.widget.Button && ((android.widget.Button)v).getText().toString().equals("Скачано"));
                if(download==null || download.isEnabled()) throw new AssertionError("Completed download not disabled");
                if(find(root,v->v instanceof android.widget.Button && ((android.widget.Button)v).getText().toString().equals("Смотреть сохранённое"))==null) throw new AssertionError("Local playback action missing");
                var spinner=(android.widget.Spinner)find(root,v->v instanceof android.widget.Spinner); spinner.setSelection(0);
            });
            Thread.sleep(1800); waitForIdleSync();
            runOnMainSync(()->{
                var download=(android.widget.Button)find(activity.getWindow().getDecorView(),v->v instanceof android.widget.Button && ((android.widget.Button)v).getText().toString().equals("Скачать на телефон"));
                if(download==null || !download.isEnabled()) throw new AssertionError("Other release download blocked");
            });
            android.provider.DocumentsContract.deleteDocument(context.getContentResolver(),uri);
            Thread.sleep(2500); waitForIdleSync();
            runOnMainSync(()->{
                var open=(android.widget.Button)find(activity.getWindow().getDecorView(),v->v instanceof android.widget.Button && ((android.widget.Button)v).getText().toString().equals("Открыть сохранённое"));
                if(open.isEnabled()) throw new AssertionError("Missing saved file left button enabled");
            });
        } finally { Library.remove(context,item.getString("id")); try { android.provider.DocumentsContract.deleteDocument(context.getContentResolver(),uri); } catch(Exception ignored) { } runOnMainSync(activity::finish); }
    }
    private JSONObject waitDownload(String id) throws Exception {
        long started=System.currentTimeMillis(), until=started+90000;
        while(System.currentTimeMillis()<until) {
            var items=Library.all(getTargetContext());
            for(int i=0;i<items.length();i++) { JSONObject item=items.optJSONObject(i); if(item.optString("id").equals(id)) {
                if(item.optString("state").equals("paused") && System.currentTimeMillis()>started+1500 && !DownloadService.queued.contains(id)) throw new AssertionError("Download paused: "+item.optString("error"));
                if(item.optString("state").equals("complete") && !DownloadService.queued.contains(id)) return item;
            } }
            Thread.sleep(250);
        }
        throw new AssertionError("Download did not finish");
    }
    private void checkLibrary(Api api) throws Exception {
        var context=getTargetContext(); String folder=context.getSharedPreferences("settings",0).getString("folder","");
        if(folder.isEmpty()) throw new AssertionError("Select a test SAF folder first");
        var activity=startActivitySync(new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        String previous=Library.all(context).toString(); JSONObject item=null;
        try {
            String key=api.request("POST","/v1/torrents",new JSONObject().put("release","local:1")).getString("id");
            JSONObject file=api.request("GET","/v1/torrents/"+key,null).getJSONArray("files").getJSONObject(0);
            String name="MApp-test-"+java.util.UUID.randomUUID()+".mp4";
            var uri=FolderLibrary.create(context,android.net.Uri.parse(folder),name);
            item=new JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("uri",uri.toString()).put("folder",folder).put("name",name)
                .put("movie","test-recovery").put("title","Recovery fixture").put("year","2026").put("release","local:1").put("size",file.getLong("size"))
                .put("path","/v1/torrents/"+key+"/files/0").put("server",api.server).put("state","queued");
            Library.put(context,item); FolderLibrary.backup(context,item);
            context.startForegroundService(new android.content.Intent(context,DownloadService.class).putExtra("item",item.toString()));
            item=waitDownload(item.getString("id"));
            byte[] actual; try(var input=context.getContentResolver().openInputStream(uri)) { actual=Api.read(input,32*1024*1024); }
            var remote=api.open(item.getString("path")); byte[] expected;
            try(var input=remote.getInputStream()) { expected=Api.read(input,32*1024*1024); } finally { remote.disconnect(); }
            if(!Arrays.equals(actual,expected)) throw new AssertionError("Saved file checksum mismatch");
            Library.remove(context,item.getString("id"));
            if(FolderLibrary.restore(context,android.net.Uri.parse(folder))<1) throw new AssertionError("No file restored");
            JSONObject restored=null; var list=Library.all(context);
            for(int i=0;i<list.length();i++) if(list.optJSONObject(i).optString("id").equals(item.getString("id"))) restored=list.optJSONObject(i);
            if(restored==null || !restored.optString("state").equals("complete") || !restored.optString("title").equals("Recovery fixture")) throw new AssertionError("Restored metadata differs");
            if(FolderLibrary.restore(context,android.net.Uri.parse(folder))!=0) throw new AssertionError("Duplicate recovery");
            item=restored;
            try(var fd=context.getContentResolver().openFileDescriptor(android.net.Uri.parse(item.getString("uri")),"rw"); var out=new java.io.FileOutputStream(fd.getFileDescriptor())) { out.getChannel().truncate(1024*1024); }
            item.put("state","paused").put("downloaded",1024*1024); FolderLibrary.backup(context,item); Library.remove(context,item.getString("id"));
            FolderLibrary.restore(context,android.net.Uri.parse(folder)); list=Library.all(context);
            for(int i=0;i<list.length();i++) if(list.optJSONObject(i).optString("id").equals(item.getString("id"))) item=list.optJSONObject(i);
            if(!item.optString("state").equals("paused") || item.optLong("downloaded")!=1024*1024) throw new AssertionError("Partial file incorrectly restored");
            context.startForegroundService(new android.content.Intent(context,DownloadService.class).putExtra("item",item.toString()));
            item=waitDownload(item.getString("id"));
            try(var input=context.getContentResolver().openInputStream(android.net.Uri.parse(item.getString("uri")))) { if(!Arrays.equals(Api.read(input,32*1024*1024),expected)) throw new AssertionError("Resumed download differs"); }
            if(!FolderLibrary.fromName("Example.S01E01.mkv",1).getString("movie").equals(FolderLibrary.fromName("Example.S02E01.mkv",1).getString("movie"))) throw new AssertionError("Recovered series not grouped");
            String beforeSorting=Library.all(context).toString();
            try {
                var records=new org.json.JSONArray()
                    .put(new JSONObject().put("id","1").put("movie","b").put("title","Бета").put("state","complete"))
                    .put(new JSONObject().put("id","2").put("movie","a").put("title","Альфа").put("state","complete"))
                    .put(new JSONObject().put("id","3").put("movie","z").put("title","Янтарь").put("state","complete"))
                    .put(new JSONObject().put("id","4").put("movie","z").put("title","Янтарь").put("state","downloading"));
                context.getSharedPreferences("library",0).edit().putString("items",records.toString()).commit();
                var grouped=Library.grouped(context);
                if(grouped.length()!=3 || !grouped.optJSONObject(0).optString("id").equals("4") || !grouped.optJSONObject(1).optString("title").equals("Альфа") || !grouped.optJSONObject(2).optString("title").equals("Бета")) throw new AssertionError("Grouped library ordering incorrect");
            } finally { context.getSharedPreferences("library",0).edit().putString("items",beforeSorting).commit(); }
        } finally {
            if(item!=null) {
                String id=item.optString("id"); if(DownloadService.queued.contains(id)) DownloadService.cancelled.add(id);
                long deadline=System.currentTimeMillis()+10000; while(DownloadService.queued.contains(id) && System.currentTimeMillis()<deadline) Thread.sleep(100);
                try { FolderLibrary.remove(context,item); } catch(Exception ignored) { }
            }
            context.getSharedPreferences("library",0).edit().putString("items",previous).commit();
            runOnMainSync(activity::finish);
        }
    }
    private void checkPlayer(Api api,String release) throws Exception {
        var context=getTargetContext();
        String key=api.request("POST","/v1/torrents",new JSONObject().put("release",release)).getString("id");
        JSONObject state=api.request("GET","/v1/torrents/"+key+"?buffer=5",null);
        long deadline=System.currentTimeMillis()+60000;
        while(!state.optBoolean("ready") && System.currentTimeMillis()<deadline) { Thread.sleep(500); state=api.request("GET","/v1/torrents/"+key+"?buffer=5",null); }
        if(!state.optBoolean("ready")) throw new AssertionError("Fixture buffer not ready");
        if(release.equals("local:2") && state.optDouble("progress")>=1) throw new AssertionError("Fixture must be incomplete");
        JSONObject file=state.getJSONArray("files").getJSONObject(0);
        var uri=MediaProvider.create(context,"/v1/torrents/"+key+"/files/"+file.getInt("index"),file.getLong("size"),file.getString("name"));
        boolean prompt=arguments.containsKey("audio"), oldAsk=context.getSharedPreferences("settings",0).getBoolean("ask_audio",false);
        context.getSharedPreferences("settings",0).edit().putBoolean("ask_audio",prompt).commit();
        var activity=(PlayerActivity)startActivitySync(new android.content.Intent(context,PlayerActivity.class).setData(uri).putExtra("position",prompt?0L:2000L).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            var field=PlayerActivity.class.getDeclaredField("player"); field.setAccessible(true);
            var player=(org.videolan.libvlc.MediaPlayer)field.get(activity);
            if(prompt) {
                var dialogField=PlayerActivity.class.getDeclaredField("audioDialog"); dialogField.setAccessible(true);
                deadline=System.currentTimeMillis()+30000;
                while(dialogField.get(activity)==null && System.currentTimeMillis()<deadline) Thread.sleep(100);
                var dialog=(android.app.AlertDialog)dialogField.get(activity);
                if(dialog==null) throw new AssertionError("Audio prompt did not appear");
                Thread.sleep(500);
                if(player.isPlaying()) throw new AssertionError("Playback not paused for audio choice");
                runOnMainSync(()->{ var list=dialog.getListView(); list.performItemClick(list.getChildAt(1),1,list.getAdapter().getItemId(1)); });
            }
            deadline=System.currentTimeMillis()+60000;
            while(player.getTime()<4000 && System.currentTimeMillis()<deadline) Thread.sleep(250);
            if(player.getTime()<4000 || player.getAudioTracksCount()<1 || player.getVideoTracksCount()<1) throw new AssertionError("Decoder did not play");
            var media=player.getMedia(); var stats=media.getStats(); media.release();
            if(stats==null || stats.displayedPictures<=0) throw new AssertionError("No video frames displayed");
            long target=player.getLength()-5000; runOnMainSync(()->player.setTime(target));
            deadline=System.currentTimeMillis()+20000;
            int displayed=stats.displayedPictures; boolean tailPlayed=false;
            while(System.currentTimeMillis()<deadline) {
                media=player.getMedia(); stats=media.getStats(); media.release();
                if(player.getTime()>=target+700 && stats!=null && stats.displayedPictures>displayed+2) { tailPlayed=true; break; }
                Thread.sleep(250);
            }
            if(!tailPlayed) throw new AssertionError("Seek did not display tail frames: position="+player.getTime()+" target="+target+" frames="+(stats==null?-1:stats.displayedPictures)+" before="+displayed+" playing="+player.isPlaying());
            var audio=player.getAudioTracks();
            if(audio!=null && audio.length>1) { int track=audio[audio.length-1].id; runOnMainSync(()->player.setAudioTrack(track)); Thread.sleep(250); if(player.getAudioTrack()!=track) throw new AssertionError("Audio selection failed"); }
            if(release.equals("local:2") && api.request("GET","/v1/torrents/"+key,null).optDouble("progress")>=1) throw new AssertionError("Incomplete playback was not exercised");
        } finally { context.getSharedPreferences("settings",0).edit().putBoolean("ask_audio",oldAsk).commit(); runOnMainSync(activity::finish); }
    }
    @Override public void onCreate(Bundle args) { arguments=args; super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            var context=getTargetContext();
            Api api=new Api(context);
            integrityChecks();
            if(arguments!=null && arguments.containsKey("navigation")) {
                checkNavigation(api); result.putString("stream","PASS: equal home buttons, golden-ratio settings buttons, search query/results/scroll restored after movie");
                finish(Activity.RESULT_OK,result); return;
            }
            if(arguments!=null && arguments.containsKey("feedback")) {
                checkFeedback(api); result.putString("stream","PASS: shared-folder diagnostics, no credentials, deletion, home title and screenshot viewer");
                finish(Activity.RESULT_OK,result); return;
            }
            if(arguments!=null && arguments.containsKey("episodes")) {
                checkEpisodes(api); result.putString("stream","PASS: episode selector, independent resume positions, MKV playback/subtitles and persisted history");
                finish(Activity.RESULT_OK,result); return;
            }
            if(arguments!=null && arguments.containsKey("card")) {
                checkCard(api); result.putString("stream","PASS: active download preselected, centered hint, saved action follows file availability");
                finish(Activity.RESULT_OK,result); return;
            }
            if(arguments!=null && arguments.containsKey("library")) {
                checkLibrary(api); result.putString("stream","PASS: verified SAF download, completed and partial recovery, no duplicates, resume checksum, series grouping");
                finish(Activity.RESULT_OK,result); return;
            }
            if(arguments!=null && arguments.containsKey("player")) {
                checkPlayer(api,arguments.getString("player"));
                result.putString("stream","PASS: embedded decoder displays frames, audio tracks, resume and seek to tail");
                finish(Activity.RESULT_OK,result); return;
            }
            if(arguments!=null && arguments.containsKey("torrent")) {
                String torrent=arguments.getString("torrent");
                JSONObject media=api.request("GET","/v1/torrents/"+torrent,null).getJSONArray("files").getJSONObject(0);
                long length=media.getLong("size");
                String mediaPath="/v1/torrents/"+torrent+"/files/"+media.getInt("index");
                var mediaUri=MediaProvider.create(context,mediaPath,length,media.getString("name"));
                try(var descriptor=context.getContentResolver().openFileDescriptor(mediaUri,"r"); var input=new FileInputStream(descriptor.getFileDescriptor())) {
                    for(long offset:new long[]{0,1048576-13,length/2,2147483648L-4096,length-8192}) {
                        if(offset<0 || offset+8192>length) continue;
                        var range=api.open(mediaPath); range.setRequestProperty("Range","bytes="+offset+"-"+(offset+8191));
                        byte[] bytes;
                        try(var stream=range.getInputStream()) { bytes=Api.read(stream,8192); } finally { range.disconnect(); }
                        input.getChannel().position(offset); ByteBuffer actual=ByteBuffer.allocate(8192);
                        while(actual.hasRemaining()) if(input.getChannel().read(actual)<0) throw new AssertionError("Premature EOF");
                        if(!Arrays.equals(bytes,actual.array())) throw new AssertionError("Real media range mismatch at "+offset);
                    }
                }
                result.putString("stream","PASS: real media bridge ranges, cross-buffer seek, >2 GiB and EOF");
                finish(Activity.RESULT_OK,result); return;
            }
            JSONObject bad=new JSONObject(Vault.load(context).toString()).put("pin","0000000000000000000000000000000000000000000000000000000000000000");
            boolean rejected=false;
            try { new Api(bad).request("GET","/v1/home",null); }
            catch(javax.net.ssl.SSLException expected) { rejected=true; }
            if(!rejected) throw new AssertionError("Invalid certificate pin was accepted");
            String key=api.request("POST","/v1/torrents",new JSONObject().put("release","local:1")).getString("id");
            JSONObject file=api.request("GET","/v1/torrents/"+key,null).getJSONArray("files").getJSONObject(0);
            String path="/v1/torrents/"+key+"/files/"+file.getInt("index");
            var connection=api.open(path); byte[] expected;
            try(var in=connection.getInputStream()) { expected=Api.read(in,32*1024*1024); }
            finally { connection.disconnect(); }
            for(int offset=0;offset<expected.length;offset+=4*1024*1024) {
                int end=Math.min(expected.length-1,offset+4*1024*1024-1);
                byte[] chunk=VerifiedChunk.read(api,path,offset,end,expected.length);
                if(!Arrays.equals(chunk,Arrays.copyOfRange(expected,offset,end+1))) throw new AssertionError("Verified download differs from original");
            }
            var uri=MediaProvider.create(context,path,file.getLong("size"),file.getString("name"));
            try(var fd=context.getContentResolver().openFileDescriptor(uri,"r"); var input=new FileInputStream(fd.getFileDescriptor())) {
                MessageDigest hash=MessageDigest.getInstance("SHA-256");
                byte[] chunk=new byte[137777]; int count; long size=0;
                while((count=input.read(chunk))!=-1) { hash.update(chunk,0,count); size+=count; }
                if(size!=expected.length || !Arrays.equals(hash.digest(),MessageDigest.getInstance("SHA-256").digest(expected))) throw new AssertionError("ContentProvider full-file checksum mismatch");
                input.getChannel().position(1024*1024-13);
                ByteBuffer seek=ByteBuffer.allocate(8192);
                while(seek.hasRemaining()) if(input.getChannel().read(seek)<0) throw new AssertionError("Unexpected EOF during seek");
                if(!Arrays.equals(seek.array(),Arrays.copyOfRange(expected,1024*1024-13,1024*1024-13+8192))) throw new AssertionError("Random seek mismatch");
            }
            JSONObject session=Vault.load(context);
            String library=Library.all(context).toString();
            try {
                Vault.clear(context);
                if(!Vault.load(context).optString("key").isEmpty() || !Vault.sessionIdentity(context).isEmpty()) throw new AssertionError("Logout retained credentials");
                boolean denied=false;
                try(var stale=context.getContentResolver().openFileDescriptor(uri,"r")) { }
                catch(java.io.FileNotFoundException expectedError) { denied=true; }
                if(!denied) throw new AssertionError("Old stream survived logout");
                if(!Library.all(context).toString().equals(library)) throw new AssertionError("Logout changed saved library");
            } finally { Vault.save(context,session); }
            result.putString("stream","PASS: verified SHA-256 downloads; corrupted/wrong/truncated/unhashed chunks rejected; retry recovery and limit; TLS, stream, seeking, logout");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {
            result.putString("stream","FAIL: "+e); finish(Activity.RESULT_CANCELED,result);
        }
    }
}
