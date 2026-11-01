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
    private void integrityChecks() throws Exception {
        byte[] payload={1,2,3,4}; StringBuilder hex=new StringBuilder();
        for(byte value:MessageDigest.getInstance("SHA-256").digest(payload)) hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        String hash=hex.toString();
        VerifiedChunk.validate(payload,"bytes 10-13/20",hash,10,13,20);
        VerifiedChunk.Fetch[] invalid={
            ()->VerifiedChunk.validate(new byte[]{1,2,3,5},"bytes 10-13/20",hash,10,13,20),
            ()->VerifiedChunk.validate(payload,"bytes 11-14/20",hash,10,13,20),
            ()->VerifiedChunk.validate(new byte[]{1,2,3},"bytes 10-13/20",hash,10,13,20),
            ()->VerifiedChunk.validate(payload,"bytes 10-13/20",null,10,13,20)};
        for(var check:invalid) {
            boolean denied=false; try { check.run(); } catch(java.io.IOException expected) { denied=true; }
            if(!denied) throw new AssertionError("Invalid chunk accepted");
        }
        int[] attempts={0};
        byte[] recovered=VerifiedChunk.retry(()->{ if(++attempts[0]<3) return invalid[0].run(); return payload; });
        if(attempts[0]!=3 || !Arrays.equals(recovered,payload)) throw new AssertionError("Chunk retry failed");
        attempts[0]=0; boolean denied=false;
        try { VerifiedChunk.retry(()->{ attempts[0]++; return invalid[0].run(); }); } catch(java.io.IOException expected) { denied=true; }
        if(!denied || attempts[0]!=3) throw new AssertionError("Retry limit failed");
    }
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments=args; super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            var context=getTargetContext();
            Api api=new Api(context);
            integrityChecks();
            if(arguments!=null && arguments.containsKey("torrent")) {
                String torrent=arguments.getString("torrent");
                JSONObject media=api.request("GET","/v1/torrents/"+torrent,null).getJSONArray("files").getJSONObject(0);
                long length=media.getLong("size");
                String mediaPath="/v1/torrents/"+torrent+"/files/"+media.getInt("index");
                var mediaUri=MediaProvider.create(context,mediaPath,length,media.getString("name"));
                try(var descriptor=context.getContentResolver().openFileDescriptor(mediaUri,"r"); var input=new FileInputStream(descriptor.getFileDescriptor())) {
                    for(long offset:new long[]{0,1048576-13,length/2,2147483648L-4096,length-8192}) {
                        if(offset<0 || offset+8192>length) continue;
                        var range=api.open(mediaPath); range.setRequestProperty("Range","bytes="+offset+"-"+(offset+8191));
                        byte[] bytes;
                        try(var stream=range.getInputStream()) { bytes=Api.read(stream,8192); } finally { range.disconnect(); }
                        input.getChannel().position(offset); ByteBuffer actual=ByteBuffer.allocate(8192);
                        while(actual.hasRemaining()) if(input.getChannel().read(actual)<0) throw new AssertionError("Premature EOF");
                        if(!Arrays.equals(bytes,actual.array())) throw new AssertionError("Real media range mismatch at "+offset);
                    }
                }
                result.putString("stream","PASS: real media bridge ranges, cross-buffer seek, >2 GiB and EOF");
                finish(Activity.RESULT_OK,result); return;
            }
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
            for(int offset=0;offset<expected.length;offset+=4*1024*1024) {
                int end=Math.min(expected.length-1,offset+4*1024*1024-1);
                byte[] chunk=VerifiedChunk.read(api,path,offset,end,expected.length);
                if(!Arrays.equals(chunk,Arrays.copyOfRange(expected,offset,end+1))) throw new AssertionError("Verified download differs from original");
            }
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
            result.putString("stream","PASS: verified SHA-256 downloads; corrupted/wrong/truncated/unhashed chunks rejected; retry recovery and limit; TLS, stream, seeking, logout");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {
            result.putString("stream","FAIL: "+e); finish(Activity.RESULT_CANCELED,result);
        }
    }
}
