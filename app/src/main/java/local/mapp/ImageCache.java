package local.mapp;

import android.content.Context;
import android.graphics.BitmapFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class ImageCache {
    private static File file(Context c,String server,String path) {
        File root=new File(c.getCacheDir(),"posters"); root.mkdirs();
        return new File(root,UUID.nameUUIDFromBytes((server+path).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
    }
    static byte[] get(Context c,String server,String path) throws IOException {
        File target=file(c,server,path); return target.exists()?Files.readAllBytes(target.toPath()):null;
    }
    static void put(Context c,String server,String path,byte[] bytes) throws IOException {
        File target=file(c,server,path), temp=File.createTempFile("poster", ".tmp",target.getParentFile());
        try { Files.write(temp.toPath(),bytes); Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING); }
        finally { temp.delete(); }
        File[] files=target.getParentFile().listFiles();
        if(files!=null && files.length>128) { Arrays.sort(files,Comparator.comparingLong(File::lastModified)); for(int i=0;i<files.length-128;i++) files[i].delete(); }
    }
    static String thumbnail(Context c,String server,String mid) {
        try {
            byte[] bytes=get(c,server,"/v1/movies/"+mid+"/poster"); if(bytes==null) return "";
            var bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length); if(bitmap==null) return "";
            if(bitmap.getWidth()<180 || bitmap.getHeight()<240 || bitmap.getWidth()>bitmap.getHeight()*1.35) { bitmap.recycle(); return ""; }
            int width=Math.min(200,bitmap.getWidth()), height=Math.max(1,bitmap.getHeight()*width/bitmap.getWidth());
            var small=android.graphics.Bitmap.createScaledBitmap(bitmap,width,height,true); ByteArrayOutputStream out=new ByteArrayOutputStream(); small.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,out);
            if(small!=bitmap) small.recycle(); bitmap.recycle();
            return android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP);
        } catch(Exception ignored) { return ""; }
    }
}
