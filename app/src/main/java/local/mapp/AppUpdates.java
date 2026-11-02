package local.mapp;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Updates use the paired server's pinned TLS connection and the installed signing identity. */
final class AppUpdates {
    private static final AtomicBoolean busy=new AtomicBoolean();
    private static final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("updates",0); }
    static long code(Context c) throws Exception { return c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode(); }
    static String version(Context c) { try { return c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName; } catch(Exception e) { return ""; } }
    private static boolean alive(Activity a) { return !a.isFinishing() && !a.isDestroyed(); }
    static void resume(Activity activity) {
        if(prefs(activity).getBoolean("permission_pending",false)) {
            prefs(activity).edit().remove("permission_pending").apply();
            if(activity.getPackageManager().canRequestPackageInstalls()) install(activity);
            return;
        }
        check(activity,false);
    }
    static void check(Activity activity,boolean manual) {
        if(!manual && System.currentTimeMillis()-prefs(activity).getLong("checked",0)<6*60*60*1000L) return;
        if(!busy.compareAndSet(false,true)) { if(manual) Toast.makeText(activity,"Обновление уже проверяется или скачивается",Toast.LENGTH_SHORT).show(); return; }
        Context context=activity.getApplicationContext();
        worker.execute(()->{
            try {
                Api api=new Api(context);
                JSONObject release=api.request("GET","/v1/app-update",null);
                prefs(context).edit().putLong("checked",System.currentTimeMillis()).apply();
                boolean available=release.optLong("version_code")>code(context);
                if(!available) {
                    JSONObject saved=new JSONObject(prefs(context).getString("release","{}"));
                    if(saved.optLong("version_code")<=code(context)) { UpdateProvider.apk(context).delete(); prefs(context).edit().remove("release").apply(); }
                    if(!manual) return;
                }
                activity.runOnUiThread(()->{
                    if(!alive(activity)) return;
                    if(available) new AlertDialog.Builder(activity).setTitle("MApp "+release.optString("version_name"))
                        .setMessage("Доступно обновление.\n\n"+release.optString("notes")+"\n\nСкачать и установить?")
                        .setPositiveButton("Обновить",(d,n)->download(activity,release))
                        .setNegativeButton("Позже",null).show();
                    else if(manual) new AlertDialog.Builder(activity).setMessage("Установлена последняя версия: "+version(activity)).setPositiveButton("Понятно",null).show();
                });
            } catch(Exception e) { if(manual) showError(activity,e); }
            finally { busy.set(false); }
        });
    }
    private static void showError(Activity a,Exception error) {
        a.runOnUiThread(()->{ if(alive(a)) new AlertDialog.Builder(a).setTitle("Обновление не выполнено")
            .setMessage("Проверьте соединение и повторите в настройках.\n"+String.valueOf(error.getMessage()))
            .setPositiveButton("Понятно",null).show(); });
    }
    static String hex(byte[] bytes) { StringBuilder out=new StringBuilder(); for(byte b:bytes) out.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return out.toString(); }
    static void verify(Context c,File file,JSONObject release) throws Exception {
        if(file.length()!=release.getLong("size")) throw new IOException("Размер APK не совпадает");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(var input=new FileInputStream(file)) { byte[] buffer=new byte[65536]; int n; while((n=input.read(buffer))!=-1) digest.update(buffer,0,n); }
        if(!hex(digest.digest()).equals(release.getString("sha256"))) throw new IOException("Контрольная сумма APK не совпадает");
        var pm=c.getPackageManager();
        var candidate=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        var current=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(candidate==null || !c.getPackageName().equals(candidate.packageName) || candidate.getLongVersionCode()!=release.getLong("version_code")
            || candidate.getLongVersionCode()<=current.getLongVersionCode() || candidate.signingInfo==null
            || !Arrays.equals(candidate.signingInfo.getApkContentsSigners(),current.signingInfo.getApkContentsSigners())) throw new IOException("Неверная версия или подпись APK");
    }
    private static void download(Activity activity,JSONObject release) {
        if(!busy.compareAndSet(false,true)) return;
        ProgressDialog progress=new ProgressDialog(activity); progress.setTitle("Обновление MApp"); progress.setMessage("Скачивание APK…");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL); progress.setMax(100); progress.setCancelable(false); progress.show();
        Context c=activity.getApplicationContext();
        worker.execute(()->{
            File apk=UpdateProvider.apk(c), partial=new File(apk.getParentFile(),"download.part");
            try {
                long size=release.getLong("size"); String hash=release.getString("sha256");
                if(size<=0 || size>200L*1024*1024 || !hash.matches("[a-f0-9]{64}")) throw new IOException("Некорректное обновление");
                apk.getParentFile().mkdirs(); Api api=new Api(c);
                var connection=api.open("/v1/app-update/"+hash+".apk");
                try {
                    if(connection.getResponseCode()!=200) throw new IOException("APK недоступен");
                    try(var input=connection.getInputStream(); var output=new FileOutputStream(partial)) {
                        byte[] buffer=new byte[65536]; long total=0; int n,last=-1;
                        while((n=input.read(buffer))!=-1) {
                            total+=n; if(total>size) throw new IOException("APK превышает ожидаемый размер"); output.write(buffer,0,n);
                            int percent=(int)(total*100/size); if(percent!=last) { last=percent; activity.runOnUiThread(()->{ if(alive(activity)) progress.setProgress(percent); }); }
                        }
                        output.getFD().sync();
                    }
                } finally { connection.disconnect(); }
                verify(c,partial,release);
                if(apk.exists() && !apk.delete()) throw new IOException("Не удалось заменить предыдущий APK");
                if(!partial.renameTo(apk)) throw new IOException("Не удалось сохранить APK");
                prefs(c).edit().putString("release",release.toString()).commit();
                activity.runOnUiThread(()->{ if(alive(activity)) install(activity); });
            } catch(Exception e) { showError(activity,e); }
            finally { partial.delete(); busy.set(false); activity.runOnUiThread(()->{ if(alive(activity)) progress.dismiss(); }); }
        });
    }
    private static void install(Activity activity) {
        try {
            JSONObject release=new JSONObject(prefs(activity).getString("release","{}"));
            if(!UpdateProvider.apk(activity).isFile() || release.optLong("version_code")<=code(activity)) return;
            if(!activity.getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(activity).setTitle("Разрешение на обновление")
                    .setMessage("Разрешите MApp устанавливать обновления в следующем системном окне, затем вернитесь назад.")
                    .setPositiveButton("Открыть настройки",(d,n)->{
                        prefs(activity).edit().putBoolean("permission_pending",true).apply();
                        activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));
                    }).setNegativeButton("Позже",null).show(); return;
            }
            Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(UpdateProvider.URI,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("MApp update",UpdateProvider.URI));
            activity.startActivity(intent);
        } catch(Exception e) { showError(activity,e); }
    }
}
