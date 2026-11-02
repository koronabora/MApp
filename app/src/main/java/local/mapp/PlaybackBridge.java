package local.mapp;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Loopback stream. Once a download exists, reads are exclusively from its saved bytes. */
final class PlaybackBridge implements AutoCloseable {
    private final ServerSocket server;
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final String token=UUID.randomUUID().toString();
    private final Api api;
    private final String path, identity;
    private final Context context;
    private final long length;
    private volatile boolean closed;
    private volatile String localId="";
    private final Map<Long,byte[]> cache=Collections.synchronizedMap(new LinkedHashMap<Long,byte[]>(64,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long,byte[]> entry) { return size()>128; }
    });
    PlaybackBridge(Context context,String path,long length) throws Exception {
        this(context,path,length,"");
    }
    private PlaybackBridge(Context context,String path,long length,String localId) throws Exception {
        this.context=context.getApplicationContext(); this.path=path; this.length=length;
        this.localId=localId; api=localId.isEmpty()?new Api(context):null; identity=localId.isEmpty()?Vault.sessionIdentity(context):"";
        server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        workers.submit(()->{ while(!closed) try { Socket socket=server.accept(); sockets.add(socket); workers.submit(()->serve(socket)); }
            catch(IOException | RejectedExecutionException e) { if(closed) break; } });
    }
    PlaybackBridge(Context context,JSONObject local) throws Exception {
        this(context,local.getString("path"),local.getLong("size"),local.getString("id"));
    }
    String url() { return "http://127.0.0.1:"+server.getLocalPort()+"/"+token; }
    private byte[] block(long start) throws Exception {
        if(closed || (!identity.isEmpty() && !identity.equals(Vault.sessionIdentity(context)))) throw new IOException("Playback closed");
        long end=Math.min(length-1,start+512*1024-1);
        long deadline=System.currentTimeMillis()+30000;
        do {
            JSONObject local=null; var items=Library.all(context);
            for(int i=0;i<items.length();i++) {
                JSONObject item=items.optJSONObject(i);
                if((!localId.isEmpty() && localId.equals(item.optString("id"))) || (localId.isEmpty() && path.equals(item.optString("path")) && api.server.equals(item.optString("server")))) { local=item; break; }
            }
            if(local==null) { if(!localId.isEmpty()) throw new IOException("Сохранённый файл удалён"); break; }
            if(localId.isEmpty()) { localId=local.optString("id"); cache.clear(); }
            if(local.optLong("downloaded")<=end) {
                if(start>=LocalIndex.offset(local) && LocalIndex.ready(context,local)) return LocalIndex.read(context,local,start,(int)(end-start+1));
                if(!DownloadService.queued.contains(localId) || System.currentTimeMillis()>=deadline) throw new IOException("Этот диапазон ещё не скачан");
                Thread.sleep(200); continue;
            }
            try(var fd=context.getContentResolver().openFileDescriptor(Uri.parse(local.getString("uri")),"r"); var in=new FileInputStream(fd.getFileDescriptor())) {
                byte[] bytes=new byte[(int)(end-start+1)]; ByteBuffer buffer=ByteBuffer.wrap(bytes); in.getChannel().position(start);
                while(buffer.hasRemaining()) if(in.getChannel().read(buffer)<0) throw new EOFException();
                Diagnostics.event("playback-read","local-only offset="+start+" bytes="+bytes.length); return bytes;
            }
        } while(!closed);
        if(closed) throw new InterruptedIOException();
        byte[] found=cache.get(start); if(found!=null) return found;
        long began=System.currentTimeMillis();
        byte[] bytes=VerifiedChunk.retry(()->{ if(closed) throw new InterruptedIOException(); return VerifiedChunk.read(api,path,start,end,length); });
        cache.put(start,bytes); Diagnostics.event("playback-read","remote offset="+start+" bytes="+bytes.length+" ms="+(System.currentTimeMillis()-began)); return bytes;
    }
    void prime(long position,long duration,int seconds) throws Exception {
        long rate=duration>0?length*1000/duration:1024*1024;
        long start=duration>0?Math.max(0,position-2000)*length/duration:0;
        start=start/(512*1024)*(512*1024);
        long target=Math.min(length, start+Math.min(48L*1024*1024,Math.max(2L*1024*1024,rate*(seconds+2))));
        block(0);
        for(long offset=start;offset<target;offset+=512*1024) block(offset);
        for(long offset=Math.max(0,length-4*1024*1024)/(512*1024)*(512*1024);offset<length;offset+=512*1024) block(offset);
        Diagnostics.event("playback-buffer","client ready bytes="+(target-start)+" position="+position);
    }
    private void serve(Socket socket) {
        try(socket) {
            socket.setSoTimeout(15000);
            BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
            String first=input.readLine(); if(first==null || first.length()>1024) return;
            String[] request=first.split(" ");
            if(request.length!=3 || !request[1].equals("/"+token) || !(request[0].equals("GET") || request[0].equals("HEAD"))) return;
            String range=null; int total=first.length();
            for(String header;(header=input.readLine())!=null && !header.isEmpty();) { total+=header.length(); if(total>8192) return; if(header.regionMatches(true,0,"Range:",0,6)) range=header.substring(6).trim(); }
            long start=0,end=length-1;
            if(range!=null) {
                if(!range.matches("bytes=(?:\\d+-\\d*|-\\d+)")) return;
                String[] parts=range.substring(6).split("-",-1);
                if(parts[0].isEmpty()) start=Math.max(0,length-Long.parseLong(parts[1]));
                else { start=Long.parseLong(parts[0]); if(!parts[1].isEmpty()) end=Math.min(end,Long.parseLong(parts[1])); }
            }
            OutputStream output=socket.getOutputStream();
            if(start>end || start>=length) { output.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */"+length+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); return; }
            String headers="HTTP/1.1 "+(range==null?"200 OK":"206 Partial Content")+"\r\nContent-Length: "+(end-start+1)+"\r\nAccept-Ranges: bytes\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n";
            if(range!=null) headers+="Content-Range: bytes "+start+"-"+end+"/"+length+"\r\n";
            output.write((headers+"\r\n").getBytes(StandardCharsets.US_ASCII)); output.flush();
            if(request[0].equals("HEAD")) return;
            for(long cursor=start;cursor<=end && !closed;) {
                long base=cursor/(512*1024)*(512*1024); byte[] bytes=block(base);
                int offset=(int)(cursor-base), count=(int)Math.min(end-cursor+1,bytes.length-offset);
                output.write(bytes,offset,count); output.flush(); cursor+=count;
            }
        } catch(Exception e) { if(!closed) Diagnostics.event("playback-read-error",e.getClass().getSimpleName()); }
        finally { sockets.remove(socket); }
    }
    @Override public void close() {
        closed=true; try { server.close(); } catch(IOException ignored) { }
        for(Socket socket:sockets) try { socket.close(); } catch(IOException ignored) { }
        workers.shutdownNow(); cache.clear();
    }
}
