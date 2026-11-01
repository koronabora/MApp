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
    static synchronized void downloadState(Context c,JSONObject item) {
        JSONArray current=all(c);
        for(int i=0;i<current.length();i++) { JSONObject prior=current.optJSONObject(i); if(prior.optString("id").equals(item.optString("id")))
            for(String field:new String[]{"position","duration","completed"}) if(prior.has(field)) try { item.put(field,prior.get(field)); } catch(Exception ignored) { }
        }
        put(c,item);
    }
    static synchronized void position(Context c,String uri,long position) {
        progress(c,uri,position,0,false);
    }
    static synchronized void progress(Context c,String uri,long position,long duration,boolean completed) {
        JSONArray items=all(c);
        for(int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i);
            if(uri.equals(item.optString("uri"))) try { item.put("position",position).put("duration",duration).put("completed",completed); put(c,item); } catch(Exception ignored) { }
        }
    }
    static JSONArray grouped(Context c) {
        java.util.LinkedHashMap<String,JSONObject> groups=new java.util.LinkedHashMap<>(); JSONArray items=all(c);
        for(int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i); String group=item.optString("server")+"|"+item.optString("movie",item.optString("title"));
            JSONObject prior=groups.get(group);
            if(prior==null || (!item.optString("state").equals("complete") && prior.optString("state").equals("complete"))) groups.put(group,item);
        }
        java.util.ArrayList<JSONObject> sorted=new java.util.ArrayList<>(groups.values());
        sorted.sort(java.util.Comparator.comparing((JSONObject item)->item.optString("state").equals("complete"))
            .thenComparing(item->item.optString("title"),java.text.Collator.getInstance(new java.util.Locale("ru"))));
        JSONArray result=new JSONArray(); for(JSONObject item:sorted) result.put(item); return result;
    }
}
