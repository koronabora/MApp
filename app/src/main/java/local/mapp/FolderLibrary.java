package local.mapp;

import android.content.*;
import android.net.Uri;
import android.provider.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** A folder is inspected only after an explicit SAF selection. Sidecars carry no credentials. */
final class FolderLibrary {
    static Uri create(Context context,Uri tree,String name) throws Exception {
        return DocumentsContract.createDocument(context.getContentResolver(),DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree)),"video/*",name);
    }
    static void backup(Context context,JSONObject item) {
        synchronized(Library.class) {
        if(DownloadService.cancelled.contains(item.optString("id"))) return;
        try {
            Uri sidecar;
            if(item.optString("sidecar").isEmpty()) {
                String name=item.optString("name")+".mapp.json";
                if(!item.optString("folder").isEmpty()) {
                    Uri tree=Uri.parse(item.getString("folder"));
                    sidecar=DocumentsContract.createDocument(context.getContentResolver(),DocumentsContract.buildDocumentUriUsingTree(tree,item.optString("parent_id",DocumentsContract.getTreeDocumentId(tree))),"application/json",name);
                } else if(item.optBoolean("mediastore")) {
                    ContentValues values=new ContentValues(); values.put(MediaStore.Downloads.DISPLAY_NAME,name); values.put(MediaStore.Downloads.MIME_TYPE,"application/json"); values.put(MediaStore.Downloads.RELATIVE_PATH,"Download/MApp");
                    sidecar=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
                } else return;
                if(sidecar==null) return; item.put("sidecar",sidecar.toString()); Library.put(context,item);
            } else sidecar=Uri.parse(item.getString("sidecar"));
            JSONObject copy=new JSONObject(item.toString()); copy.remove("uri"); copy.remove("sidecar"); copy.remove("folder"); copy.remove("mediastore"); copy.put("format",1);
            try(var out=context.getContentResolver().openOutputStream(sidecar,"wt")) {
                if(out!=null) out.write(copy.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch(Exception ignored) { /* Video remains usable if its provider disallows sidecars. */ }
        }
    }
    static int restore(Context context,Uri tree) throws Exception {
        int restored=scan(context,tree,DocumentsContract.getTreeDocumentId(tree),new HashSet<>());
        // Old MApp versions wrote no sidecars. Associate only unambiguous cached titles.
        try {
            Api api=new Api(context); JSONArray items=Library.all(context);
            for(int i=0;i<items.length();i++) {
                JSONObject item=items.optJSONObject(i);
                if(!item.optString("folder").equals(tree.toString()) || !item.optString("movie").startsWith("local-file:")) continue;
                JSONObject movie=api.request("GET","/v1/library/match?name="+Uri.encode(item.getString("name")),null).optJSONObject("movie");
                if(movie!=null) { item.put("movie",movie.getString("id")).put("title",movie.getString("title")).put("year",movie.optString("year")).put("director",movie.optString("director")).put("card",movie).put("server",api.server); Library.put(context,item); }
            }
        } catch(Exception ignored) { /* Offline recovery still succeeds from local files and sidecars. */ }
        return restored;
    }
    private static int scan(Context context,Uri tree,String parent,Set<String> seen) throws Exception {
        if(!seen.add(parent)) return 0;
        ArrayList<JSONObject> files=new ArrayList<>(); ArrayList<String> folders=new ArrayList<>(); Map<String,JSONObject> manifests=new HashMap<>(); Map<String,Uri> sidecars=new HashMap<>();
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent);
        try(var cursor=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE},null,null,null)) {
            if(cursor==null) return 0;
            while(cursor.moveToNext()) {
                String id=cursor.getString(0), name=cursor.getString(1), type=cursor.getString(2); Uri uri=DocumentsContract.buildDocumentUriUsingTree(tree,id);
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) { folders.add(id); continue; }
                if(name.endsWith(".mapp.json")) {
                    try(var input=context.getContentResolver().openInputStream(uri)) {
                        JSONObject record=new JSONObject(new String(Api.read(input,1024*1024),StandardCharsets.UTF_8));
                        if(record.optInt("format")==1) { manifests.put(record.optString("name"),record); sidecars.put(record.optString("name"),uri); }
                    } catch(Exception ignored) { }
                } else if((type!=null && type.startsWith("video/")) || name.matches("(?i).*\\.(mkv|avi|mp4|m4v|mov|ts|webm)$"))
                    files.add(new JSONObject().put("uri",uri.toString()).put("name",name).put("size",cursor.getLong(3)));
            }
        }
        int restored=0;
        for(JSONObject file:files) {
            String name=file.getString("name"); JSONObject record=manifests.get(name);
            boolean known=false; JSONArray existing=Library.all(context);
            for(int i=0;i<existing.length();i++) {
                JSONObject old=existing.optJSONObject(i);
                if(old.optString("uri").equals(file.getString("uri")) || (record!=null && old.optString("id").equals(record.optString("id")))) { known=true; break; }
            }
            if(known) continue;
            if(record==null) record=fromName(name,file.getLong("size"));
            else {
                long size=file.getLong("size"), expected=record.optLong("size");
                boolean complete=expected>0 && size==expected && record.optString("state").equals("complete");
                record.put("state",complete?"complete":"paused").put("downloaded",Math.min(size,record.optLong("downloaded")));
                record.put("sidecar",sidecars.get(name).toString());
            }
            record.put("uri",file.getString("uri")).put("folder",tree.toString()).put("parent_id",parent);
            Library.put(context,record); restored++;
        }
        for(String folder:folders) restored+=scan(context,tree,folder,seen);
        return restored;
    }
    static JSONObject fromName(String name,long size) throws Exception {
        String title=name.replaceFirst("(?i)\\.(mkv|avi|mp4|m4v|mov|ts|webm)$","");
        Matcher episode=Pattern.compile("(?i)[ ._-]*(?:s\\d{1,3}e\\d{1,3}|\\d{1,2}x\\d{1,3}).*").matcher(title);
        boolean series=episode.find(); if(series) title=title.substring(0,episode.start());
        Matcher year=Pattern.compile("\\b(?:19|20)\\d{2}\\b").matcher(title); String y=year.find()?year.group():"";
        String clean=title.replaceFirst("[\\[(].*","").replace('.', ' ').trim();
        String mid="local-file:"+UUID.nameUUIDFromBytes((clean+":"+y).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("id",UUID.randomUUID().toString()).put("movie",mid).put("title",clean).put("year",y).put("name",name).put("size",size).put("downloaded",size).put("state","complete").put("series",series);
    }
    static boolean exists(Context context,JSONObject item) {
        try(var cursor=context.getContentResolver().query(Uri.parse(item.getString("uri")),new String[]{OpenableColumns.SIZE},null,null,null)) { return cursor!=null && cursor.moveToFirst(); }
        catch(Exception ignored) { return false; }
    }
    static void remove(Context context,JSONObject item) throws Exception {
        Uri uri=Uri.parse(item.getString("uri"));
        if(exists(context,item)) {
            if(DocumentsContract.isDocumentUri(context,uri)) { if(!DocumentsContract.deleteDocument(context.getContentResolver(),uri)) throw new java.io.IOException("Не удалось удалить файл"); }
            else if(context.getContentResolver().delete(uri,null,null)==0) throw new java.io.IOException("Не удалось удалить файл");
        }
        if(!item.optString("sidecar").isEmpty()) try {
            Uri sidecar=Uri.parse(item.getString("sidecar"));
            if(DocumentsContract.isDocumentUri(context,sidecar)) DocumentsContract.deleteDocument(context.getContentResolver(),sidecar); else context.getContentResolver().delete(sidecar,null,null);
        } catch(Exception ignored) { }
        Library.remove(context,item.getString("id"));
    }
}
