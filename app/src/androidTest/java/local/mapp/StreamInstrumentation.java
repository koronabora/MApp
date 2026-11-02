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
    private void checkSeasons033(Api api) throws Exception {
        var context=getTargetContext(); context.getSharedPreferences("settings",0).edit().putBoolean("ask_path",false).commit();
        String key=api.request("POST","/v1/torrents",new JSONObject().put("release","local:4")).getString("id");
        if(api.request("GET","/v1/torrents/"+key,null).getJSONArray("files").length()!=3) throw new AssertionError("Install multi-season fixture");
        var movies=api.request("GET","/v1/search?q=MCore&live=false",null).getJSONArray("movies"); String mid="";
        for(int i=0;i<movies.length();i++) if(movies.optJSONObject(i).optString("title").equals("MCore multiseason demo")) mid=movies.getJSONObject(i).getString("id");
        final String movie=mid; if(movie.isEmpty()) throw new AssertionError("Multi-season movie missing");
        var activity=(MainActivity)startActivitySync(new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        var chosen=MainActivity.class.getDeclaredField("chosenFile"); chosen.setAccessible(true);
        try {
            runOnMainSync(()->{ try { var m=MainActivity.class.getDeclaredMethod("movie",String.class); m.setAccessible(true); m.invoke(activity,movie); } catch(Exception e) { throw new RuntimeException(e); } });
            long deadline=System.currentTimeMillis()+30000; while(chosen.get(activity)==null && System.currentTimeMillis()<deadline) Thread.sleep(100);
            if(chosen.get(activity)==null) throw new AssertionError("Multi-season list not loaded");
            runOnMainSync(()->{ var season=(android.widget.Spinner)find(activity.getWindow().getDecorView(),v->"file-season-selector".equals(v.getTag())); if(season.getCount()!=2) throw new AssertionError("Season pack was not split"); season.setSelection(1); }); waitForIdleSync();
            if(((JSONObject)chosen.get(activity)).optInt("season")!=2) throw new AssertionError("Season selection ignored");
            runOnMainSync(()->{ var root=activity.getWindow().getDecorView(); var episodes=(android.widget.Spinner)find(root,v->"episode-selector".equals(v.getTag())); if(episodes.getCount()!=2) throw new AssertionError("Episode list mixes seasons");
                find(root,v->v instanceof android.widget.Button && ((android.widget.Button)v).getText().toString().equals("Скачать сезон")).performClick(); });
            deadline=System.currentTimeMillis()+60000; int completed=0;
            while(System.currentTimeMillis()<deadline) { completed=0; var all=Library.all(context); for(int i=0;i<all.length();i++) { var row=all.optJSONObject(i); if(row.optString("movie").equals(movie)) { if(row.optInt("season")!=2) throw new AssertionError("Downloaded unwanted season"); if(row.optString("state").equals("complete")) completed++; } } if(completed==2) break; Thread.sleep(200); }
            if(completed!=2) throw new AssertionError("Season batch incomplete");
            JSONObject card=api.request("GET","/v1/movies/"+movie,null);
            runOnMainSync(()->{ try { var m=MainActivity.class.getDeclaredMethod("saveAllSeasons",JSONObject.class); m.setAccessible(true); m.invoke(activity,card); } catch(Exception e) { throw new RuntimeException(e); } });
            deadline=System.currentTimeMillis()+60000;
            while(System.currentTimeMillis()<deadline) { completed=0; var all=Library.all(context); for(int i=0;i<all.length();i++) if(all.optJSONObject(i).optString("movie").equals(movie) && all.optJSONObject(i).optString("state").equals("complete")) completed++; if(completed==3) break; Thread.sleep(200); }
            if(completed!=3) throw new AssertionError("All seasons missing or duplicated episodes: "+completed);
        } finally { runOnMainSync(activity::finish); var all=Library.all(context); for(int i=0;i<all.length();i++) if(all.optJSONObject(i).optString("movie").equals(movie)) FolderLibrary.remove(context,all.getJSONObject(i)); }
    }
    private void checkExternal033(Api api) throws Exception {
        var context=getTargetContext(); var prefs=context.getSharedPreferences("settings",0); boolean external=prefs.getBoolean("external_player",false); int buffer=prefs.getInt("buffer",15);
        String remembered=prefs.getString("external_component","");
        prefs.edit().putBoolean("external_player",true).putInt("buffer",5).remove("external_component").commit();
        String key=api.request("POST","/v1/torrents",new JSONObject().put("release","local:2")).getString("id");
        JSONObject file=api.request("GET","/v1/torrents/"+key,null).getJSONArray("files").getJSONObject(0);
        String mid=""; var movies=api.request("GET","/v1/search?q=MCore&live=false",null).getJSONArray("movies");
        for(int i=0;i<movies.length();i++) if(movies.optJSONObject(i).optString("title").equals("MCore incomplete AVI")) mid=movies.getJSONObject(i).getString("id");
        if(mid.isEmpty()) throw new AssertionError("AVI fixture absent"); final String movie=mid;
        JSONObject card=api.request("GET","/v1/movies/"+movie,null);
        var uri=MediaProvider.create(context,"/v1/torrents/"+key+"/files/"+file.getInt("index"),file.getLong("size"),file.getString("name"));
        var activity=(MainActivity)startActivitySync(new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            runOnMainSync(()->{ try {
                var page=MainActivity.class.getDeclaredMethod("page",String.class,String.class); page.setAccessible(true); page.invoke(activity,"movie","Карточка фильма");
                var current=MainActivity.class.getDeclaredField("currentMovie"); current.setAccessible(true); current.set(activity,card);
                var torrent=MainActivity.class.getDeclaredField("selectedTorrent"); torrent.setAccessible(true); torrent.set(activity,key);
                var play=MainActivity.class.getDeclaredMethod("play",android.net.Uri.class,String.class,long.class); play.setAccessible(true); play.invoke(activity,uri,movie,5000L);
            } catch(Exception e) { throw new RuntimeException(e); } });
            long deadline=System.currentTimeMillis()+60000; boolean launched=false;
            while(System.currentTimeMillis()<deadline) { var root=getUiAutomation().getRootInActiveWindow(); if(root!=null && "org.videolan.vlc".contentEquals(root.getPackageName())) { launched=true; break; }
                if(root!=null) for(var node:root.findAccessibilityNodeInfosByText("VLC")) { if(!node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) && node.getParent()!=null) node.getParent().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK); }
                if(root!=null) for(var node:root.findAccessibilityNodeInfosByText("Всегда открывать")) if(!node.isChecked()) node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                if(root!=null) for(var node:root.findAccessibilityNodeInfosByViewId("android:id/button1")) if(node.getText()!=null && node.getText().toString().equalsIgnoreCase("Открыть")) node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                Thread.sleep(200); }
            if(!launched) throw new AssertionError("External VLC did not launch");
            Thread.sleep(12000);
            try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"external033.png"))) { getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); }
            getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            deadline=System.currentTimeMillis()+15000;
            while(System.currentTimeMillis()<deadline && WatchState.position(context,movie,"",key,0,-1)<=5000) Thread.sleep(200);
            long position=WatchState.position(context,movie,"",key,0,-1);
            if(position<=5000) throw new AssertionError("VLC did not return progress: "+position);
            var screen=MainActivity.class.getDeclaredField("screen"); screen.setAccessible(true);
            if(!"movie".equals(screen.get(activity))) throw new AssertionError("External return lost movie card");
            if(!prefs.getString("external_component","").startsWith("org.videolan.vlc/")) throw new AssertionError("Always player not remembered");
            runOnMainSync(()->{ try { var play=MainActivity.class.getDeclaredMethod("play",android.net.Uri.class,String.class,long.class); play.setAccessible(true); play.invoke(activity,uri,movie,5000L); } catch(Exception e) { throw new RuntimeException(e); } });
            deadline=System.currentTimeMillis()+60000; launched=false;
            while(System.currentTimeMillis()<deadline) { var root=getUiAutomation().getRootInActiveWindow(); if(root!=null && "org.videolan.vlc".contentEquals(root.getPackageName())) { launched=true; break; } Thread.sleep(200); }
            if(!launched) throw new AssertionError("Remembered player required another chooser");
            getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        } finally { prefs.edit().putBoolean("external_player",external).putInt("buffer",buffer).putString("external_component",remembered).commit(); context.stopService(new android.content.Intent(context,PlaybackService.class)); runOnMainSync(activity::finish); }
    }
    private void check033(Api api) throws Exception {
        var context=getTargetContext(); var prefs=context.getSharedPreferences("settings",0);
        prefs.edit().putBoolean("ask_path",false).putBoolean("external_player",false).commit();
        context.getSharedPreferences("watch-state",0).edit().clear().commit();
        String key=api.request("POST","/v1/torrents",new JSONObject().put("release","local:3")).getString("id");
        var files=api.request("GET","/v1/torrents/"+key,null).getJSONArray("files");
        var movies=api.request("GET","/v1/search?q=MCore&live=false",null).getJSONArray("movies"); String mid="";
        for(int i=0;i<movies.length();i++) if(movies.optJSONObject(i).optString("title").equals("MCore series demo")) mid=movies.getJSONObject(i).getString("id");
        final String movie=mid;
        if(movie.isEmpty() || files.length()!=2) throw new AssertionError("Series fixture missing");
        var activity=(MainActivity)startActivitySync(new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        var chosen=MainActivity.class.getDeclaredField("chosenFile"); chosen.setAccessible(true);
        try {
            runOnMainSync(()->{ try { var m=MainActivity.class.getDeclaredMethod("movie",String.class); m.setAccessible(true); m.invoke(activity,movie); } catch(Exception e) { throw new RuntimeException(e); } });
            long deadline=System.currentTimeMillis()+30000; while(chosen.get(activity)==null && System.currentTimeMillis()<deadline) Thread.sleep(100);
            if(chosen.get(activity)==null) throw new AssertionError("Series not loaded");
            try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"series033.png"))) { getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); }
            runOnMainSync(()->{
                var root=activity.getWindow().getDecorView();
                if(find(root,v->"file-season-selector".equals(v.getTag()))==null || find(root,v->"episode-selector".equals(v.getTag()))==null) throw new AssertionError("Two-level season/episode selector missing");
                var download=find(root,v->v instanceof android.widget.Button && ((android.widget.Button)v).getText().toString().equals("Скачать сезон"));
                if(download==null) throw new AssertionError("Season download missing"); download.performClick();
            });
            deadline=System.currentTimeMillis()+60000; int count=0;
            while(System.currentTimeMillis()<deadline) { count=0; var all=Library.all(context); for(int i=0;i<all.length();i++) if(all.optJSONObject(i).optString("movie").equals(movie) && all.optJSONObject(i).optString("state").equals("complete")) count++; if(count==2) break; Thread.sleep(200); }
            if(count!=2) throw new AssertionError("Season queue did not download both episodes");
            runOnMainSync(()->{ try { var m=MainActivity.class.getDeclaredMethod("library"); m.setAccessible(true); m.invoke(activity); } catch(Exception e) { throw new RuntimeException(e); } }); waitForIdleSync();
            runOnMainSync(()->{
                var root=activity.getWindow().getDecorView();
                if(find(root,v->v instanceof android.widget.TextView && ((android.widget.TextView)v).getText().toString().equals("Сезон 1 · Серия 1"))==null || find(root,v->v instanceof android.widget.TextView && ((android.widget.TextView)v).getText().toString().equals("Сезон 1 · Серия 2"))==null) throw new AssertionError("Individual episode labels missing");
                var delete=find(root,v->"Удалить файл".contentEquals(v.getContentDescription()==null?"":v.getContentDescription()));
                if(delete==null || delete.getWidth()!=Math.round(48*context.getResources().getDisplayMetrics().density) || delete.getRight()>((android.view.View)delete.getParent()).getWidth()) throw new AssertionError("Delete button overflow");
            });
            try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"downloads033.png"))) { getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); }
            JSONObject saved=null; var all=Library.all(context); for(int i=0;i<all.length();i++) if(all.optJSONObject(i).optString("movie").equals(movie)) { saved=all.getJSONObject(i); break; }
            if(saved==null) throw new AssertionError("Saved episode absent");
            // A fake remote path ensures the bridge must read the verified local prefix.
            JSONObject original=new JSONObject(saved.toString()); String fake="/v1/torrents/local-prefix-only/files/0";
            saved.put("path",fake); Library.put(context,saved);
            try(var bridge=new PlaybackBridge(context,fake,saved.getLong("size"))) {
                var connection=(java.net.HttpURLConnection)new java.net.URL(bridge.url()).openConnection(); connection.setRequestProperty("Range","bytes=17-8192");
                byte[] actual; try(var in=connection.getInputStream()) { actual=Api.read(in,8192); } finally { connection.disconnect(); }
                byte[] expected=new byte[8176];
                try(var fd=context.getContentResolver().openFileDescriptor(android.net.Uri.parse(saved.getString("uri")),"r"); var in=new java.io.FileInputStream(fd.getFileDescriptor())) { in.getChannel().position(17); new java.io.DataInputStream(in).readFully(expected); }
                if(!java.util.Arrays.equals(actual,expected)) throw new AssertionError("Local playback prefix mismatch");
            } finally { Library.put(context,original); }
            // Download progress updates must not overwrite a concurrent playback checkpoint.
            Library.progress(context,original.getString("uri"),4321,20000,false);
            Library.downloadState(context,original);
            for(int i=0;i<Library.all(context).length();i++) { var row=Library.all(context).getJSONObject(i); if(row.optString("id").equals(original.optString("id")) && row.optLong("position")!=4321) throw new AssertionError("Download overwrote playback checkpoint"); }
            runOnMainSync(()->{ try { chosen.set(activity,null); var m=MainActivity.class.getDeclaredMethod("movie",String.class); m.setAccessible(true); m.invoke(activity,movie); } catch(Exception e) { throw new RuntimeException(e); } });
            deadline=System.currentTimeMillis()+30000; while(chosen.get(activity)==null && System.currentTimeMillis()<deadline) Thread.sleep(100);
            Thread.sleep(1700); waitForIdleSync();
            var monitor=addMonitor(PlayerActivity.class.getName(),null,false);
            runOnMainSync(()->{ var watch=find(activity.getWindow().getDecorView(),v->v instanceof android.widget.Button && ((android.widget.Button)v).getText().toString().equals("Просмотр")); if(watch==null) throw new AssertionError("Saved episode action missing"); watch.performClick(); });
            var player=(PlayerActivity)waitForMonitorWithTimeout(monitor,15000); removeMonitor(monitor);
            if(player==null) throw new AssertionError("Saved episode opened a menu instead of playing");
            try { if("local.mapp.media".equals(player.getIntent().getData().getAuthority())) throw new AssertionError("Completed episode streamed instead of local file"); }
            finally { runOnMainSync(player::finish); }
            var screen=MainActivity.class.getDeclaredField("screen"); screen.setAccessible(true);
            if(!"movie".equals(screen.get(activity))) throw new AssertionError("Saved playback lost card");
        } finally {
            runOnMainSync(activity::finish);
            var all=Library.all(context); for(int i=0;i<all.length();i++) { var row=all.getJSONObject(i); if(row.optString("movie").equals(movie)) FolderLibrary.remove(context,row); }
        }
    }
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
        context.getSharedPreferences("watch-state",0).edit().clear().commit();
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
            deadline=System.currentTimeMillis()+5000;
            while(selected.optInt("index")!=0 && System.currentTimeMillis()<deadline) { Thread.sleep(100); selected=(JSONObject)chosen.get(activity); }
            if(selected.optLong("position")!=3000) throw new AssertionError("Episode positions mixed: "+selected);
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
            var screen=MainActivity.class.getDeclaredField("screen"); screen.setAccessible(true);
            if(!screen.get(activity).equals("movie")) throw new AssertionError("Player returned to home instead of card");
            var recreation=addMonitor(MainActivity.class.getName(),null,false);
            runOnMainSync(activity::recreate);
            var replacement=(MainActivity)waitForMonitorWithTimeout(recreation,15000); removeMonitor(recreation);
            if(replacement==null) throw new AssertionError("Main activity did not recreate");
            try {
                waitForIdleSync();
                if(!screen.get(replacement).equals("movie")) throw new AssertionError("Recreation lost movie card");
                var cardField=MainActivity.class.getDeclaredField("currentMovie"); cardField.setAccessible(true);
                if(!((JSONObject)cardField.get(replacement)).optString("id").equals(mid)) throw new AssertionError("Recreation restored wrong movie");
            } finally { runOnMainSync(replacement::finish); }
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
            runOnMainSync(()->{ try { var show=PlayerActivity.class.getDeclaredMethod("showControls"); show.setAccessible(true); show.invoke(activity); } catch(Exception e) { throw new RuntimeException(e); } });
            waitForIdleSync();
            try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"player033.png"))) { getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); }
            checkPlayerControls035(activity);
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
    private int actionCount(android.view.View view) {
        int count=view instanceof android.widget.Button && ("card-watch".equals(view.getTag()) || "card-download".equals(view.getTag()))?1:0;
        if(view instanceof android.view.ViewGroup group) for(int i=0;i<group.getChildCount();i++) count+=actionCount(group.getChildAt(i));
        return count;
    }
    private void check034(Api api) throws Exception {
        if(!MainActivity.cleanDescription("Uploader: Aids&lt;br&gt;Дикая Аляска").isEmpty() || MainActivity.cleanDescription("Описание &lt;b&gt;Аляски&lt;/b&gt;").contains("<")) throw new AssertionError("Cached description markup leaked");
        var context=getTargetContext(); String previous=Library.all(context).toString();
        var prefs=context.getSharedPreferences("settings",0); int percent=prefs.getInt("download_percent",10); prefs.edit().putInt("download_percent",10).commit();
        var target=java.io.File.createTempFile("local034", ".bin",context.getCacheDir());
        byte[] prefix=new byte[512*1024]; new java.util.Random(34).nextBytes(prefix); java.nio.file.Files.write(target.toPath(),prefix);
        var localUri=FolderLibrary.create(context,android.net.Uri.parse(prefs.getString("folder","")),"MApp-034-"+java.util.UUID.randomUUID()+".avi");
        try(var out=context.getContentResolver().openOutputStream(localUri,"wt")) { out.write(prefix); }
        String key=api.request("POST","/v1/torrents",new JSONObject().put("release","local:1")).getString("id");
        org.json.JSONArray remoteFiles=api.request("GET","/v1/torrents/"+key,null).getJSONArray("files");
        long metadataDeadline=System.currentTimeMillis()+30000;
        while(remoteFiles.length()==0 && System.currentTimeMillis()<metadataDeadline) { Thread.sleep(500); remoteFiles=api.request("GET","/v1/torrents/"+key,null).getJSONArray("files"); }
        var remote=remoteFiles.getJSONObject(0);
        String path="/v1/torrents/"+key+"/files/"+remote.getInt("index");
        JSONObject local=new JSONObject().put("id","check034").put("movie","check034").put("uri",localUri.toString())
            .put("path",path).put("server",api.server).put("size",remote.getLong("size")).put("downloaded",prefix.length).put("state","paused").put("name","XviD.avi").put("episode","s001e0001").put("season",1).put("number",1).put("series",true).put("release","a");
        Library.put(context,local);
        var activity=(MainActivity)startActivitySync(new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            try(var bridge=new PlaybackBridge(context,local)) {
                var first=(java.net.HttpURLConnection)new java.net.URL(bridge.url()).openConnection(); first.setRequestProperty("Range","bytes=0-8191");
                try(var in=first.getInputStream()) { if(!Arrays.equals(Api.read(in,8192),Arrays.copyOf(prefix,8192))) throw new AssertionError("Local prefix changed"); } finally { first.disconnect(); }
                var missing=(java.net.HttpURLConnection)new java.net.URL(bridge.url()).openConnection(); missing.setReadTimeout(5000); missing.setRequestProperty("Range","bytes=524288-532479");
                int count=0; try(var in=missing.getInputStream()) { byte[] bytes=new byte[8192]; int n; while((n=in.read(bytes))>0) count+=n; } catch(java.io.IOException expected) { } finally { missing.disconnect(); }
                if(count!=0) throw new AssertionError("Unsaved bytes fetched from server");
            }
            JSONObject r1=new JSONObject().put("id","a").put("series",true).put("resolution","720p").put("size","1 GB").put("provider","rutracker").put("seeds",12).put("release_title","Release information");
            JSONObject r2=new JSONObject(r1.toString()).put("id","b").put("resolution","1080p");
            JSONObject s1=new JSONObject().put("release","a").put("torrent",key).put("index",remote.getInt("index")).put("name","S01E01.mkv").put("size",remote.getLong("size")).put("episode","s001e0001").put("season",1).put("number",1);
            JSONObject s2=new JSONObject(s1.toString()).put("release","b").put("torrent","other");
            JSONObject work=new JSONObject().put("id","s001e0001").put("season",1).put("number",1).put("last_watched",true).put("sources",new org.json.JSONArray().put(s1).put(s2));
            JSONObject card=new JSONObject().put("id","check034").put("title","Проверка карточки").put("releases",new org.json.JSONArray().put(r1).put(r2))
                .put("works",new org.json.JSONArray().put(work)).put("complete_seasons",new JSONObject().put("a",new org.json.JSONArray().put(1)).put("b",new org.json.JSONArray())).put("indexing",false);
            runOnMainSync(()->{ try { var page=MainActivity.class.getDeclaredMethod("page",String.class,String.class); page.setAccessible(true); page.invoke(activity,"movie","Карточка фильма");
                var show=MainActivity.class.getDeclaredMethod("showMovie",JSONObject.class); show.setAccessible(true); show.invoke(activity,card);
            } catch(Exception e) { throw new RuntimeException(e); } }); waitForIdleSync();
            for(int n=0;n<6;n++) { final int selection=n%2;
                runOnMainSync(()->((android.widget.Spinner)find(activity.getWindow().getDecorView(),v->"source-selector".equals(v.getTag()))).setSelection(selection)); waitForIdleSync();
                runOnMainSync(()->{ var root=activity.getWindow().getDecorView();
                    if(actionCount(root)!=2) throw new AssertionError("Duplicate card actions");
                    var screenshots=find(root,v->"card-screenshots".equals(v.getTag()));
                    var description=find(root,v->"card-description".equals(v.getTag()));
                    var content=(android.view.ViewGroup)screenshots.getParent();
                    if(content.indexOfChild(screenshots)!=content.indexOfChild(description)+1) throw new AssertionError("Screenshots must directly follow description");
                    var line=find(root,v->"fixed-card-divider".equals(v.getTag()));
                    if(line==null || line.getParent() instanceof android.widget.ScrollView) throw new AssertionError("Fixed header divider missing");
                    var spinner=(android.widget.Spinner)find(root,v->"source-selector".equals(v.getTag()));
                    var label=(android.widget.TextView)spinner.getSelectedView();
                    if(!label.getText().toString().startsWith("●") || label.getText().toString().contains("rutracker")) throw new AssertionError("Availability dot/tracker label regression");
                    var detail=find(root,v->v instanceof android.widget.TextView && "Release information".contentEquals(((android.widget.TextView)v).getText()));
                    var watch=find(root,v->"card-watch".equals(v.getTag()));
                    int golden=(int)(activity.getResources().getDisplayMetrics().widthPixels/1.61803398875);
                    if(Math.abs(watch.getWidth()-golden)>1 || Math.abs(seasonButtonWidth(root)-golden)>1) throw new AssertionError("Card actions must use golden-ratio width");
                    if(selection==1 && find(root,v->"download-status".equals(v.getTag())).getVisibility()!=android.view.View.GONE) throw new AssertionError("Empty status reserves blank space");
                    var separator=find(root,v->"season-divider".equals(v.getTag()));
                    var seasonAction=find(root,v->"season-download".equals(v.getTag()));
                    if(seasonAction.getTop()-separator.getBottom()>5*activity.getResources().getDisplayMetrics().density) throw new AssertionError("Excess gap below season divider");
                    if(detail.getTop()>=watch.getTop()) throw new AssertionError("Release details below actions");
                    var seasonButton=find(root,v->"season-download".equals(v.getTag()));
                    if(seasonButton.isEnabled()!=(selection==0)) throw new AssertionError("Partial season source enabled");
                });
            }
            runOnMainSync(()->((android.widget.Spinner)find(activity.getWindow().getDecorView(),v->"source-selector".equals(v.getTag()))).setSelection(0)); waitForIdleSync();
            local.put("downloaded",local.getLong("size")*9/100); Library.put(context,local); Thread.sleep(2200);
            runOnMainSync(()->{ var root=activity.getWindow().getDecorView(); var watch=(android.widget.Button)find(root,v->"card-watch".equals(v.getTag())); if(watch.isEnabled() || !watch.getText().toString().equals("Просмотр")) throw new AssertionError("9% must be disabled"); });
            local.put("downloaded",(local.getLong("size")+9)/10); Library.put(context,local); Thread.sleep(2200);
            runOnMainSync(()->{ var watch=find(activity.getWindow().getDecorView(),v->"card-watch".equals(v.getTag())); if(!watch.isEnabled()) throw new AssertionError("10% must enable playback"); });
            runOnMainSync(()->{ var watch=(android.widget.Button)find(activity.getWindow().getDecorView(),v->"card-watch".equals(v.getTag())); watch.setTag(Boolean.TRUE); watch.setText("Буферизация"); });
            DownloadService.queued.add("check034"); Thread.sleep(2200);
            runOnMainSync(()->{ var download=(android.widget.Button)find(activity.getWindow().getDecorView(),v->"card-download".equals(v.getTag())); if(!download.getText().toString().equals("Остановить скачивание")) throw new AssertionError("Stop action missing"); download.performClick(); });
            if(!DownloadService.paused.contains("check034") || !FolderLibrary.exists(context,local)) throw new AssertionError("Stop deleted file instead of pausing");
            runOnMainSync(()->{ var root=activity.getWindow().getDecorView(); var watch=(android.widget.Button)find(root,v->"card-watch".equals(v.getTag())); if(watch==null || !watch.getText().toString().equals("Просмотр")) throw new AssertionError("Buffering survived local download"); });
            try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"card034.png"))) { getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); }
            runOnMainSync(()->{ var status=(android.widget.TextView)find(activity.getWindow().getDecorView(),v->"download-status".equals(v.getTag())); if(status==null || status.getGravity()!=android.view.Gravity.CENTER) throw new AssertionError("Download status not centered"); });
            local.put("folder",prefs.getString("folder","")).put("downloaded",0).put("state","queued"); Library.put(context,local); FolderLibrary.backup(context,local);
            DownloadService.paused.remove("check034");
            runOnMainSync(()->{ try { var method=MainActivity.class.getDeclaredMethod("library"); method.setAccessible(true); method.invoke(activity); } catch(Exception e) { throw new RuntimeException(e); } }); waitForIdleSync();
            runOnMainSync(()->{
                var button=(android.widget.ImageButton)find(activity.getWindow().getDecorView(),v->"download-control:check034".equals(v.getTag()));
                if(button==null || !"Приостановить загрузку".contentEquals(button.getContentDescription())) throw new AssertionError("Separate pause action missing");
                var column=(android.view.ViewGroup)button.getParent(); var delete=column.getChildAt(0);
                if(button.getTop()-delete.getBottom()<30*activity.getResources().getDisplayMetrics().density) throw new AssertionError("Pause too close to delete");
                button.performClick();
                if(!DownloadService.paused.contains("check034")) throw new AssertionError("Pause action did not pause download");
            });
            DownloadService.queued.remove("check034"); local.put("state","paused"); Library.put(context,local); Thread.sleep(1700);
            runOnMainSync(()->{ var button=find(activity.getWindow().getDecorView(),v->"download-control:check034".equals(v.getTag())); if(!button.isEnabled() || !"Продолжить загрузку".contentEquals(button.getContentDescription())) throw new AssertionError("Resume action missing after pause"); });
            DownloadService.delete(context,local);
            Library.downloadState(context,local); FolderLibrary.backup(context,local);
            if(FolderLibrary.exists(context,local)) throw new AssertionError("Queued download was not deleted immediately");
            for(int n=0;n<Library.all(context).length();n++) if("check034".equals(Library.all(context).optJSONObject(n).optString("id"))) throw new AssertionError("Deleted download resurrected");
        } finally {
            runOnMainSync(activity::finish); DownloadService.queued.remove("check034"); DownloadService.paused.remove("check034"); target.delete();
            if(FolderLibrary.exists(context,local)) android.provider.DocumentsContract.deleteDocument(context.getContentResolver(),localUri);
            DownloadService.cancelled.remove("check034");
            context.getSharedPreferences("library",0).edit().putString("items",previous).commit(); prefs.edit().putInt("download_percent",percent).commit();
        }
    }
    private int seasonButtonWidth(android.view.View root) { return find(root,v->"season-download".equals(v.getTag())).getWidth(); }
    private void checkPhoneDecoder() throws Exception {
        var context=getTargetContext(); JSONObject item=null;
        var items=Library.all(context);
        for(int i=0;i<items.length();i++) {
            var row=items.getJSONObject(i);
            if(row.optString("name").equals(arguments.getString("phoneDecoder")) && row.optString("state").equals("complete")) { item=row; break; }
        }
        if(item==null) throw new AssertionError("Requested completed file not found");
        var uri=android.net.Uri.parse(item.getString("uri"));
        if(!DecoderPolicy.software(context,DecoderPolicy.name(context,uri))) throw new AssertionError("Xvid software policy not selected");
        // No movie/saved_uri extras: diagnostic playback never updates the user's history.
        var activity=(PlayerActivity)startActivitySync(new android.content.Intent(context,PlayerActivity.class).setData(uri)
            .putExtra("position",55000L).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            var field=PlayerActivity.class.getDeclaredField("player"); field.setAccessible(true);
            var player=(org.videolan.libvlc.MediaPlayer)field.get(activity);
            long deadline=System.currentTimeMillis()+30000;
            while(player!=null && player.getTime()<61000 && System.currentTimeMillis()<deadline) Thread.sleep(100);
            if(player==null || player.getTime()<61000) throw new AssertionError("Saved AVI did not play past target scene");
            var media=player.getMedia(); var stats=media.getStats(); media.release();
            if(stats==null || stats.displayedPictures<10) throw new AssertionError("No decoded frames");
            checkPlayerControls035(activity);
            runOnMainSync(player::pause); waitForIdleSync();
            try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"phone-embedded-034.png"))) {
                getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
            }
        } finally { runOnMainSync(activity::finish); }
    }
    private void checkPlayerControls035(PlayerActivity activity) throws Exception {
        runOnMainSync(()->{ try {
            var show=PlayerActivity.class.getDeclaredMethod("showControls"); show.setAccessible(true); show.invoke(activity);
            var root=activity.getWindow().getDecorView();
            if(find(root,v->v instanceof android.widget.TextView && "MApp".contentEquals(((android.widget.TextView)v).getText()))!=null) throw new AssertionError("Player toolbar still has app title");
            for(String label:new String[]{"Назад","Повернуть экран","Блокировка управления","Назад 10 секунд","Пауза / продолжить","Вперёд 10 секунд","Аудиодорожки","Субтитры","Дополнительно"}) {
                var button=find(root,v->label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()));
                if(!(button instanceof android.widget.ImageButton) || button.getWidth()!=button.getHeight()) throw new AssertionError("Inconsistent vector control: "+label);
            }
            find(root,v->"Повернуть экран".contentEquals(v.getContentDescription()==null?"":v.getContentDescription())).performClick();
        } catch(Exception e) { throw new RuntimeException(e); } });
        long deadline=System.currentTimeMillis()+5000;
        while(activity.getResources().getConfiguration().orientation!=android.content.res.Configuration.ORIENTATION_LANDSCAPE && System.currentTimeMillis()<deadline) Thread.sleep(100);
        if(activity.getResources().getConfiguration().orientation!=android.content.res.Configuration.ORIENTATION_LANDSCAPE) throw new AssertionError("Rotate control failed");
        runOnMainSync(()->{ try { var show=PlayerActivity.class.getDeclaredMethod("showControls"); show.setAccessible(true); show.invoke(activity); } catch(Exception e) { throw new RuntimeException(e); } });
        waitForIdleSync();
        try(var out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),"player035.png"))) { getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); }
        var info=getTargetContext().getPackageManager().getActivityInfo(new android.content.ComponentName(getTargetContext(),MainActivity.class),0);
        if(info.screenOrientation!=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) throw new AssertionError("Main screen must return to portrait");
    }
    private void checkLocalAvi034(Api api) throws Exception {
        var context=getTargetContext(); String prior=Library.all(context).toString();
        var target=java.io.File.createTempFile("prefix034", ".avi",context.getCacheDir()); PlayerActivity activity=null;
        try {
            String key=api.request("POST","/v1/torrents",new JSONObject().put("release","local:2")).getString("id");
            var files=api.request("GET","/v1/torrents/"+key,null).getJSONArray("files");
            long until=System.currentTimeMillis()+30000; while(files.length()==0 && System.currentTimeMillis()<until) { Thread.sleep(500); files=api.request("GET","/v1/torrents/"+key,null).getJSONArray("files"); }
            JSONObject file=files.getJSONObject(0); String path="/v1/torrents/"+key+"/files/"+file.getInt("index");
            try(var output=new java.io.FileOutputStream(target)) {
                for(long offset=0;offset<24*1024*1024;offset+=4*1024*1024) output.write(VerifiedChunk.read(api,path,offset,offset+4*1024*1024-1,file.getLong("size")));
            }
            JSONObject item=new JSONObject().put("id","prefix034").put("uri",android.net.Uri.fromFile(target).toString()).put("path",path).put("server",api.server)
                .put("size",file.getLong("size")).put("downloaded",target.length()).put("state","paused").put("name","Xvid-prefix.avi"); Library.put(context,item);
            activity=(PlayerActivity)startActivitySync(new android.content.Intent(context,PlayerActivity.class).setData(android.net.Uri.fromFile(target)).putExtra("local_item",item.toString()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            var field=PlayerActivity.class.getDeclaredField("player"); field.setAccessible(true); var player=(org.videolan.libvlc.MediaPlayer)field.get(activity);
            until=System.currentTimeMillis()+35000;
            while(player.getTime()<3000 && System.currentTimeMillis()<until) Thread.sleep(200);
            var media=player.getMedia(); var stats=media.getStats(); media.release();
            if(player.getTime()<3000 || stats==null || stats.displayedPictures<5) throw new AssertionError("Local AVI prefix did not decode: time="+player.getTime());
        } finally { if(activity!=null) { var a=activity; runOnMainSync(a::finish); } context.getSharedPreferences("library",0).edit().putString("items",prior).commit(); target.delete(); }
    }
    private void checkLargeCard040(Api api) throws Exception {
        var context=getTargetContext(); var prefs=context.getSharedPreferences("settings",0);
        boolean external=prefs.getBoolean("external_player",false), ask=prefs.getBoolean("ask_audio",false);
        String component=prefs.getString("external_component","");
        MainActivity activity=null; PlayerActivity playerActivity=null;
        String mid="1ea5a5a915c02e3e6f69dfb1", rid="kinozal:1475843";
        var launchMonitor=addMonitor(MainActivity.class.getName(),null,false);
        try(var output=getUiAutomation().executeShellCommand("am start -n local.mapp/.MainActivity"); var input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(output)) { byte[] bytes=new byte[1024]; while(input.read(bytes)!=-1) { } }
        activity=(MainActivity)waitForMonitorWithTimeout(launchMonitor,15000); removeMonitor(launchMonitor);
        if(activity==null) throw new AssertionError("Phone must be unlocked for the test");
        JSONObject card=api.request("GET","/v1/movies/"+mid,null);
        // Force a state larger than Binder's limit even if the server cache changes.
        card.put("state_stress","x".repeat(1100000));
        JSONObject target=null; var works=card.getJSONArray("works");
        for(int i=0;i<works.length();i++) {
            JSONObject w=works.getJSONObject(i); if(w.optInt("season")!=1 || w.optInt("number")!=1) continue;
            var sources=w.getJSONArray("sources"); for(int j=0;j<sources.length();j++) if(rid.equals(sources.getJSONObject(j).optString("release"))) target=w;
        }
        if(target==null) throw new AssertionError("Requested Game of Thrones release/episode absent");
        final String episode=target.getString("id");
        var current=MainActivity.class.getDeclaredField("currentMovie"); current.setAccessible(true);
        var chosen=MainActivity.class.getDeclaredField("chosenFile"); chosen.setAccessible(true);
        var screen=MainActivity.class.getDeclaredField("screen"); screen.setAccessible(true);
        try {
            prefs.edit().putBoolean("external_player",false).putBoolean("ask_audio",false).commit();
            final MainActivity initial=activity;
            runOnMainSync(()->{ try {
                var row=MainActivity.class.getDeclaredMethod("movieRow",JSONObject.class,Runnable.class); row.setAccessible(true);
                var view=(android.view.View)row.invoke(initial,new JSONObject().put("id",mid).put("title","Игра престолов").put("series",true), (Runnable)()->{});
                if(find(view,v->v instanceof android.widget.TextView && "Сериал".contentEquals(((android.widget.TextView)v).getText()))==null) throw new AssertionError("Series search label missing");
                var page=MainActivity.class.getDeclaredMethod("page",String.class,String.class); page.setAccessible(true); page.invoke(initial,"movie","Карточка фильма");
                var release=MainActivity.class.getDeclaredField("preferredRelease"); release.setAccessible(true); release.set(initial,rid);
                var work=MainActivity.class.getDeclaredField("preferredEpisode"); work.setAccessible(true); work.set(initial,episode);
                var show=MainActivity.class.getDeclaredMethod("showMovie",JSONObject.class); show.setAccessible(true); show.invoke(initial,card);
            } catch(Exception e) { throw new RuntimeException(e); } });
            waitForIdleSync();
            Bundle saved=new Bundle();
            runOnMainSync(()->initial.onSaveInstanceState(saved));
            android.os.Parcel parcel=android.os.Parcel.obtain();
            try { parcel.writeBundle(saved); if(parcel.dataSize()>65536) throw new AssertionError("Oversized saved state: "+parcel.dataSize()); }
            finally { parcel.recycle(); }
            if(saved.containsKey("card") || saved.containsKey("pendingBatch")) throw new AssertionError("Large payload in Binder state");
            if(ActivitySnapshot.read(context,saved.getString("snapshot")).getJSONObject("card").getString("state_stress").length()!=1100000) throw new AssertionError("Snapshot truncated");
            var monitor=addMonitor(MainActivity.class.getName(),null,false);
            runOnMainSync(initial::recreate);
            activity=(MainActivity)waitForMonitorWithTimeout(monitor,15000); removeMonitor(monitor);
            if(activity==null) throw new AssertionError("Recreation failed");
            waitForIdleSync();
            if(!mid.equals(((JSONObject)current.get(activity)).optString("id"))) throw new AssertionError("Recreation lost card");
            if(!rid.equals(((JSONObject)chosen.get(activity)).optString("release"))) throw new AssertionError("Recreation lost selected source");
            final MainActivity restored=activity;
            var playerMonitor=addMonitor(PlayerActivity.class.getName(),null,false);
            runOnMainSync(()->find(restored.getWindow().getDecorView(),v->"card-watch".equals(v.getTag())).performClick());
            playerActivity=(PlayerActivity)waitForMonitorWithTimeout(playerMonitor,90000); removeMonitor(playerMonitor);
            if(playerActivity==null) throw new AssertionError("Internal player did not open");
            var playerField=PlayerActivity.class.getDeclaredField("player"); playerField.setAccessible(true);
            var player=(org.videolan.libvlc.MediaPlayer)playerField.get(playerActivity);
            long until=System.currentTimeMillis()+60000;
            while(player.getTime()<5000 && System.currentTimeMillis()<until) Thread.sleep(200);
            var media=player.getMedia(); var stats=media.getStats(); media.release();
            if(player.getTime()<5000 || stats==null || stats.displayedPictures<5) throw new AssertionError("Internal video did not decode");
            final PlayerActivity playing=playerActivity; runOnMainSync(playing::finish); playerActivity=null;
            Thread.sleep(1500); waitForIdleSync();
            if(!"movie".equals(screen.get(restored))) throw new AssertionError("Internal return lost card");
            prefs.edit().putBoolean("external_player",true).putString("external_component","org.videolan.vlc/org.videolan.vlc.StartActivity").commit();
            runOnMainSync(()->find(restored.getWindow().getDecorView(),v->"card-watch".equals(v.getTag())).performClick());
            until=System.currentTimeMillis()+60000; boolean launched=false;
            while(System.currentTimeMillis()<until) { var root=getUiAutomation().getRootInActiveWindow(); if(root!=null && "org.videolan.vlc".contentEquals(root.getPackageName())) { launched=true; break; } Thread.sleep(200); }
            if(!launched) throw new AssertionError("External VLC did not open");
            Thread.sleep(15000);
            try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"got040-external.png"))) { getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); }
            var root=getUiAutomation().getRootInActiveWindow();
            if(root==null || !"org.videolan.vlc".contentEquals(root.getPackageName())) throw new AssertionError("External VLC exited unexpectedly");
            getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            Thread.sleep(2000); waitForIdleSync();
            if(!"movie".equals(screen.get(restored)) || restored.isDestroyed()) throw new AssertionError("External return lost activity");
        } finally {
            prefs.edit().putBoolean("external_player",external).putBoolean("ask_audio",ask).putString("external_component",component).commit();
            if(playerActivity!=null) { final PlayerActivity p=playerActivity; runOnMainSync(p::finish); }
            if(activity!=null && current.get(activity) instanceof JSONObject data) data.remove("state_stress");
            // Leave the real card available for the user's follow-up checks.
        }
    }
    private void checkDownloadBinder041(Api api) throws Exception {
        var context=getTargetContext(); var ids=new java.util.ArrayList<String>();
        var activity=(MainActivity)startActivitySync(new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            JSONObject card=new JSONObject().put("description","x".repeat(1100000));
            for(int n=0;n<3;n++) {
                String id="binder041-"+java.util.UUID.randomUUID(); ids.add(id);
                JSONObject item=new JSONObject().put("id",id).put("title","Binder regression").put("server",api.server)
                    .put("uri","file:///unused-binder-test").put("size",1).put("state","queued").put("card",card);
                Library.put(context,item); DownloadService.paused.add(id);
                var intent=Library.itemIntent(context,DownloadService.class,item);
                var parcel=android.os.Parcel.obtain();
                try { intent.writeToParcel(parcel,0); if(parcel.dataSize()>4096) throw new AssertionError("Large service Intent"); }
                finally { parcel.recycle(); }
                runOnMainSync(()->activity.startForegroundService(intent));
            }
            long until=System.currentTimeMillis()+20000; int ready=0;
            while(System.currentTimeMillis()<until) {
                ready=0; var items=Library.all(context);
                for(int i=0;i<items.length();i++) { var item=items.getJSONObject(i); if(ids.contains(item.optString("id")) && item.optString("state").equals("paused")) {
                    if(!item.optString("error").equals("Загрузка остановлена")) throw new AssertionError("Worker did not resolve item: "+item.optString("error"));
                    if(item.getJSONObject("card").getString("description").length()!=1100000) throw new AssertionError("Lost large card");
                    ready++;
                } }
                if(ready==3 && java.util.Collections.disjoint(DownloadService.queued,ids)) break;
                Thread.sleep(100);
            }
            if(ready!=3) throw new AssertionError("Season-sized queue did not reach workers");
            JSONObject saved=Library.intentItem(context,new android.content.Intent().putExtra("item_id",ids.get(0)),"local_item");
            if(saved.getJSONObject("card").getString("description").length()!=1100000) throw new AssertionError("Player lost saved metadata");
            Library.remove(context,ids.get(0));
            try { Library.intentItem(context,new android.content.Intent().putExtra("item_id",ids.get(0)),"item"); throw new AssertionError("Deleted item resurrected"); }
            catch(java.io.IOException expected) { }
        } finally {
            for(String id:ids) { Library.remove(context,id); DownloadService.paused.remove(id); }
            runOnMainSync(activity::finish);
        }
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            var context=getTargetContext();
            if(arguments!=null && arguments.containsKey("phoneDecoder")) {
                checkPhoneDecoder(); result.putString("stream","PASS: completed Xvid uses software policy and displays target scene; user history preserved");
                finish(Activity.RESULT_OK,result); return;
            }
            Api api=new Api(context);
            if(arguments!=null && arguments.containsKey("binder041")) {
                checkDownloadBinder041(api); result.putString("stream","PASS: three large-card downloads cross real service Binder by ID; saved playback lookup; deleted item rejected");
                finish(Activity.RESULT_OK,result); return;
            }
            if(arguments!=null && arguments.containsKey("largeCard040")) {
                checkLargeCard040(api); result.putString("stream","PASS: series label, small Binder state, large-card recreation, real GOT internal decoding and external VLC return");
                finish(Activity.RESULT_OK,result); return;
            }
            if(arguments!=null && arguments.containsKey("localAvi034")) {
                checkLocalAvi034(api); result.putString("stream","PASS: incomplete AVI plays exclusively from saved prefix without remote tail/index");
                finish(Activity.RESULT_OK,result); return;
            }
            if(arguments!=null && arguments.containsKey("feedback034")) {
                check034(api); result.putString("stream","PASS: local-only reads, exactly two actions after source switches, 9/10 percent gate, stop retains file, season completeness");
                finish(Activity.RESULT_OK,result); return;
            }
            integrityChecks();
            if(arguments!=null && arguments.containsKey("seasons033")) {
                checkSeasons033(api); result.putString("stream","PASS: multi-season pack splits episodes, selected season downloads, all seasons queue without duplicate episodes");
                finish(Activity.RESULT_OK,result); return;
            }
            if(arguments!=null && arguments.containsKey("external033")) {
                checkExternal033(api); result.putString("stream","PASS: external VLC plays incomplete AVI via buffered HTTP and returns position to movie card");
                finish(Activity.RESULT_OK,result); return;
            }
            if(arguments!=null && arguments.containsKey("feedback033")) {
                check033(api); result.putString("stream","PASS: two-level series menu, season download queue, individual episode labels/delete buttons, local HTTP prefix and concurrent playback checkpoint");
                finish(Activity.RESULT_OK,result); return;
            }
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
