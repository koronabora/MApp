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
    private android.view.View find(android.view.View root,java.util.function.Predicate<android.view.View> match) {
        if(match.test(root)) return root;
        if(root instanceof android.view.ViewGroup group) for(int i=0;i<group.getChildCount();i++) { var result=find(group.getChildAt(i),match); if(result!=null) return result; }
        return null;
    }
    private void checkCard(Api api) throws Exception {
        var context=getTargetContext(); String folder=context.getSharedPreferences("settings",0).getString("folder","");
        var tree=android.net.Uri.parse(folder); var uri=FolderLibrary.create(context,tree,"MApp-card-test-"+java.util.UUID.randomUUID()+".mp4");
        JSONObject item=new JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("movie","card-test").put("release","local:8").put("uri",uri.toString()).put("server",api.server).put("state","downloading");
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
        JSONObject state=api.request("GET","/v1/torrents/"+key,null);
        long deadline=System.currentTimeMillis()+60000;
        while(!state.optBoolean("ready") && System.currentTimeMillis()<deadline) { Thread.sleep(500); state=api.request("GET","/v1/torrents/"+key,null); }
        if(!state.optBoolean("ready")) throw new AssertionError("Fixture buffer not ready");
        if(release.equals("local:2") && state.optDouble("progress")>=1) throw new AssertionError("Fixture must be incomplete");
        JSONObject file=state.getJSONArray("files").getJSONObject(0);
        var uri=MediaProvider.create(context,"/v1/torrents/"+key+"/files/"+file.getInt("index"),file.getLong("size"),file.getString("name"));
        var activity=(PlayerActivity)startActivitySync(new android.content.Intent(context,PlayerActivity.class).setData(uri).putExtra("position",2000L).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            var field=PlayerActivity.class.getDeclaredField("player"); field.setAccessible(true);
            var player=(org.videolan.libvlc.MediaPlayer)field.get(activity);
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
            if(!tailPlayed) throw new AssertionError("Seek did not display tail frames");
            var audio=player.getAudioTracks();
            if(audio!=null && audio.length>1) { int track=audio[audio.length-1].id; runOnMainSync(()->player.setAudioTrack(track)); Thread.sleep(250); if(player.getAudioTrack()!=track) throw new AssertionError("Audio selection failed"); }
            if(release.equals("local:2") && api.request("GET","/v1/torrents/"+key,null).optDouble("progress")>=1) throw new AssertionError("Incomplete playback was not exercised");
        } finally { runOnMainSync(activity::finish); }
    }
    @Override public void onCreate(Bundle args) { arguments=args; super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            var context=getTargetContext();
            Api api=new Api(context);
            integrityChecks();
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
