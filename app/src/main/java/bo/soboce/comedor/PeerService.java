package bo.soboce.comedor;

import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.Process;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class PeerService extends Service {
    static volatile PeerEngine engine;
    static volatile String failure="";
    static final TunnelHealth health=new TunnelHealth();
    private final ScheduledExecutorService tasks=Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService licenseTasks=Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService leaseTasks=Executors.newScheduledThreadPool(2);
    private volatile Process tunnel;
    private PowerManager.WakeLock awake;
    private volatile boolean stopped;
    @Override public void onCreate(){
        super.onCreate();failure="";
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("peer-network","Red del comedor",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        startForeground(41,new Notification.Builder(this,"peer-network").setSmallIcon(bo.soboce.comedor.R.drawable.ic_launcher).setContentTitle(getString(R.string.organization_name)+" · Red de teléfonos activa").setContentText("Mantiene y sincroniza el registro del comedor.").setContentIntent(open).setOngoing(true).build());
        awake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,getPackageName()+":peer-network");
        awake.setReferenceCounted(false);
        tasks.execute(()->{
            try{
                PeerConfig config=new PeerConfig(new Vault(this).get("peerConfig"));
                PeerEngine ready=new PeerEngine(this,config);
                synchronized(PeerService.this){if(stopped){ready.close();return;}ready.start();engine=ready;if(!config.shared)startTunnel(config);}
            }catch(Exception e){failure="No se pudo iniciar la red. Revisa el archivo de configuración y la compatibilidad del teléfono.";stopSelf();}
        });
        tasks.scheduleWithFixedDelay(()->{
            if(stopped)return;
            try{
                synchronized(PeerService.this){if(stopped)return;awake.acquire(10*60*1000L);}
                PeerEngine current=engine;if(current==null)return;
                if(current.shared==null){if(tunnel==null||!tunnel.isAlive())startTunnel(current.config);current.checkOwnIdentity();}
                current.sync();
            }catch(Exception e){failure="El túnel no pudo conectarse. Comprueba internet.";}
        },2,5,TimeUnit.SECONDS);
        licenseTasks.scheduleWithFixedDelay(()->{try{bo.colbert.licensing.LicenseClient l=bo.colbert.licensing.LicenseClient.get(this);if(l.configured()&&l.refreshDue())l.refresh();}catch(Exception ignored){}},1,60,TimeUnit.SECONDS);
        leaseTasks.scheduleWithFixedDelay(()->{PeerEngine e=engine;if(!stopped&&e!=null&&e.shared!=null)e.shared.coordinate();},0,5,TimeUnit.SECONDS);
        leaseTasks.scheduleWithFixedDelay(()->{
            synchronized(PeerService.this){
                PeerEngine e=engine;if(stopped||e==null||e.shared==null)return;
                if(e.shared.leading()){try{if(tunnel==null||!tunnel.isAlive())startTunnel(e.config);}catch(Exception error){failure="No se pudo abrir el túnel; el registro local sigue disponible.";}}
                else {Process p=tunnel;tunnel=null;if(p!=null)p.destroyForcibly();}
            }
        },0,250,TimeUnit.MILLISECONDS);
    }
    private synchronized void startTunnel(PeerConfig config) throws Exception {
        if(stopped)return;
        File binary=new File(getApplicationInfo().nativeLibraryDir,"libcloudflared.so");
        if(!binary.canExecute())throw new IOException("Conector Android no incluido.");
        File dir=new File(getFilesDir(),"peer-tunnel");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Directorio");
        File credentials=new File(dir,"credentials.json"),yaml=new File(dir,"config.yml");
        try(FileOutputStream out=new FileOutputStream(credentials)){out.write(config.raw.getJSONObject("tunnel").toString().getBytes(StandardCharsets.UTF_8));}
        String text="tunnel: "+config.raw.getJSONObject("tunnel").getString("TunnelID")+"\ncredentials-file: "+credentials.getAbsolutePath()+"\nprotocol: http2\ningress:\n  - hostname: "+new java.net.URL(config.shared?config.raw.getString("url"):config.nodes.getJSONObject(config.node).getString("url")).getHost()+"\n    service: http://127.0.0.1:"+getResources().getInteger(R.integer.peer_port)+"\n  - service: http_status:404\n";
        try(FileOutputStream out=new FileOutputStream(yaml)){out.write(text.getBytes(StandardCharsets.UTF_8));}
        ProcessBuilder builder=new ProcessBuilder(binary.getAbsolutePath(),"tunnel","--config",yaml.getAbsolutePath(),"--no-autoupdate","run",config.raw.getJSONObject("tunnel").getString("TunnelID"));
        health.reset();builder.directory(dir);builder.environment().put("HOME",dir.getAbsolutePath());builder.redirectErrorStream(true);tunnel=builder.start();failure="";
        final Process running=tunnel;
        Thread drain=new Thread(()->{try(BufferedReader reader=new BufferedReader(new InputStreamReader(running.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)health.line(line);}catch(IOException ignored){}finally{if(!stopped&&tunnel==running)try{health.exited(running.waitFor());}catch(InterruptedException e){Thread.currentThread().interrupt();}}},"tunnel-output");drain.setDaemon(true);drain.start();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public synchronized void onDestroy(){stopped=true;tasks.shutdownNow();leaseTasks.shutdownNow();licenseTasks.shutdownNow();Process p=tunnel;if(p!=null)p.destroyForcibly();PeerEngine e=engine;engine=null;if(e!=null)e.close();if(awake!=null&&awake.isHeld())awake.release();super.onDestroy();}
}
