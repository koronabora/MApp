package local.mapp;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** Loopback-only HTTP input for LibVLC. API credentials and TLS verification stay in Java.
 * Unlike an in-process FUSE descriptor, a stalled torrent read cannot block native close(). */
final class PlaybackBridge implements AutoCloseable {
    private final ServerSocket server;
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final Set<HttpsURLConnection> connections=ConcurrentHashMap.newKeySet();
    private final String token=UUID.randomUUID().toString();
    private final Api api;
    private final String path, identity;
    private final Context context;
    private volatile boolean closed;
    PlaybackBridge(Context context,String path) throws Exception {
        this.context=context.getApplicationContext(); this.path=path; api=new Api(context); identity=Vault.sessionIdentity(context);
        server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        workers.submit(()->{
            while(!closed) try { Socket socket=server.accept(); sockets.add(socket); workers.submit(()->serve(socket)); }
            catch(IOException | RejectedExecutionException e) { if(closed) break; }
        });
    }
    String url() { return "http://127.0.0.1:"+server.getLocalPort()+"/"+token; }
    private void serve(Socket socket) {
        HttpsURLConnection remote=null;
        try(socket) {
            socket.setSoTimeout(15000);
            BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
            String first=input.readLine();
            if(first==null || first.length()>1024) return;
            String[] request=first.split(" ");
            if(request.length!=3 || !request[1].equals("/"+token) || !(request[0].equals("GET") || request[0].equals("HEAD"))) return;
            String range=null; int total=first.length();
            for(String header;(header=input.readLine())!=null && !header.isEmpty();) {
                total+=header.length(); if(total>8192) return;
                if(header.regionMatches(true,0,"Range:",0,6)) range=header.substring(6).trim();
            }
            if(range!=null && !range.matches("bytes=(?:\\d+-\\d*|-\\d+)")) return;
            if(closed || !identity.equals(Vault.sessionIdentity(context))) return;
            remote=api.open(path); connections.add(remote); remote.setRequestMethod(request[0]); remote.setRequestProperty("Accept-Encoding","identity");
            if(range!=null) remote.setRequestProperty("Range",range);
            int code=remote.getResponseCode(); long length=remote.getContentLengthLong();
            OutputStream output=socket.getOutputStream();
            if((code!=200 && code!=206) || length<0) { output.write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); return; }
            String headers="HTTP/1.1 "+code+" OK\r\nContent-Length: "+length+"\r\nAccept-Ranges: bytes\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n";
            if(code==206) headers+="Content-Range: "+remote.getHeaderField("Content-Range")+"\r\n";
            output.write((headers+"\r\n").getBytes(StandardCharsets.US_ASCII)); output.flush();
            if(request[0].equals("HEAD")) return;
            try(InputStream stream=remote.getInputStream()) {
                byte[] bytes=new byte[64*1024]; int read;
                while(!closed && identity.equals(Vault.sessionIdentity(context)) && (read=stream.read(bytes))!=-1) output.write(bytes,0,read);
            }
        } catch(Exception ignored) { /* Closing/seeking intentionally aborts the previous HTTP request. */ }
        finally { sockets.remove(socket); if(remote!=null) { connections.remove(remote); remote.disconnect(); } }
    }
    @Override public void close() {
        closed=true;
        try { server.close(); } catch(IOException ignored) { }
        for(Socket socket:sockets) try { socket.close(); } catch(IOException ignored) { }
        workers.shutdownNow();
        new Thread(()->{ for(var connection:connections) connection.disconnect(); },"MApp stream close").start();
    }
}
