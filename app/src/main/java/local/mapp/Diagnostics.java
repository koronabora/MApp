package local.mapp;

import android.content.*;
import android.net.Uri;
import android.provider.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

/** Opt-in, bounded diagnostics in the video folder. No keys, URLs or request bodies. */
final class Diagnostics {
    private static final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private static final StringBuilder pending=new StringBuilder();
    private static Context context;
    private static String content="", location="", uri="";
    private static boolean scheduled;
    static synchronized void init(Context value) {
        context=value.getApplicationContext();
        if(!scheduled) {
            scheduled=true; worker.scheduleWithFixedDelay(Diagnostics::flush,1,2,TimeUnit.SECONDS);
            var previous=Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
                event("crash",error.getClass().getSimpleName());
                for(StackTraceElement frame:error.getStackTrace()) event("stack",frame.toString());
                try { worker.submit(Diagnostics::flush).get(2,TimeUnit.SECONDS); } catch(Exception ignored) { }
                if(previous!=null) previous.uncaughtException(thread,error);
            });
        }
    }
    static synchronized void event(String category,String detail) {
        if(context==null || !context.getSharedPreferences("settings",0).getBoolean("diagnostics",false)) return;
        if(pending.length()>65536) return;
        pending.append(java.time.Instant.now()).append(' ').append(category).append(' ').append(detail).append('\n');
    }
    static void enable(Context c,boolean enabled,java.util.function.Consumer<String> result) {
        init(c); c.getSharedPreferences("settings",0).edit().putBoolean("diagnostics",enabled).apply();
        worker.execute(()->{
            if(enabled) {
                event("session","MApp 0.3.7 Android="+android.os.Build.VERSION.SDK_INT+" model="+android.os.Build.MODEL);
                flush(); result.accept(uri.isEmpty()?"Не удалось создать лог в папке видео":"Логи записываются в MApp-debug.txt в папке видео");
            } else {
                boolean failed=false; JSONArray retained=new JSONArray();
                try { JSONArray files=new JSONArray(c.getSharedPreferences("settings",0).getString("diagnostic_files","[]"));
                    for(int i=0;i<files.length();i++) { String value=files.getString(i); Uri file=Uri.parse(value);
                        try { if(DocumentsContract.isDocumentUri(c,file)) DocumentsContract.deleteDocument(c.getContentResolver(),file); else c.getContentResolver().delete(file,null,null); }
                        catch(Exception e) {
                            boolean missing=e instanceof java.io.FileNotFoundException || (e instanceof IllegalArgumentException && String.valueOf(e.getMessage()).contains("java.io.FileNotFoundException"));
                            if(!missing) { failed=true; retained.put(value); }
                        }
                    }
                } catch(Exception e) { failed=true; }
                synchronized(Diagnostics.class) { pending.setLength(0); }
                content=""; location=""; uri="";
                c.getSharedPreferences("settings",0).edit().putString("diagnostic_files",retained.toString()).remove("diagnostic_uri").remove("diagnostic_folder").apply();
                result.accept(failed?"Запись выключена. Для удаления части логов восстановите доступ к прежней папке и выключите настройку повторно.":"Логирование выключено, логи удалены");
            }
        });
    }
    private static void flush() {
        Context c=context; if(c==null) return;
        var prefs=c.getSharedPreferences("settings",0);
        if(!prefs.getBoolean("diagnostics",false)) return;
        String batch;
        synchronized(Diagnostics.class) { if(pending.length()==0) return; batch=pending.toString(); pending.setLength(0); }
        try {
            String folder=prefs.getString("folder","");
            if(uri.isEmpty() || !folder.equals(location)) {
                content=""; location=folder;
                uri=folder.equals(prefs.getString("diagnostic_folder",null))?prefs.getString("diagnostic_uri",""):"";
                if(uri.isEmpty()) {
                    Uri created;
                    if(folder.isEmpty()) {
                        ContentValues values=new ContentValues(); values.put(MediaStore.Downloads.DISPLAY_NAME,"MApp-debug.txt"); values.put(MediaStore.Downloads.MIME_TYPE,"text/plain"); values.put(MediaStore.Downloads.RELATIVE_PATH,"Download/MApp");
                        created=c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
                    } else { Uri tree=Uri.parse(folder); created=DocumentsContract.createDocument(c.getContentResolver(),DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree)),"text/plain","MApp-debug.txt"); }
                    if(created==null) throw new java.io.IOException(); uri=created.toString();
                    JSONArray files=new JSONArray(prefs.getString("diagnostic_files","[]")); files.put(uri);
                    prefs.edit().putString("diagnostic_files",files.toString()).putString("diagnostic_uri",uri).putString("diagnostic_folder",folder).apply();
                } else try(var in=c.getContentResolver().openInputStream(Uri.parse(uri))) { content=new String(Api.read(in,3*1024*1024),StandardCharsets.UTF_8); }
            }
            content+=batch; if(content.length()>512000) content=content.substring(content.length()-384000);
            try(var out=c.getContentResolver().openOutputStream(Uri.parse(uri),"wt")) { if(out==null) throw new java.io.IOException(); out.write(content.getBytes(StandardCharsets.UTF_8)); }
        } catch(Exception e) { uri=""; prefs.edit().remove("diagnostic_uri").apply(); }
    }
}
