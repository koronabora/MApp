package local.mapp;

import android.content.Context;
import org.json.JSONObject;

/** Local checkpoint survives activity recreation and an unavailable history API. */
final class WatchState {
    private static String key(Context c,String movie,String episode,String torrent,int file) {
        String account=""; try { account=Vault.sessionIdentity(c); } catch(Exception ignored) { }
        return account+":"+movie+":"+(!episode.isEmpty()?episode:torrent+":"+file);
    }
    static void save(Context c,String movie,String episode,String torrent,int file,long position) {
        var edit=c.getSharedPreferences("watch-state",0).edit().putLong(key(c,movie,episode,torrent,file),position);
        if(!episode.isEmpty()) edit.putString(key(c,movie,"last-episode","",0),episode);
        edit.commit();
    }
    static String lastEpisode(Context c,String movie) { return c.getSharedPreferences("watch-state",0).getString(key(c,movie,"last-episode","",0),""); }
    static long position(Context c,String movie,String episode,String torrent,int file,long fallback) {
        return c.getSharedPreferences("watch-state",0).getLong(key(c,movie,episode,torrent,file),fallback);
    }
}
