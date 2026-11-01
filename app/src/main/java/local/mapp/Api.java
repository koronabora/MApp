package local.mapp;

import android.content.Context;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;
import org.json.JSONObject;

final class Api {
    static final class Failure extends java.io.IOException {
        final int code;
        Failure(int code) { super(code==401?"Ссылка истекла или доступ отозван":"Ошибка MCore: "+code); this.code=code; }
        Failure(int code,String message) { super(message); this.code=code; }
    }
    final String server, pin, key;
    final SSLSocketFactory sockets;
    Api(Context context) throws Exception { this(Vault.load(context)); Diagnostics.init(context); }
    Api(JSONObject session) throws Exception {
        server = session.optString("server"); pin = session.optString("pin"); key = session.optString("key");
        URL url = new URL(server);
        if (!url.getProtocol().equals("https") || url.getUserInfo()!=null || !url.getPath().isEmpty() || url.getQuery()!=null || !pin.matches("[a-fA-F0-9]{64}")) throw new SecurityException("Некорректный адрес сервера");
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String type) throws java.security.cert.CertificateException { throw new java.security.cert.CertificateException(); }
            public void checkServerTrusted(X509Certificate[] chain, String type) throws java.security.cert.CertificateException {
                try {
                    chain[0].checkValidity();
                    byte[] hash = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    StringBuilder hex = new StringBuilder();
                    for(byte b:hash) hex.append(String.format("%02x", b & 255));
                    if (!MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.US_ASCII),pin.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) throw new SecurityException();
                } catch (Exception e) { throw new java.security.cert.CertificateException("Сертификат MCore не совпадает", e); }
            }
        }}, null);
        sockets = tls.getSocketFactory();
    }
    HttpsURLConnection open(String path) throws Exception {
        if (!path.startsWith("/v1/") || path.contains("..")) throw new SecurityException();
        HttpsURLConnection c = (HttpsURLConnection)new URL(server+path).openConnection();
        c.setSSLSocketFactory(sockets);
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000); c.setReadTimeout(100000);
        if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
        return c;
    }
    JSONObject request(String method, String path, JSONObject body) throws Exception {
        long started=System.currentTimeMillis();
        HttpsURLConnection c = open(path);
        try {
            c.setRequestMethod(method);
            if(body!=null) {
                c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
                try (var out=c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            Diagnostics.event("api",method+" "+path.split("\\?",2)[0]+" status="+code+" ms="+(System.currentTimeMillis()-started));
            if(code<200 || code>=300) {
                try(var input=c.getErrorStream()) {
                    if(input!=null) {
                        JSONObject error=new JSONObject(new String(read(input,16384),StandardCharsets.UTF_8));
                        JSONObject detail=error.optJSONObject("detail");
                        if(detail!=null && detail.optString("code").matches("source_missing|tracker_unavailable|torrent_invalid")) {
                            Diagnostics.event("api-detail",detail.optString("code"));
                            throw new Failure(code,detail.optString("message","Ошибка MCore: "+code));
                        }
                    }
                } catch(Failure failure) { throw failure; } catch(Exception ignored) { }
                throw new Failure(code);
            }
            try(InputStream in=c.getInputStream()) { return new JSONObject(new String(read(in,16*1024*1024), StandardCharsets.UTF_8)); }
        } catch(Exception e) { Diagnostics.event("api-error",e.getClass().getSimpleName()); throw e; }
        finally { c.disconnect(); }
    }
    static byte[] read(InputStream in,int limit) throws java.io.IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[16384]; int count;
        while((count=in.read(buffer))!=-1) {
            if(out.size()+count>limit) throw new java.io.IOException("Ответ сервера слишком большой");
            out.write(buffer,0,count);
        }
        return out.toByteArray();
    }
}
