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
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            var context=getTargetContext();
            Api api=new Api(context);
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
            result.putString("stream","PASS: TLS pin, full stream checksum, seeking; logout clears credentials and stream access, preserves library");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {
            result.putString("stream","FAIL: "+e); finish(Activity.RESULT_CANCELED,result);
        }
    }
}
