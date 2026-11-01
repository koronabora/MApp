package local.mapp;

import java.io.IOException;
import java.security.MessageDigest;

/** Download chunks are never committed until their range, length and digest agree. */
final class VerifiedChunk {
    interface Fetch { byte[] run() throws Exception; }
    static byte[] retry(Fetch fetch) throws Exception {
        IOException failure=null;
        for(int attempt=0;attempt<3;attempt++) {
            try { return fetch.run(); }
            catch(java.net.SocketTimeoutException e) { failure=e; }
            catch(java.io.InterruptedIOException e) { throw e; }
            catch(Api.Failure e) { if(e.code!=503 && e.code!=502 && e.code!=504) throw e; failure=e; }
            catch(IOException e) { failure=e; }
        }
        throw new IOException("Не удалось проверить целостность части после трёх попыток. Загрузка приостановлена.",failure);
    }
    static byte[] read(Api api,String path,long start,long end,long total) throws Exception {
        var connection=api.open(path);
        connection.setRequestProperty("Range","bytes="+start+"-"+end);
        connection.setRequestProperty("X-MCore-Integrity","sha256");
        connection.setRequestProperty("Accept-Encoding","identity");
        try {
            int status=connection.getResponseCode();
            if(status!=206) throw new Api.Failure(status);
            byte[] bytes;
            try(var input=connection.getInputStream()) { bytes=Api.read(input,(int)(end-start+1)); }
            return validate(bytes,connection.getHeaderField("Content-Range"),connection.getHeaderField("X-MCore-SHA256"),start,end,total);
        } finally { connection.disconnect(); }
    }
    static byte[] validate(byte[] bytes,String range,String digest,long start,long end,long total) throws Exception {
        if(!("bytes "+start+"-"+end+"/"+total).equals(range) || bytes.length!=end-start+1)
            throw new IOException("Сервер вернул другую часть файла или неверную длину");
        if(digest==null || !digest.matches("[0-9a-fA-F]{64}"))
            throw new IOException("Сервер не предоставил SHA-256 части. Обновите MCore.");
        byte[] expected=new byte[32];
        for(int i=0;i<32;i++) expected[i]=(byte)Integer.parseInt(digest.substring(i*2,i*2+2),16);
        if(!MessageDigest.isEqual(expected,MessageDigest.getInstance("SHA-256").digest(bytes)))
            throw new IOException("Контрольная сумма части не совпала");
        return bytes;
    }
}
