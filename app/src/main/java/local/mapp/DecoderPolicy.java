package local.mapp;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.util.Locale;

/** MPEG-4 Part 2 can render corrupt frames through Android's MediaCodec decoder. */
final class DecoderPolicy {
    static String name(Context context,Uri uri) {
        try {
            if("local.mapp.media".equals(uri.getAuthority())) return MediaProvider.describe(context,uri).optString("name");
            var items=Library.all(context);
            for(int i=0;i<items.length();i++) if(uri.toString().equals(items.optJSONObject(i).optString("uri"))) return items.optJSONObject(i).optString("name");
        } catch(Exception ignored) { }
        return uri.getLastPathSegment()==null?"":uri.getLastPathSegment();
    }
    static boolean software(Context context,String name) {
        String mode=context.getSharedPreferences("settings",0).getString("decoder","auto");
        if(mode.equals("software")) return true;
        if(mode.equals("system")) return false;
        return name.toLowerCase(Locale.ROOT).matches(".*(?:xvid|divx|mpeg[ ._-]?4).*" );
    }
}
