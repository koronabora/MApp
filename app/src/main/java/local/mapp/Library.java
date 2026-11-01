package local.mapp;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

final class Library {
    static synchronized JSONArray all(Context c) {
        try { return new JSONArray(c.getSharedPreferences("library",0).getString("items","[]")); }
        catch(Exception e) { return new JSONArray(); }
    }
    static synchronized void put(Context c, JSONObject item) {
        JSONArray old=all(c), result=new JSONArray();
        for(int i=0;i<old.length();i++) if(!old.optJSONObject(i).optString("id").equals(item.optString("id"))) result.put(old.optJSONObject(i));
        result.put(item);
        c.getSharedPreferences("library",0).edit().putString("items",result.toString()).commit();
    }
    static synchronized void remove(Context c, String id) {
        JSONArray old=all(c), result=new JSONArray();
        for(int i=0;i<old.length();i++) if(!old.optJSONObject(i).optString("id").equals(id)) result.put(old.optJSONObject(i));
        c.getSharedPreferences("library",0).edit().putString("items",result.toString()).commit();
    }
}
