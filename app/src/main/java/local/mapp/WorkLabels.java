package local.mapp;

import java.util.regex.Pattern;
import org.json.JSONObject;

/** Presentation only: torrent paths and technical names are never card labels. */
final class WorkLabels {
    static java.util.ArrayList<JSONObject> collection(java.util.List<JSONObject> works) {
        var result=new java.util.ArrayList<JSONObject>();
        var numbered=new java.util.LinkedHashMap<Integer,JSONObject>();
        try {
            for(JSONObject original:works) {
                int number=number(original); JSONObject group=numbered.get(number);
                if(number==Integer.MAX_VALUE || group==null) {
                    group=new JSONObject(original.toString());
                    group.put("member_ids",new org.json.JSONArray().put(original.optString("id")));
                    result.add(group);
                    if(number!=Integer.MAX_VALUE) numbered.put(number,group);
                } else {
                    group.getJSONArray("member_ids").put(original.optString("id"));
                    group.put("last_watched",group.optBoolean("last_watched") || original.optBoolean("last_watched"));
                    var sources=original.optJSONArray("sources");
                    if(sources!=null) for(int i=0;i<sources.length();i++) group.getJSONArray("sources").put(sources.getJSONObject(i));
                }
            }
        } catch(org.json.JSONException e) { throw new IllegalArgumentException(e); }
        result.sort(java.util.Comparator.comparingInt(WorkLabels::number));
        return result;
    }
    static boolean matches(JSONObject work,String id) {
        if(work.optString("id").equals(id)) return true;
        var members=work.optJSONArray("member_ids");
        if(members!=null) for(int i=0;i<members.length();i++) if(members.optString(i).equals(id)) return true;
        return false;
    }
    private static final Pattern EPISODE=Pattern.compile("(?iu)(?:эпизод|episode|epizod)[ ._:-]+(\\d{1,3}|VIII|VII|VI|IV|IX|III|II|V|I|X)(?=[ ._:-]|$)");
    static int number(JSONObject work) {
        if(!work.isNull("number")) return work.optInt("number");
        String name=work.optString("name").replace('\\','/');
        name=name.substring(name.lastIndexOf('/')+1).replaceFirst("(?i)\\.(mkv|mp4|avi|m4v|mov|ts|m2ts|webm|wmv)$","");
        var episode=EPISODE.matcher(name);
        if(episode.find()) {
            String value=episode.group(1).toUpperCase(java.util.Locale.ROOT);
            String[] roman={"I","II","III","IV","V","VI","VII","VIII","IX","X"};
            for(int i=0;i<roman.length;i++) if(roman[i].equals(value)) { value=String.valueOf(i+1); break; }
            return Integer.parseInt(value);
        }
        return Integer.MAX_VALUE;
    }
    static String label(JSONObject work,boolean series,int index) {
        int number=number(work);
        if(number!=Integer.MAX_VALUE) return String.valueOf(number);
        // No invented episode number when the manifest could not establish identity.
        if(series) return "Неопределённая серия · вариант "+(index+1);
        return "Без номера · вариант "+(index+1);
    }
}
