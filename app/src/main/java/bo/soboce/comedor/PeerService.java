package bo.soboce.comedor;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class PeerService extends Service {
    static volatile PeerEngine engine;
    static volatile String failure="";
    private final ScheduledExecutorService tasks=Executors.newSingleThreadScheduledExecutor();
    private volatile Process tunnel;
    private PowerManager.WakeLock awake;
    private volatile boolean stopped;
    @Override public void onCreate(){
        super.onCreate();failure="";
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("peer-network","Red del comedor",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        startForeground(41,new Notification.Builder(this,"peer-network").setSmallIcon(bo.soboce.comedor.R.drawable.ic_launcher).setContentTitle("SOBOCE · Red de teléfonos activa").setContentText("Mantiene y sincroniza el registro del comedor.").setContentIntent(open).setOngoing(true).build());
        awake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SOBOCE:peer-network");
        tasks.execute(()->{
            try{
                PeerConfig config=new PeerConfig(new Vault(this).get("peerConfig"));
                PeerEngine ready=new PeerEngine(this,config);ready.start();engine=ready;startTunnel(config);
            }catch(Exception e){failure="No se pudo iniciar la red. Revisa el archivo de configuración y la compatibilidad del teléfono.";stopSelf();}
        });
        tasks.scheduleWithFixedDelay(()->{
            if(stopped)return;
            try{
                if(!awake.isHeld())awake.acquire(10*60*1000L);
                PeerEngine current=engine;if(current==null)return;
                if(tunnel==null||!tunnel.isAlive())startTunnel(current.config);
                current.sync();
            }catch(Exception e){failure="El túnel no pudo conectarse. Comprueba internet.";}
        },2,15,TimeUnit.SECONDS);
    }
    private void startTunnel(PeerConfig config) throws Exception {
        File binary=new File(getApplicationInfo().nativeLibraryDir,"libcloudflared.so");
        if(!binary.canExecute())throw new IOException("Conector Android no incluido.");
        File dir=new File(getFilesDir(),"peer-tunnel");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Directorio");
        File credentials=new File(dir,"credentials.json"),yaml=new File(dir,"config.yml");
        try(FileOutputStream out=new FileOutputStream(credentials)){out.write(config.raw.getJSONObject("tunnel").toString().getBytes(StandardCharsets.UTF_8));}
        String text="tunnel: "+config.raw.getJSONObject("tunnel").getString("TunnelID")+"\ncredentials-file: "+credentials.getAbsolutePath()+"\nprotocol: http2\ningress:\n  - hostname: "+new java.net.URL(config.nodes.getJSONObject(config.node).getString("url")).getHost()+"\n    service: http://127.0.0.1:8787\n  - service: http_status:404\n";
        try(FileOutputStream out=new FileOutputStream(yaml)){out.write(text.getBytes(StandardCharsets.UTF_8));}
        ProcessBuilder builder=new ProcessBuilder(binary.getAbsolutePath(),"tunnel","--config",yaml.getAbsolutePath(),"--no-autoupdate","run",config.raw.getJSONObject("tunnel").getString("TunnelID"));
        builder.directory(dir);builder.environment().put("HOME",dir.getAbsolutePath());builder.redirectErrorStream(true);tunnel=builder.start();
        final Process running=tunnel;
        Thread drain=new Thread(()->{try(InputStream in=running.getInputStream()){byte[] bytes=new byte[4096];while(in.read(bytes)!=-1){/* Discard connector logs: no credentials or request data on disk. */}}catch(IOException ignored){}},"tunnel-output");drain.setDaemon(true);drain.start();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){stopped=true;tasks.shutdownNow();Process p=tunnel;if(p!=null)p.destroy();PeerEngine e=engine;engine=null;if(e!=null)e.close();if(awake!=null&&awake.isHeld())awake.release();super.onDestroy();}
}
