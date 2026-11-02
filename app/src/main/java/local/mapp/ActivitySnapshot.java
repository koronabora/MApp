package local.mapp;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Large activity data stays in private storage, never in a Binder Bundle. */
final class ActivitySnapshot {
    private static AtomicFile file(Context context,String key) {
        if(!key.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid snapshot key");
        return new AtomicFile(new File(context.getCacheDir(),"activity-"+key+".json"));
    }
    static void write(Context context,String key,JSONObject data) throws Exception {
        AtomicFile file=file(context,key); FileOutputStream output=null;
        try {
            output=file.startWrite(); output.write(data.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch(Exception error) { if(output!=null) file.failWrite(output); throw error; }
    }
    static JSONObject read(Context context,String key) {
        try { return new JSONObject(new String(file(context,key).readFully(),StandardCharsets.UTF_8)); }
        catch(Exception ignored) { return new JSONObject(); }
    }
}
