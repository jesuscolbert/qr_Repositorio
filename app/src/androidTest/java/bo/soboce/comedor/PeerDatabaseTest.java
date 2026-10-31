package bo.soboce.comedor;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.net.*;
import java.util.regex.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PeerDatabaseTest {
    @Test public void billingLockPreservesUncertainScanAndOnlyRecoversExistingReceipts() throws Exception {
        PeerConfig c=sharedConfig(0,"b".repeat(64));long now=System.currentTimeMillis();context.deleteDatabase("billing-gate.db");
        try(PeerDatabase local=new PeerDatabase(context,c,"billing-gate.db")){
            SharedLedger ledger=new SharedLedger(local,c);JSONObject previous=sharedRequest(0,"almuerzo");
            assertEquals("accepted",ledger.scan(previous,c.operator(-1),now,true).getString("status"));
            assertEquals("recovered",ledger.scan(previous,c.operator(-1),now,false).getString("status"));
            JSONObject fresh=sharedRequest(0,"cena"),denied=ledger.scan(fresh,c.operator(-1),now,false);
            assertFalse(denied.getBoolean("ok"));assertTrue(denied.getBoolean("definitive"));assertEquals(1,new OfflineLedger(local,c).records().size());
            assertEquals(ScanFeedback.Kind.PENDING,ScanFeedback.kind(LicensingController.denied()));
            assertEquals("accepted",ledger.scan(fresh,c.operator(-1),now,true).getString("status"));
            assertEquals(2,new OfflineLedger(local,c).records().size());
        }finally{context.deleteDatabase("billing-gate.db");}
    }
    @Test public void dietReplacesLunchAcrossPhonesAndPreservesItsReportLabel() throws Exception {
        PeerConfig a=sharedConfig(0,"b".repeat(64)),b=sharedConfig(1,"c".repeat(64));long now=System.currentTimeMillis();
        context.deleteDatabase("diet-a.db");context.deleteDatabase("diet-b.db");
        try(PeerDatabase ad=new PeerDatabase(context,a,"diet-a.db");PeerDatabase bd=new PeerDatabase(context,b,"diet-b.db")){
            SharedLedger al=new SharedLedger(ad,a),bl=new SharedLedger(bd,b);
            assertEquals("accepted",al.scan(sharedRequest(0,"dieta"),a.operator(-1),now).getString("status"));
            bl.merge(al.events(new JSONObject(),20));assertEquals("duplicate",bl.scan(sharedRequest(1,"almuerzo"),b.operator(-1),now).getString("status"));
            assertEquals("duplicate",bl.scan(sharedRequest(1,"dieta"),b.operator(-1),now).getString("status"));
            JSONObject saved=new OfflineLedger(bd,b).records().get(0);assertEquals("dieta",saved.getString("comida"));assertTrue(saved.getString("clave").endsWith("|almuerzo"));assertEquals(23,saved.getDouble("precio"),0.001);
            long tomorrow=now+86400000L;assertEquals("accepted",bl.scan(sharedRequest(1,"almuerzo"),b.operator(-1),tomorrow).getString("status"));al.merge(bl.events(al.vector(),20));assertEquals("duplicate",al.scan(sharedRequest(0,"dieta"),a.operator(-1),tomorrow).getString("status"));
            assertEquals("accepted",al.scan(sharedRequest(0,"cena"),a.operator(-1),tomorrow).getString("status"));
        }finally{context.deleteDatabase("diet-a.db");context.deleteDatabase("diet-b.db");}
    }
    @Test public void dietVisitorAuthorizationAndOfflineConflictAreSafe() throws Exception {
        long now=System.currentTimeMillis();PeerConfig a=sharedConfig(0,"b".repeat(64)),b=sharedConfig(1,"c".repeat(64));context.deleteDatabase("diet-visitor.db");context.deleteDatabase("diet-other.db");
        try(PeerDatabase ad=new PeerDatabase(context,a,"diet-visitor.db");PeerDatabase bd=new PeerDatabase(context,b,"diet-other.db")){
            SharedLedger al=new SharedLedger(ad,a),bl=new SharedLedger(bd,b);String card=PeerCards.request(ad,a,visitorRequest("Planta 0",now).put("meals","dieta"),now).getString("qr");
            assertFalse(al.scan(sharedRequest(0,"almuerzo").put("qr",card),a.operator(-1),now).getBoolean("ok"));assertEquals("accepted",al.scan(sharedRequest(0,"dieta").put("qr",card),a.operator(-1),now).getString("status"));
            al.scan(sharedRequest(0,"dieta"),a.operator(-1),now);bl.scan(sharedRequest(1,"almuerzo"),b.operator(-1),now);al.merge(bl.events(al.vector(),20));
            int marked=0;for(JSONObject row:new OfflineLedger(ad,a).records())if(row.optString("revision").equals("POSIBLE DUPLICADO"))marked++;assertEquals(2,marked);
        }finally{context.deleteDatabase("diet-visitor.db");context.deleteDatabase("diet-other.db");}
    }
    @Test public void dailyReportsFilterExactDateValidateCalendarAndExportAllRows() throws Exception {
        long now=System.currentTimeMillis();PeerConfig c=sharedConfig(0,"b".repeat(64));context.deleteDatabase("daily-report.db");
        try(PeerDatabase local=new PeerDatabase(context,c,"daily-report.db")){
            SharedLedger ledger=new SharedLedger(local,c);ledger.scan(sharedRequest(0,"desayuno"),c.operator(-1),now);ledger.scan(sharedRequest(0,"dieta"),c.operator(-1),now);ledger.scan(sharedRequest(0,"cena"),c.operator(-1),now+86400000L);
            JSONObject req=new JSONObject().put("action","report").put("period","day").put("date",MealLedger.day(now)).put("plant","Planta 0").put("allPlants",true);
            JSONObject r=new MealLedger(local,c).read(req);assertEquals(2,r.getInt("count"));assertEquals(35,r.getDouble("total"),0.001);assertEquals(1,r.getJSONObject("byMeal").getInt("dieta"));assertEquals(0,r.getJSONObject("byMeal").getInt("almuerzo"));assertEquals(3,r.getString("csv").split("\r\n").length);
            assertEquals(1,new MealLedger(local,c).read(new JSONObject(req.toString()).put("date",MealLedger.day(now+86400000L))).getInt("count"));
            assertEquals(0,new MealLedger(local,c).read(new JSONObject(req.toString()).put("date","2020-01-01")).getInt("count"));
            for(String date:new String[]{"2026-02-30","2026-13-01","not-a-date"})try{new MealLedger(local,c).read(new JSONObject(req.toString()).put("date",date));fail("Invalid calendar date");}catch(IllegalArgumentException expected){}
            assertFalse(new MealLedger(local,c).read(new JSONObject(req.toString()).put("preview",true)).has("csv"));
        }finally{context.deleteDatabase("daily-report.db");}
    }
    @Test public void continuousScannerIgnoresHeldCardButDetectsASecondPresentation() throws Exception {
        ScanGate gate=new ScanGate();assertTrue(gate.read("A",1000));assertFalse(gate.read("A",1500));assertFalse(gate.read("B",1600));gate.ready();assertFalse(gate.read("A",1800));assertFalse(gate.read("A",3000));assertTrue(gate.read("B",3100));gate.ready();assertTrue(gate.read("A",3200));gate.ready();assertTrue(gate.read("A",5000));gate.hold();assertFalse(gate.read("C",6000));
        assertEquals(ScanFeedback.Kind.ALLOWED,ScanFeedback.kind(new JSONObject().put("ok",true).put("status","accepted")));
        for(String status:new String[]{"duplicate","recovered"})assertEquals(ScanFeedback.Kind.DUPLICATE,ScanFeedback.kind(new JSONObject().put("ok",true).put("status",status)));
        assertEquals(ScanFeedback.Kind.PENDING,ScanFeedback.kind(new JSONObject().put("ok",true).put("status","unknown")));assertEquals(ScanFeedback.Kind.PENDING,ScanFeedback.kind(new JSONObject().put("ok",false)));assertEquals(0,ScanFeedback.delay(ScanFeedback.Kind.PENDING));
    }
    boolean containsText(android.view.View view,String text){if(view instanceof android.widget.TextView&&((android.widget.TextView)view).getText().toString().contains(text))return true;if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++)if(containsText(((android.view.ViewGroup)view).getChildAt(i),text))return true;return false;}
    void waitText(MainActivity a,String text) throws Exception {long end=System.currentTimeMillis()+15000;java.util.concurrent.atomic.AtomicBoolean found=new java.util.concurrent.atomic.AtomicBoolean();while(System.currentTimeMillis()<end){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->found.set(containsText(a.getWindow().getDecorView(),text)));if(found.get())return;Thread.sleep(70);}fail("UI text not found: "+text);}
    void shellUi(String command) throws Exception {try(android.os.ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){in.readAllBytes();}}
    void screenshot(String name) throws Exception {shellUi("mkdir -p /sdcard/Download/soboce-design");shellUi("screencap -p /sdcard/Download/soboce-design/"+name+".png");}
    @Test public void redesignedScreensShowActualSavedScanAndDailyTotals() throws Exception {
        Vault vault=new Vault(context);clearSetupVault(vault);vault.remove("needsProfile");
        PeerConfig uiConfig=new PeerConfig(new JSONObject(sharedConfig(0,"d".repeat(64)).raw.toString()).put("node",0).toString());vault.put("peerConfig",uiConfig.raw.toString());vault.put("mode","p2p");
        shellUi("pm grant "+context.getPackageName()+" android.permission.CAMERA");shellUi("pm grant "+context.getPackageName()+" android.permission.POST_NOTIFICATIONS");
        MainActivity activity=(MainActivity)InstrumentationRegistry.getInstrumentation().startActivitySync(new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            waitText(activity,"INICIAR ESCANEO");screenshot("01-inicio");
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::openScanner);Thread.sleep(600);
            String qr="SB1:"+"a".repeat(64);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->activity.scanner.onCode(qr));waitText(activity,"ACCESO PERMITIDO");screenshot("02-acceso-permitido");
            assertTrue(vault.get("pending").isEmpty());Thread.sleep(2000);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->activity.scanner.onCode(qr));waitText(activity,"YA REGISTRADO");screenshot("03-duplicado");
            assertEquals(1,new OfflineLedger(db,uiConfig).records().size());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->activity.scanner.show(new JSONObject()));waitText(activity,"SIN CONFIRMACIÓN");screenshot("04-pendiente");
            long now=System.currentTimeMillis();new SharedLedger(db,uiConfig).scan(sharedRequest(0,"desayuno"),uiConfig.operator(-1),now);new SharedLedger(db,uiConfig).scan(sharedRequest(0,"cena"),uiConfig.operator(-1),now);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::report);waitText(activity,"ENTREGAS DEL DÍA");screenshot("05-reporte-diario");
            assertTrue(vault.get("pending").isEmpty());
        }finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);context.stopService(new android.content.Intent(context,PeerService.class));long end=System.currentTimeMillis()+5000;while(PeerService.engine!=null&&System.currentTimeMillis()<end)Thread.sleep(50);clearSetupVault(vault);}
    }
    Process namedConnector(PeerConfig config) throws Exception {
        File dir=new File(context.getFilesDir(),"shared-live-test");assertTrue(dir.exists()||dir.mkdirs());File credentials=new File(dir,"credentials.json"),yaml=new File(dir,"config.yml");
        try(FileOutputStream out=new FileOutputStream(credentials)){out.write(config.raw.getJSONObject("tunnel").toString().getBytes(StandardCharsets.UTF_8));}
        String text="tunnel: "+config.raw.getJSONObject("tunnel").getString("TunnelID")+"\ncredentials-file: "+credentials.getAbsolutePath()+"\nprotocol: http2\ningress:\n  - hostname: "+new URL(config.raw.getString("url")).getHost()+"\n    service: http://127.0.0.1:8787\n  - service: http_status:404\n";
        try(FileOutputStream out=new FileOutputStream(yaml)){out.write(text.getBytes(StandardCharsets.UTF_8));}
        Process p=new ProcessBuilder(new File(context.getApplicationInfo().nativeLibraryDir,"libcloudflared.so").getAbsolutePath(),"tunnel","--config",yaml.getAbsolutePath(),"--no-autoupdate","run",config.raw.getJSONObject("tunnel").getString("TunnelID")).directory(dir).redirectErrorStream(true).start();
        Thread drain=new Thread(()->{try(InputStream in=p.getInputStream()){byte[] bytes=new byte[4096];while(in.read(bytes)!=-1){/* Never print connector credentials or raw logs. */}}catch(IOException ignored){}});drain.setDaemon(true);drain.start();return p;
    }
    @Test public void realSharedTunnelTransfersMealsAndReplacesCrashedServer() throws Exception {
        JSONObject connection;
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("shared-live-test.json")){connection=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        catch(FileNotFoundException absent){org.junit.Assume.assumeTrue("Live credentials available only to authorized branch builds",false);return;}
        PeerEngine[] engines=new PeerEngine[3];Process tunnel=null;final java.util.concurrent.atomic.AtomicReference<PeerEngine> renew=new java.util.concurrent.atomic.AtomicReference<>();java.util.concurrent.ScheduledExecutorService timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try{
            for(int i=0;i<3;i++){
                context.deleteDatabase("live-shared-"+i+".db");JSONObject raw=new JSONObject(sharedConfig(i,PeerConfig.random()).raw.toString());for(String key:new String[]{"url","coordinator","networkKey","tunnel"})raw.put(key,connection.get(key));engines[i]=new PeerEngine(context,new PeerConfig(raw.toString()),"live-shared-"+i+".db");
            }
            PeerEngine a=engines[0],b=engines[1],c=engines[2];
            timer.scheduleWithFixedDelay(()->{PeerEngine e=renew.get();if(e!=null)e.shared.coordinate();},0,3,TimeUnit.SECONDS);
            long deadline=System.currentTimeMillis()+45000;while(!a.shared.leading()&&System.currentTimeMillis()<deadline){a.shared.coordinate();Thread.sleep(500);}assertTrue("First phone could not acquire lease",a.shared.leading());
            renew.set(a);a.start();tunnel=namedConnector(a.config);
            deadline=System.currentTimeMillis()+90000;boolean reached=false;
            while(System.currentTimeMillis()<deadline){b.shared.coordinate();try{b.shared.exchange();reached=true;break;}catch(Exception waiting){Thread.sleep(1000);}}
            assertTrue("Client could not reach Android through named tunnel",reached);
            assertEquals("accepted",b.request(sharedRequest(1,"almuerzo")).getString("status"));assertEquals(1,a.offline.records().size());assertEquals(1,b.offline.records().size());
            renew.set(null);a.close();tunnel.destroyForcibly();assertTrue(tunnel.waitFor(10,TimeUnit.SECONDS));tunnel=null;
            long previous=a.shared.lease.epoch;deadline=System.currentTimeMillis()+45000;
            while(!b.shared.leading()&&System.currentTimeMillis()<deadline){b.shared.coordinate();Thread.sleep(1000);}assertTrue("Second phone did not take over expired lease",b.shared.leading());assertTrue(b.shared.lease.epoch>previous);
            renew.set(b);b.start();tunnel=namedConnector(b.config);
            deadline=System.currentTimeMillis()+90000;reached=false;
            while(System.currentTimeMillis()<deadline){c.shared.coordinate();try{c.shared.exchange();reached=true;break;}catch(Exception waiting){Thread.sleep(1000);}}
            assertTrue("Same URL did not reach replacement Android server",reached);assertEquals(1,c.offline.records().size());
            assertEquals("duplicate",c.request(sharedRequest(2,"almuerzo")).getString("status"));assertEquals("accepted",c.request(sharedRequest(2,"cena")).getString("status"));
            PeerConfig old=a.config;a.db.close();engines[0]=new PeerEngine(context,old,"live-shared-0.db");engines[0].shared.coordinate();assertFalse(engines[0].shared.leading());engines[0].shared.exchange();
            for(PeerEngine e:engines)assertEquals(2,e.offline.records().size());
            SharedNetwork.http(b.config.raw.getString("coordinator")+"/lease",b.shared.lease.request("release"),"Bearer "+b.config.raw.getString("networkKey"));
        }finally{
            renew.set(null);timer.shutdownNow();if(tunnel!=null)tunnel.destroyForcibly();
            for(int i=0;i<engines.length;i++){if(engines[i]!=null){engines[i].close();engines[i].db.close();}context.deleteDatabase("live-shared-"+i+".db");}
            File dir=new File(context.getFilesDir(),"shared-live-test");File[] files=dir.listFiles();if(files!=null)for(File f:files)f.delete();dir.delete();
        }
    }
    JSONObject localSharedHttp(JSONObject envelope) throws Exception {
        byte[] body=envelope.toString().getBytes(StandardCharsets.UTF_8);
        try(Socket socket=new Socket("127.0.0.1",8787)){
            socket.setSoTimeout(5000);OutputStream out=socket.getOutputStream();out.write(("POST /rpc HTTP/1.1\r\nHost: localhost\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
            String result=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);if(!result.startsWith("HTTP/1.1 200"))throw new IOException("Rejected");return new JSONObject(result.substring(result.indexOf("\r\n\r\n")+4));
        }
    }
    PeerConfig sharedConfig(int actor,String device) throws Exception {
        JSONObject raw=new JSONObject(config.raw.toString());raw.remove("identityPrivate");
        return new PeerConfig(raw.put("format","SOBOCE-SHARED-1").put("node",-1).put("device",device).put("networkKey","a".repeat(64)).put("url","https://comedor.example.com").put("coordinator","https://coord.example.com").put("operator",config.nodes.getJSONObject(actor)).toString());
    }
    JSONObject sharedRequest(int plant,String meal) throws Exception {return new JSONObject(scan(plant,"Planta "+plant,meal,UUID.randomUUID().toString())).getJSONObject("request");}
    void elect(PeerEngine[] engines,int owner,long epoch) throws Exception {
        long now=android.os.SystemClock.elapsedRealtime();
        for(PeerEngine e:engines)e.shared.lease.update(new JSONObject().put("owner",engines[owner].config.device).put("boot",engines[owner].shared.lease.boot).put("epoch",epoch).put("remaining",30000),now,now);
    }
    @Test public void universalInstallGeneratesUniqueIdentityAndKeepsLegacyHistory() throws Exception {
        long now=System.currentTimeMillis();JSONObject request=sharedRequest(0,"almuerzo");
        assertEquals("accepted",new OfflineLedger(db,config).scan(request,now).getString("status"));
        db.getWritableDatabase().setVersion(3);db.close();
        Vault vault=new Vault(context);clearSetupVault(vault);vault.remove("sharedDevice");
        JSONObject bundle=new JSONObject(sharedConfig(0,"b".repeat(64)).raw.toString()).put("format","SOBOCE-SHARED-BUNDLE-1");bundle.remove("device");bundle.remove("operator");bundle.remove("node");
        try{
            vault.put("peerConfig",config.raw.toString());SharedSetup.apply(vault,bundle.toString());PeerConfig updated=new PeerConfig(vault.get("peerConfig"));
            assertTrue(updated.shared);assertEquals(0,updated.node);assertFalse(updated.raw.has("identityPrivate"));
            db=new PeerDatabase(context,updated);assertEquals(1,new OfflineLedger(db,updated).records().size());
            assertEquals("recovered",new SharedLedger(db,updated).scan(request,updated.operator(-1),now).getString("status"));
            String first=updated.device;SharedSetup.apply(vault,bundle.toString());assertEquals(first,new PeerConfig(vault.get("peerConfig")).device);
            clearSetupVault(vault);vault.remove("sharedDevice");SharedSetup.apply(vault,bundle.toString());assertNotEquals(first,new PeerConfig(vault.get("peerConfig")).device);assertEquals("yes",vault.get("needsProfile"));
        }finally{clearSetupVault(vault);for(String key:new String[]{"sharedDevice","needsProfile","legacyPeerConfig"})vault.remove(key);}
    }
    @Test public void sharedCardsWorkEverywhereAndOldCardsStillWork() throws Exception {
        PeerConfig a=sharedConfig(0,"b".repeat(64)),b=sharedConfig(1,"c".repeat(64));long now=System.currentTimeMillis();
        context.deleteDatabase("shared-cards.db");try(PeerDatabase local=new PeerDatabase(context,a,"shared-cards.db")){
            String qr=PeerCards.request(local,a,workerRequest("Planta 0","SAP-12345"),now).getString("qr");assertTrue(qr.startsWith("SB3:"));assertEquals("SAP-12345",PeerCards.resolve(local,b,qr,now).getString("id"));
            String old=PeerCards.request(db,config,workerRequest("Planta 0","SAP-12345"),now).getString("qr");assertEquals("SAP-12345",PeerCards.resolve(local,b,old,now).getString("id"));
            String visitor=PeerCards.request(local,a,visitorRequest("Planta 0",now),now).getString("qr");assertEquals("VISITA",PeerCards.resolve(local,b,visitor,now).getString("tipo"));
            String[] parts=qr.substring(4).split("\\.");JSONObject body=new JSONObject(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8));body.put("id","SAP-99999");String fake="SB3:"+Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().getBytes(StandardCharsets.UTF_8))+"."+parts[1];
            try{PeerCards.resolve(local,b,fake,now);fail("Tampered shared card");}catch(IllegalArgumentException expected){}
        }finally{context.deleteDatabase("shared-cards.db");}
    }
    @Test public void sharedReplicationFailoverOfflineRejoinAndAllPlantFeed() throws Exception {
        PeerEngine[] engines=new PeerEngine[4];final int[] leader={0};boolean[] online={true,true,true,true};
        try{
            for(int i=0;i<4;i++){
                String name="shared-test-"+i+".db";context.deleteDatabase(name);PeerEngine e=new PeerEngine(context,sharedConfig(i,PeerConfig.hash("device"+i)),name);engines[i]=e;
                e.shared=new SharedNetwork(e){@Override JSONObject rpc(JSONObject request) throws Exception {if(!online[leader[0]])throw new IOException("offline");return engines[leader[0]].shared.dispatch(new JSONObject(request.toString()));}};
            }
            elect(engines,0,1);
            assertEquals("accepted",engines[1].request(sharedRequest(1,"almuerzo")).getString("status"));
            assertEquals("duplicate",engines[2].request(sharedRequest(2,"almuerzo")).getString("status"));
            for(PeerEngine e:engines)e.sync();for(PeerEngine e:engines)assertEquals(1,e.offline.records().size());
            online[0]=false;assertEquals("accepted",engines[2].request(sharedRequest(2,"cena")).getString("status"));assertEquals(1,engines[2].shared.ledger.pending());
            leader[0]=1;elect(engines,1,2);engines[2].sync();engines[3].sync();assertEquals(0,engines[2].shared.ledger.pending());
            online[0]=true;engines[0].sync();
            for(PeerEngine e:engines){assertEquals(2,e.offline.records().size());JSONObject feed=e.request(new JSONObject().put("action","today").put("plant",e.config.operator(-1).getString("plant")).put("allPlants",true));assertEquals(2,feed.getInt("count"));}
            assertFalse(engines[0].shared.leading());assertTrue(engines[1].shared.leading());
            assertEquals("duplicate",engines[0].request(sharedRequest(0,"cena")).getString("status"));
        }finally{for(int i=0;i<4;i++){if(engines[i]!=null){engines[i].close();engines[i].db.close();}context.deleteDatabase("shared-test-"+i+".db");}}
    }
    @Test public void sharedPagesRejectTamperingAndPreserveOfflineConflicts() throws Exception {
        PeerConfig a=sharedConfig(0,"b".repeat(64)),b=sharedConfig(1,"c".repeat(64));context.deleteDatabase("shared-a.db");context.deleteDatabase("shared-b.db");long now=System.currentTimeMillis();
        try(PeerDatabase ad=new PeerDatabase(context,a,"shared-a.db");PeerDatabase bd=new PeerDatabase(context,b,"shared-b.db")){
            SharedLedger al=new SharedLedger(ad,a),bl=new SharedLedger(bd,b);
            al.scan(sharedRequest(0,"almuerzo"),a.operator(-1),now);bl.scan(sharedRequest(1,"almuerzo"),b.operator(-1),now);
            JSONArray page=al.events(new JSONObject(),20);String value=page.getString(0);JSONObject forged=new JSONObject(value).put("signature","0".repeat(64));
            try{bl.merge(new JSONArray().put(forged.toString()));fail("Bad MAC");}catch(IllegalArgumentException expected){}assertEquals(1,bl.vector().length());
            bl.merge(page);bl.merge(page);al.merge(bl.events(al.vector(),20));
            assertEquals(2,new OfflineLedger(ad,a).records().size());for(JSONObject row:new OfflineLedger(ad,a).records())assertEquals("POSIBLE DUPLICADO",row.getString("revision"));
            for(int d=1;d<=45;d++)al.scan(sharedRequest(0,"cena"),a.operator(-1),now+d*86400000L);
            int pages=0;while(true){JSONArray more=al.events(bl.vector(),20);if(more.length()==0)break;bl.merge(more);pages++;}assertEquals(3,pages);assertEquals(47,new OfflineLedger(bd,b).records().size());
        }finally{context.deleteDatabase("shared-a.db");context.deleteDatabase("shared-b.db");}
    }
    @Test public void leaseUsesMonotonicDeadlineAndFencesLateResponses() throws Exception {
        LeaderLease lease=new LeaderLease("b".repeat(64));JSONObject own=new JSONObject().put("owner",lease.device).put("boot",lease.boot).put("epoch",1).put("remaining",30000);
        lease.update(own,1000,2000);assertTrue(lease.leading(2000));assertFalse(lease.leading(28000));
        lease.update(own,1000,32000);assertFalse(lease.leading(32000));
        JSONObject replacement=new JSONObject(own.toString()).put("owner","c".repeat(64)).put("epoch",2);lease.update(replacement,33000,33001);assertFalse(lease.leading(33001));
        try{lease.update(own,34000,34001);fail("Old epoch resurrected");}catch(IllegalArgumentException expected){}
    }
    @Test public void sharedHttpAuthenticatesAndRejectsExpiredServer() throws Exception {
        PeerConfig c=sharedConfig(0,"b".repeat(64));context.deleteDatabase("shared-http.db");PeerEngine e=new PeerEngine(context,c,"shared-http.db");
        try{
            elect(new PeerEngine[]{e},0,1);e.start();String nonce=PeerConfig.random(),payload="{\"method\":\"status\"}";long time=System.currentTimeMillis();
            JSONObject envelope=new JSONObject().put("epoch",1).put("time",time).put("nonce",nonce).put("payload",payload).put("signature",c.mac("rpc|1|"+time+"|"+nonce+"|"+payload));
            JSONObject result=localSharedHttp(envelope);assertTrue(PeerConfig.equal(c.mac("reply|1|"+nonce+"|"+result.getString("payload")),result.getString("signature")));
            try{localSharedHttp(envelope);fail("Replay accepted");}catch(IOException expected){}
            long now=android.os.SystemClock.elapsedRealtime();e.shared.lease.update(new JSONObject().put("owner","c".repeat(64)).put("boot",PeerConfig.random()).put("epoch",2).put("remaining",30000),now,now);
            assertFalse(e.shared.leading());try{localSharedHttp(envelope);fail("Stale server responded");}catch(IOException expected){}
        }finally{e.close();e.db.close();context.deleteDatabase("shared-http.db");}
    }
    @Test public void uncertainRemoteWriteNeverFallsBackToSecondLocalGrant() throws Exception {
        PeerConfig c=sharedConfig(0,"b".repeat(64));context.deleteDatabase("shared-uncertain.db");PeerEngine e=new PeerEngine(context,c,"shared-uncertain.db");
        try{
            JSONObject request=sharedRequest(0,"almuerzo");e.db.putMeta("remoteAttempt:"+request.getString("requestId"),"1");
            JSONObject reply=e.request(request);assertFalse(reply.getBoolean("ok"));assertFalse(reply.getBoolean("definitive"));assertEquals(0,e.offline.records().size());
        }finally{e.close();e.db.close();context.deleteDatabase("shared-uncertain.db");}
    }
    Context context;PeerDatabase db;PeerConfig config;PeerConfig[] identities;
    @Before public void setup() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();context.deleteDatabase("comedor-peers.db");
        java.security.KeyPairGenerator generator=java.security.KeyPairGenerator.getInstance("EC");generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));java.security.KeyPair[] pairs=new java.security.KeyPair[4];for(int i=0;i<4;i++)pairs[i]=generator.generateKeyPair();
        JSONArray nodes=new JSONArray(),plants=new JSONArray();for(int i=0;i<4;i++){plants.put("Planta "+i);nodes.put(new JSONObject().put("id",i).put("publicKey",Base64.getEncoder().encodeToString(pairs[i].getPublic().getEncoded())).put("name","Encargado "+i).put("plant","Planta "+i).put("role",i==0?"admin":"encargado").put("url","https://node"+i+".example.com"));}
        JSONObject p=new JSONObject().put("id","SAP-12345").put("sap","12345").put("nombre","Persona de prueba").put("planta","Planta 0").put("centroCosto","TEST").put("tipo","EMPLEADO").put("activo",true).put("qrHash",PeerConfig.hash("SB1:"+"a".repeat(64))).put("vence","").put("comidas","desayuno,almuerzo,cena");
        String seed=new JSONObject().put("people",new JSONArray().put(p)).put("plants",plants).put("rates",new JSONArray().put(new JSONObject().put("desde","2020-01-01").put("desayuno",12).put("almuerzo",23).put("cena",19))).toString();
        String manifest=new JSONObject().put("seedHash",PeerConfig.hash(seed)).put("nodes",nodes).toString();
        String raw=new JSONObject().put("format","SOBOCE-P2P-2").put("manifest",manifest).put("seed",seed).put("identityPrivate",Base64.getEncoder().encodeToString(pairs[0].getPrivate().getEncoded())).put("node",0).put("tunnel",new JSONObject().put("TunnelID","00000000-0000-0000-0000-000000000000").put("TunnelSecret","test-only").put("AccountTag","test-only")).toString();identities=new PeerConfig[4];for(int i=0;i<4;i++)identities[i]=new PeerConfig(new JSONObject(raw).put("node",i).put("identityPrivate",Base64.getEncoder().encodeToString(pairs[i].getPrivate().getEncoded())).toString());config=identities[0];db=new PeerDatabase(context,config);
    }
    @After public void cleanup(){db.close();context.deleteDatabase("comedor-peers.db");}
    String scan(int actor,String plant,String meal,String request) throws Exception {return new JSONObject().put("actor",actor).put("time",1793361600000L).put("request",new JSONObject().put("action","scan").put("plant",plant).put("meal",meal).put("qr","SB1:"+"a".repeat(64)).put("requestId",request)).toString();}
    String commit(long slot,String body) throws Exception {JSONObject command=new JSONObject(body);String value=identities[command.getInt("actor")].seal(command);List<QuorumLog.Vote> votes=new ArrayList<>();for(int i=0;i<3;i++)votes.add(new QuorumLog.Vote(i,identities[i].vote(i,slot,4,value)));db.learn(new QuorumLog.Decision(slot,4,value,votes));return value;}
    @Test public void duplicateAcrossPlantsAndRetry() throws Exception {
        String id=UUID.randomUUID().toString(),another=UUID.randomUUID().toString();String first=scan(0,"Planta 0","almuerzo",id);
        commit(1,first);commit(2,scan(1,"Planta 1","almuerzo",another));commit(3,first);
        assertEquals(1,db.rows("meals").size());assertEquals("accepted",db.get("results",id).getJSONObject("reply").getString("status"));assertEquals("duplicate",db.get("results",another).getJSONObject("reply").getString("status"));
        commit(4,scan(0,"Planta 0","cena",UUID.randomUUID().toString()));assertEquals(2,db.rows("meals").size());
    }
    @Test public void persistedPromisesAfterRestart() throws Exception {
        String value=config.seal(new JSONObject(scan(0,"Planta 0","almuerzo",UUID.randomUUID().toString())));db.prepare(1,44);assertNotNull(db.accept(1,44,value));db.close();db=new PeerDatabase(context,config);
        assertFalse(db.prepare(1,40).granted());assertEquals(value,db.prepare(1,48).value());assertNull(db.accept(1,44,value));
    }
    @Test public void aLostResponseRecoveredDuringCatchupDoesNotAuthorizeAgain() throws Exception {
        String id=UUID.randomUUID().toString();String first=scan(0,"Planta 0","almuerzo",id);
        String retry=new JSONObject(first).put("attempt","different-attempt").toString();
        String firstSealed=commit(1,first),retrySealed=commit(2,retry);
        JSONObject saved=db.get("results",id);
        assertEquals("accepted",PeerEngine.replyForAttempt(saved,firstSealed).getString("status"));
        assertEquals("recovered",PeerEngine.replyForAttempt(saved,retrySealed).getString("status"));
        assertEquals("recovered",PeerEngine.replyForAttempt(saved,null).getString("status"));
        assertEquals(1,db.rows("meals").size());
    }
    @Test public void rejectsFakeMajority() throws Exception {
        String value=config.seal(new JSONObject(scan(0,"Planta 0","almuerzo",UUID.randomUUID().toString())));
        try{db.learn(new QuorumLog.Decision(1,4,value,List.of(new QuorumLog.Vote(0,config.vote(0,1,4,value)))));fail("Minority accepted");}catch(IllegalArgumentException expected){}
        assertEquals(0,db.applied());assertEquals(0,db.rows("meals").size());
    }
    @Test public void visitorRenewalPreservesIdentityAndMealLimit() throws Exception {
        String today=MealLedger.day(1793361600000L),firstQr="SB1:"+"c".repeat(64),secondQr="SB1:"+"d".repeat(64);
        JSONObject visitor=new JSONObject().put("action","visitor").put("plant","Planta 1").put("name","Visitante de prueba").put("document","CI BO 1234567").put("costCenter","TEST").put("expires",today).put("meals","almuerzo").put("newQr",firstQr).put("requestId",UUID.randomUUID().toString());
        commit(1,new JSONObject().put("actor",1).put("time",1793361600000L).put("request",visitor).toString());
        JSONObject meal=new JSONObject(scan(1,"Planta 1","almuerzo",UUID.randomUUID().toString()));meal.getJSONObject("request").put("qr",firstQr);commit(2,meal.toString());
        visitor.put("newQr",secondQr).put("requestId",UUID.randomUUID().toString());commit(3,new JSONObject().put("actor",1).put("time",1793361600000L).put("request",visitor).toString());
        String retry=UUID.randomUUID().toString();meal=new JSONObject(scan(1,"Planta 1","almuerzo",retry));meal.getJSONObject("request").put("qr",secondQr);commit(4,meal.toString());
        assertEquals("duplicate",db.get("results",retry).getJSONObject("reply").getString("status"));assertEquals(1,db.rows("meals").size());
        String revoked=UUID.randomUUID().toString();meal=new JSONObject(scan(1,"Planta 1","almuerzo",revoked));meal.getJSONObject("request").put("qr",firstQr);commit(5,meal.toString());assertFalse(db.get("results",revoked).getJSONObject("reply").getBoolean("ok"));
        String wrongPlant=UUID.randomUUID().toString();meal=new JSONObject(scan(2,"Planta 2","almuerzo",wrongPlant));meal.getJSONObject("request").put("qr",secondQr);commit(6,meal.toString());assertFalse(db.get("results",wrongPlant).getJSONObject("reply").getBoolean("ok"));
        assertEquals(2,db.rows("people").size());assertEquals(1,db.rows("meals").size());
    }
    @Test public void cannotImpersonateAnotherSupervisor() throws Exception {
        try{new PeerConfig(new JSONObject(config.raw.toString()).put("node",1).toString());fail("Wrong private identity accepted");}catch(IllegalArgumentException expected){}
        String body=scan(0,"Planta 0","almuerzo",UUID.randomUUID().toString());
        String forged=new JSONObject().put("body",body).put("signature",identities[1].sign("command|"+config.cluster+"|"+body)).toString();
        try{db.accept(1,4,forged);fail("Forged administrator accepted");}catch(IllegalArgumentException expected){}
        assertNull(db.prepare(1,8).value());assertEquals(0,db.applied());
    }
    @Test public void packagedConnectorRunsOnAndroid() throws Exception {
        File binary=new File(context.getApplicationInfo().nativeLibraryDir,"libcloudflared.so");assertTrue(binary.canExecute());
        Process p=new ProcessBuilder(binary.getAbsolutePath(),"--version").redirectErrorStream(true).start();
        try{assertTrue("Connector did not exit",p.waitFor(20,TimeUnit.SECONDS));ByteArrayOutputStream output=new ByteArrayOutputStream();try(InputStream in=p.getInputStream()){byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1)output.write(b,0,n);}assertEquals(output.toString("UTF-8"),0,p.exitValue());assertTrue(output.toString("UTF-8").contains("2026.10.6"));}finally{p.destroy();}
    }
    JSONObject workerRequest(String plant,String id) throws Exception {return new JSONObject().put("action","issue").put("plant",plant).put("personId",id);}
    JSONObject visitorRequest(String plant,long time) throws Exception {return new JSONObject().put("action","visitor").put("plant",plant).put("name","Visita sin conexión").put("document","CI BO 1234567").put("costCenter","TEST").put("expires",MealLedger.day(time)).put("meals","almuerzo");}
    String consumeCard(int actor,String plant,String meal,String qr,long when) throws Exception {
        String id=UUID.randomUUID().toString();JSONObject command=new JSONObject(scan(actor,plant,meal,id)).put("time",when);command.getJSONObject("request").put("qr",qr);commit(db.applied()+1,command.toString());return id;
    }
    @Test public void supervisorIssuesOwnPlantOfflineWithoutChangingOldQrOrLog() throws Exception {
        JSONObject person=new JSONObject(db.get("people","SAP-12345").toString()).put("id","SAP-67890").put("sap","67890").put("planta","Planta 1");db.save("people","SAP-67890",person);
        JSONObject card=PeerCards.request(db,identities[1],workerRequest("Planta 1","SAP-67890"),1793361600000L);
        assertTrue(card.getBoolean("ok"));assertTrue(PeerCards.format(card.getString("qr")));
        assertEquals("SAP-67890",PeerCards.resolve(db,config,card.getString("qr"),1793361600000L).getString("id"));
        assertEquals(0,db.applied());assertEquals(0,db.history(0,20).length());
        assertEquals(person.getString("qrHash"),db.get("people","SAP-67890").getString("qrHash"));
        assertFalse(PeerCards.request(db,identities[1],workerRequest("Planta 0","SAP-12345"),1793361600000L).getBoolean("ok"));
    }
    @Test public void cardsWorkWithNetworkEngineStopped() throws Exception {
        PeerEngine offline=new PeerEngine(context,config);offline.close();
        try {
            assertTrue(offline.request(workerRequest("Planta 0","SAP-12345")).getBoolean("ok"));
            assertTrue(offline.request(visitorRequest("Planta 0",System.currentTimeMillis())).getBoolean("ok"));
            assertEquals(0,offline.db.applied());
        } finally {offline.db.close();}
    }
    @Test public void newCardsCopiesAndLegacyQrShareTheSameMealLimit() throws Exception {
        long now=1793361600000L;
        String first=PeerCards.request(db,config,workerRequest("Planta 0","SAP-12345"),now).getString("qr");
        String copy=PeerCards.request(db,config,workerRequest("Planta 0","SAP-12345"),now).getString("qr");
        String a=consumeCard(1,"Planta 1","almuerzo",first,now),b=consumeCard(0,"Planta 0","almuerzo",copy,now),c=consumeCard(2,"Planta 2","almuerzo","SB1:"+"a".repeat(64),now);
        assertEquals("accepted",db.get("results",a).getJSONObject("reply").getString("status"));
        for(String id:new String[]{b,c})assertEquals("duplicate",db.get("results",id).getJSONObject("reply").getString("status"));
        assertEquals(1,db.rows("meals").size());
    }
    @Test public void offlineVisitorCardsHaveGlobalIdentityAndEnforceHostExpiryAndMeals() throws Exception {
        long now=1793361600000L;
        String atOne=PeerCards.request(db,identities[1],visitorRequest("Planta 1",now),now).getString("qr");
        String atZero=PeerCards.request(db,config,visitorRequest("Planta 0",now).put("document","ci-bo-1234567"),now).getString("qr");
        assertEquals(1,db.rows("people").size()); // Issuing alone does not change the shared projection.
        String accepted=consumeCard(1,"Planta 1","almuerzo",atOne,now),duplicate=consumeCard(0,"Planta 0","almuerzo",atZero,now);
        assertEquals("accepted",db.get("results",accepted).getJSONObject("reply").getString("status"));
        assertEquals("duplicate",db.get("results",duplicate).getJSONObject("reply").getString("status"));
        for(String id:new String[]{consumeCard(0,"Planta 0","almuerzo",atOne,now),consumeCard(1,"Planta 1","cena",atOne,now),consumeCard(1,"Planta 1","almuerzo",atOne,now+86400000L)})assertFalse(db.get("results",id).getJSONObject("reply").getBoolean("ok"));
        assertEquals(1,db.rows("meals").size());
    }
    @Test public void tamperedForeignAndOutOfScopeSignedCardsAreRejected() throws Exception {
        long now=1793361600000L;String qr=PeerCards.request(db,config,workerRequest("Planta 0","SAP-12345"),now).getString("qr");
        String[] parts=qr.substring(4).split("\\.");JSONObject body=new JSONObject(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8));body.put("id","SAP-99999");
        String tampered="SB2:"+Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().getBytes(StandardCharsets.UTF_8))+"."+parts[1];
        assertFalse(db.get("results",consumeCard(0,"Planta 0","almuerzo",tampered,now)).getJSONObject("reply").getBoolean("ok"));
        body.put("id","SAP-12345").put("issuer",1);String text=body.toString();String outOfScope="SB2:"+Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8))+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getDecoder().decode(identities[1].sign("card|"+config.cluster+"|"+text)));
        assertFalse(db.get("results",consumeCard(0,"Planta 0","almuerzo",outOfScope,now)).getJSONObject("reply").getBoolean("ok"));
        JSONObject manifest=new JSONObject(config.raw.getString("manifest")).put("otherNetwork",true);
        PeerConfig foreign=new PeerConfig(new JSONObject(config.raw.toString()).put("manifest",manifest.toString()).toString());
        String other=PeerCards.request(db,foreign,workerRequest("Planta 0","SAP-12345"),now).getString("qr");
        assertFalse(db.get("results",consumeCard(0,"Planta 0","almuerzo",other,now)).getJSONObject("reply").getBoolean("ok"));assertEquals(0,db.rows("meals").size());
    }
    @Test public void updateReplaysHistoryAndRetainsAcceptedVotes() throws Exception {
        long now=1793361600000L;consumeCard(0,"Planta 0","almuerzo","SB1:"+"a".repeat(64),now);
        String qr=PeerCards.request(db,config,workerRequest("Planta 0","SAP-12345"),now).getString("qr");String id=consumeCard(0,"Planta 0","cena",qr,now);
        String pending=config.seal(new JSONObject(scan(0,"Planta 0","desayuno",UUID.randomUUID().toString())));db.prepare(3,100);db.accept(3,100,pending);long oldBallot=db.nextBallot(0,100);
        db.getWritableDatabase().delete("meals",null,null);db.getWritableDatabase().delete("results",null,null);db.getWritableDatabase().setVersion(1);db.close();
        db=new PeerDatabase(context,config);
        assertEquals(2,db.applied());assertEquals(2,db.rows("meals").size());assertEquals("accepted",db.get("results",id).getJSONObject("reply").getString("status"));
        assertFalse(db.prepare(3,96).granted());assertEquals(pending,db.prepare(3,104).value());assertTrue(db.nextBallot(0,oldBallot)>oldBallot);
    }
    @Test public void olderProposersCannotFormAMixedVersionQuorum() throws Exception {
        PeerEngine engine=new PeerEngine(context,config);
        try{
            assertEquals(3,engine.dispatch(new JSONObject().put("method","status")).getInt("protocol"));
            try{engine.dispatch(new JSONObject().put("method","prepare").put("slot",1).put("ballot",4));fail("Old proposer received a promise");}catch(IllegalArgumentException expected){}
            try{engine.dispatch(new JSONObject().put("method","prepare").put("protocol",2).put("slot",1).put("ballot",4));fail("Legacy vote accepted");}catch(IllegalArgumentException expected){}
            assertEquals(0,engine.dispatch(new JSONObject().put("method","events").put("protocol",3).put("after",new JSONArray("[0,0,0,0]"))).getJSONArray("items").length());
        }finally{engine.close();engine.db.close();}
    }
    @Test public void offlineVisitorQrCanBeReadByTheScanner() throws Exception {
        String qr=PeerCards.request(db,identities[1],visitorRequest("Planta 1",1793361600000L).put("name","Visitante de prueba con nombre completo"),1793361600000L).getString("qr");
        com.google.zxing.common.BitMatrix matrix=new com.google.zxing.MultiFormatWriter().encode(qr,com.google.zxing.BarcodeFormat.QR_CODE,700,700);int[] pixels=new int[700*700];
        for(int y=0;y<700;y++)for(int x=0;x<700;x++)pixels[y*700+x]=matrix.get(x,y)?0xff000000:0xffffffff;
        com.google.zxing.BinaryBitmap image=new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(700,700,pixels)));
        assertEquals(qr,new com.google.zxing.qrcode.QRCodeReader().decode(image).getText());
    }
    @Test public void androidConnectorReachesOriginThroughCloudflare() throws Exception {
        // Ephemeral tunnel with synthetic data only; no production credentials in CI.
        File binary=new File(context.getApplicationInfo().nativeLibraryDir,"libcloudflared.so");
        ServerSocket origin=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        Thread responder=new Thread(()->{while(!origin.isClosed())try(Socket s=origin.accept()){
            s.setSoTimeout(3000);InputStream in=s.getInputStream();int tail=0,b,count=0;while((b=in.read())!=-1&&count++<8192){tail=(tail<<8)|b;if(tail==0x0d0a0d0a)break;}
            byte[] body="SOBOCE-CONNECTOR-TEST".getBytes(StandardCharsets.UTF_8);OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
        }catch(IOException ignored){}});responder.setDaemon(true);responder.start();
        ProcessBuilder pb=new ProcessBuilder(binary.getAbsolutePath(),"tunnel","--no-autoupdate","--protocol","http2","--url","http://127.0.0.1:"+origin.getLocalPort());pb.environment().put("HOME",context.getCacheDir().getAbsolutePath());pb.redirectErrorStream(true);Process process=pb.start();
        AtomicReference<String> address=new AtomicReference<>();StringBuffer diagnostic=new StringBuffer();
        Thread logs=new Thread(()->{try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){String line;Pattern pattern=Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");while((line=reader.readLine())!=null){Matcher m=pattern.matcher(line);if(m.find())address.set(m.group());synchronized(diagnostic){if(diagnostic.length()>5000)diagnostic.delete(0,diagnostic.length()-3000);diagnostic.append(line).append('\n');}}}catch(IOException ignored){}});logs.setDaemon(true);logs.start();
        boolean reached=false;long deadline=System.currentTimeMillis()+120000;
        try{
            while(System.currentTimeMillis()<deadline&&process.isAlive()){
                if(address.get()!=null){HttpURLConnection c=(HttpURLConnection)new URL(address.get()+"/probe").openConnection();c.setConnectTimeout(3000);c.setReadTimeout(3000);
                    try{if(c.getResponseCode()==200){ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){int b;while((b=in.read())!=-1&&bytes.size()<100)bytes.write(b);}if(bytes.toString("UTF-8").equals("SOBOCE-CONNECTOR-TEST")){reached=true;break;}}}catch(IOException ignored){}finally{c.disconnect();}}
                Thread.sleep(1500);
            }
            assertTrue("Android tunnel did not reach its origin: "+diagnostic,reached);
        }finally{process.destroy();origin.close();}
    }

    JSONObject offlineRequest(int actor,String meal) throws Exception {return new JSONObject(scan(actor,"Planta "+actor,meal,UUID.randomUUID().toString())).getJSONObject("request");}
    @Test public void singlePhoneRecordsAllMealsOfflineAndRecoversAfterRestart() throws Exception {
        long now=1793361600000L;OfflineLedger ledger=new OfflineLedger(db,config);JSONObject lunch=offlineRequest(0,"almuerzo");
        for(String meal:new String[]{"desayuno","almuerzo","cena"})assertEquals("accepted",ledger.scan(meal.equals("almuerzo")?lunch:offlineRequest(0,meal),now).getString("status"));
        assertEquals(3,ledger.records().size());assertEquals(0,db.applied());assertEquals(3,ledger.pending());
        db.close();db=new PeerDatabase(context,config);ledger=new OfflineLedger(db,config);
        assertEquals("recovered",ledger.scan(lunch,now).getString("status"));
        assertEquals("duplicate",ledger.scan(offlineRequest(0,"almuerzo"),now).getString("status"));
        assertEquals(3,ledger.records().size());
        JSONObject future=ledger.scan(offlineRequest(0,"almuerzo"),now+86400000L);assertEquals("accepted",future.getString("status"));
        assertFalse(ledger.scan(offlineRequest(0,"cena"),now).getBoolean("ok"));
        try{ledger.scan(new JSONObject(lunch.toString()).put("meal","cena"),now);fail("Request ID reused");}catch(IllegalArgumentException expected){}
    }
    @Test public void engineCanRegisterWithNetworkStopped() throws Exception {
        PeerEngine engine=new PeerEngine(context,config);engine.close();
        try{assertEquals("accepted",engine.request(offlineRequest(0,"almuerzo")).getString("status"));assertEquals(1,engine.offline.records().size());}
        finally{engine.db.close();}
    }
    @Test public void knownHistoryAndVisitorRulesStillBlockOfflineDuplicates() throws Exception {
        long now=1793361600000L;String id=UUID.randomUUID().toString();commit(1,scan(0,"Planta 0","almuerzo",id));
        OfflineLedger ledger=new OfflineLedger(db,config);
        assertEquals("duplicate",ledger.scan(offlineRequest(0,"almuerzo"),now).getString("status"));
        JSONObject recovered=new JSONObject(scan(0,"Planta 0","almuerzo",id)).getJSONObject("request");
        assertEquals("recovered",ledger.scan(recovered,now).getString("status"));
        String qr=PeerCards.request(db,config,visitorRequest("Planta 0",now),now).getString("qr");
        assertFalse(ledger.scan(offlineRequest(0,"cena").put("qr",qr),now).getBoolean("ok"));
        assertFalse(ledger.scan(offlineRequest(0,"almuerzo").put("plant","Planta 1").put("qr",qr),now).getBoolean("ok"));
        assertEquals("accepted",ledger.scan(offlineRequest(0,"almuerzo").put("qr",qr),now).getString("status"));
        assertFalse(ledger.scan(offlineRequest(0,"almuerzo").put("qr",qr),now+86400000L).getBoolean("ok"));
    }
    @Test public void simultaneousScansInTwoDatabaseConnectionsAuthorizeOnlyOnce() throws Exception {
        PeerDatabase second=new PeerDatabase(context,config);
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try{
            java.util.concurrent.Future<String> a=pool.submit(()->{start.await();return new OfflineLedger(db,config).scan(offlineRequest(0,"almuerzo"),1793361600000L).getString("status");});
            java.util.concurrent.Future<String> b=pool.submit(()->{start.await();return new OfflineLedger(second,config).scan(offlineRequest(0,"almuerzo"),1793361600000L).getString("status");});
            start.countDown();List<String> replies=Arrays.asList(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertTrue(replies.contains("accepted"));assertTrue(replies.contains("duplicate"));assertEquals(1,new OfflineLedger(db,config).records().size());
        }finally{pool.shutdownNow();second.close();}
    }
    @Test public void signedReplicationIsIdempotentAtomicAndRejectsAlteredReceipts() throws Exception {
        long now=1793361600000L;OfflineLedger origin=new OfflineLedger(db,config);
        origin.scan(offlineRequest(0,"almuerzo"),now);origin.scan(offlineRequest(0,"cena"),now);
        JSONArray events=origin.events(new JSONArray("[0,0,0,0]"),20);
        context.deleteDatabase("offline-test.db");PeerDatabase other=new PeerDatabase(context,identities[1],"offline-test.db");
        try{
            OfflineLedger remote=new OfflineLedger(other,identities[1]);
            try{remote.merge(new JSONArray().put(events.getString(1)));fail("Missing predecessor accepted");}catch(IllegalArgumentException expected){}
            JSONObject envelope=new JSONObject(events.getString(1));JSONObject body=new JSONObject(envelope.getString("body"));body.getJSONObject("record").put("precio",0);envelope.put("body",body.toString());
            try{remote.merge(new JSONArray().put(events.getString(0)).put(envelope.toString()));fail("Altered receipt accepted");}catch(IllegalArgumentException expected){}
            assertEquals(0,remote.records().size());assertEquals(0,remote.vector().getLong(0));
            remote.merge(events);remote.merge(events);assertEquals(2,remote.records().size());
            assertEquals("duplicate",remote.scan(offlineRequest(1,"almuerzo"),now).getString("status"));
            JSONObject divergent=config.command(events.getString(0));divergent.getJSONObject("record").put("nombre","Nombre cambiado");
            try{remote.merge(new JSONArray().put(config.seal(divergent)));fail("Forked stream accepted");}catch(IllegalArgumentException expected){}
            assertEquals(2,remote.records().size());
        }finally{other.close();context.deleteDatabase("offline-test.db");}
    }
    @Test public void disconnectedPhonesConvergeAndPreserveBothConflictingDeliveries() throws Exception {
        PeerEngine[] engines=new PeerEngine[4];boolean[] available={true,true,false,false};
        for(int node=0;node<4;node++){
            context.deleteDatabase("sync-test-"+node+".db");
            engines[node]=new PeerEngine(context,identities[node],"sync-test-"+node+".db"){
                @Override JSONObject rpc(int peer,JSONObject request) throws Exception {
                    if(!available[config.node]||!available[peer])throw new IOException("Offline simulation");
                    JSONObject wire=new JSONObject(request.toString());if(!wire.has("protocol"))wire.put("protocol",PROTOCOL);
                    return new JSONObject(engines[peer].dispatch(wire).toString());
                }
            };
        }
        try{
            long now=1793361600000L;
            assertEquals("accepted",engines[0].offline.scan(offlineRequest(0,"almuerzo"),now).getString("status"));
            assertEquals("accepted",engines[1].offline.scan(offlineRequest(1,"almuerzo"),now).getString("status"));
            engines[1].sync();assertEquals(2,engines[0].offline.records().size());assertEquals(2,engines[1].offline.records().size());
            available[0]=false;available[2]=true;available[3]=true;engines[2].sync();engines[3].sync();
            assertEquals(2,engines[3].offline.records().size()); // Node 0's receipt was relayed while node 0 was off.
            for(PeerEngine e:engines){
                assertEquals(2,e.offline.records().size());for(JSONObject r:e.offline.records())assertEquals("POSIBLE DUPLICADO",r.getString("revision"));
                assertEquals("duplicate",e.offline.scan(offlineRequest(e.config.node,"almuerzo"),now).getString("status"));
            }
            JSONObject report=new MealLedger(engines[0].db,identities[0]).read(new JSONObject().put("action","report").put("plant","Planta 0").put("month","2026-10"));
            assertEquals(1,report.getInt("count"));assertTrue(report.getString("csv").contains("POSIBLE DUPLICADO"));
            assertEquals(1,engines[0].offline.pending());available[0]=true;engines[0].sync();assertEquals(0,engines[0].offline.pending());
            String canonical=engines[0].offline.records().toString();for(PeerEngine e:engines)assertEquals(canonical,e.offline.records().toString());
        }finally{for(int i=0;i<4;i++){engines[i].close();engines[i].db.close();context.deleteDatabase("sync-test-"+i+".db");}}
    }
    @Test public void paginatedCatchupResumesAndNewPhoneReceivesOlderDays() throws Exception {
        long now=1793361600000L;OfflineLedger origin=new OfflineLedger(db,config);
        for(int day=0;day<45;day++)assertEquals("accepted",origin.scan(offlineRequest(0,"almuerzo"),now+day*86400000L).getString("status"));
        context.deleteDatabase("page-test.db");PeerDatabase other=new PeerDatabase(context,identities[2],"page-test.db");
        try{
            OfflineLedger remote=new OfflineLedger(other,identities[2]);JSONArray first=origin.events(remote.vector(),20);assertEquals(20,first.length());remote.merge(first);
            other.close();other=new PeerDatabase(context,identities[2],"page-test.db");remote=new OfflineLedger(other,identities[2]);remote.merge(first);
            for(int i=0;i<3;i++){JSONArray page=origin.events(remote.vector(),20);remote.merge(page);}
            assertEquals(45,remote.records().size());assertEquals(45,remote.vector().getLong(0));assertEquals(0,origin.events(remote.vector(),20).length());
        }finally{other.close();context.deleteDatabase("page-test.db");}
    }
    @Test public void upgradeFromVersionTwoKeepsHistoryAndOfflineReceipts() throws Exception {
        long now=1793361600000L;commit(1,scan(0,"Planta 0","desayuno",UUID.randomUUID().toString()));
        String pending=config.seal(new JSONObject(scan(0,"Planta 0","cena",UUID.randomUUID().toString())));db.prepare(2,100);db.accept(2,100,pending);
        db.getWritableDatabase().setVersion(2);db.close();db=new PeerDatabase(context,config);
        assertEquals(1,db.applied());assertEquals(pending,db.prepare(2,104).value());
        OfflineLedger ledger=new OfflineLedger(db,config);assertEquals("duplicate",ledger.scan(offlineRequest(0,"desayuno"),now).getString("status"));
        assertEquals("accepted",ledger.scan(offlineRequest(0,"almuerzo"),now).getString("status"));
        db.close();db=new PeerDatabase(context,config);assertEquals(2,new OfflineLedger(db,config).records().size());
    }


    void clearSetupVault(Vault vault){for(String key:new String[]{"peerConfig","mode","setupPending","pending"})vault.remove(key);}
    @Test public void bundledInstallerConfiguresAnEmptyPhoneWithoutImportingAFile() throws Exception {
        Vault vault=new Vault(context);clearSetupVault(vault);db.close();context.deleteDatabase("comedor-peers.db");
        try{
            BundledSetup.apply(context,vault,identities[1].raw.toString());
            assertEquals(1,new PeerConfig(vault.get("peerConfig")).node);assertEquals("p2p",vault.get("mode"));assertEquals("",vault.get("setupPending"));
            db=new PeerDatabase(context,identities[1]);assertEquals("1",db.meta("node",""));assertEquals(1,db.rows("people").size());
        }finally{clearSetupVault(vault);}
    }
    @Test public void bundledInstallerRepairsDuplicatedIdentityOnlyWhenNoHistoryExists() throws Exception {
        Vault vault=new Vault(context);clearSetupVault(vault);vault.put("peerConfig",config.raw.toString());db.close();
        try{
            BundledSetup.apply(context,vault,identities[1].raw.toString());db=new PeerDatabase(context,identities[1]);
            assertEquals("1",db.meta("node",""));assertEquals(0,db.applied());
            new OfflineLedger(db,identities[1]).scan(offlineRequest(1,"almuerzo"),1793361600000L);db.close();
            try{BundledSetup.apply(context,vault,identities[2].raw.toString());fail("Historical identity changed");}catch(IllegalArgumentException expected){}
            assertEquals(1,new PeerConfig(vault.get("peerConfig")).node);db=new PeerDatabase(context,identities[1]);assertEquals(1,new OfflineLedger(db,identities[1]).records().size());
        }finally{clearSetupVault(vault);}
    }
    @Test public void interruptedBundledSetupResumesWithoutDiscardingData() throws Exception {
        Vault vault=new Vault(context);clearSetupVault(vault);vault.put("peerConfig",config.raw.toString());vault.put("setupPending",identities[1].raw.toString());
        db.putMeta("node","1");db.close();
        try{
            BundledSetup.install(context,vault);assertEquals(1,new PeerConfig(vault.get("peerConfig")).node);assertEquals("p2p",vault.get("mode"));
            vault.put("setupPending",identities[1].raw.toString());vault.remove("mode");BundledSetup.install(context,vault);assertEquals("p2p",vault.get("mode"));
            db=new PeerDatabase(context,identities[1]);assertEquals(1,db.rows("people").size());
        }finally{clearSetupVault(vault);}
    }
    @Test public void tunnelDiagnosticsDoNotExposeCredentialLines() throws Exception {
        TunnelHealth health=new TunnelHealth();health.line("ERR Unauthorized secret=DO-NOT-DISPLAY");assertFalse(health.message().contains("DO-NOT-DISPLAY"));
        health.line("INF Registered tunnel connection connIndex=0 connection=private-value");assertTrue(health.message().contains("1 conexiones"));assertFalse(health.message().contains("private-value"));
        health.line("ERR Connection terminated connIndex=0");assertFalse(health.message().contains("Túnel conectado"));health.exited(1);assertTrue(health.message().contains("código 1"));
        assertFalse(PeerEngine.duplicateIdentity("one",new JSONObject().put("instance","two")).isEmpty());assertTrue(PeerEngine.duplicateIdentity("one",new JSONObject().put("instance","one")).isEmpty());
    }
    @Test public void publicHealthEndpointAndSignedRpcReachTheActualServer() throws Exception {
        PeerEngine engine=new PeerEngine(context,config);engine.start();
        try{
            try(Socket socket=new Socket("127.0.0.1",8787)){
                socket.setSoTimeout(5000);socket.getOutputStream().write("GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(response.startsWith("HTTP/1.1 200"));assertTrue(response.contains("SOBOCE Comedor"));assertFalse(response.contains("identityPrivate"));
            }
            String nonce=PeerConfig.random(),payload=new JSONObject().put("method","status").put("protocol",3).toString();long now=System.currentTimeMillis();
            JSONObject envelope=new JSONObject().put("source",1).put("time",now).put("nonce",nonce).put("payload",payload).put("signature",identities[1].sign("request|"+config.cluster+"|1|"+now+"|"+nonce+"|"+payload));byte[] body=envelope.toString().getBytes(StandardCharsets.UTF_8);
            try(Socket socket=new Socket("127.0.0.1",8787)){
                socket.setSoTimeout(5000);OutputStream out=socket.getOutputStream();out.write(("POST /rpc HTTP/1.1\r\nHost: localhost\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
                String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(response.startsWith("HTTP/1.1 200"));JSONObject reply=new JSONObject(response.substring(response.indexOf("\r\n\r\n")+4));assertTrue(config.verify(0,"reply|"+nonce+"|"+reply.getString("payload"),reply.getString("signature")));assertEquals(engine.instance,new JSONObject(reply.getString("payload")).getString("instance"));
            }
        }finally{engine.close();engine.db.close();}
    }


    @Test public void duplicateIdentityWarningRemainsAfterTheNextRequestHitsThisPhone() throws Exception {
        PeerEngine engine=new PeerEngine(context,config){int calls;@Override JSONObject remoteRpc(int peer,JSONObject request) throws Exception {return new JSONObject().put("instance",calls++==0?"another-installation":instance);}};
        try{engine.checkOwnIdentity();assertFalse(engine.identityWarning.isEmpty());engine.checkOwnIdentity();assertFalse(engine.identityWarning.isEmpty());}
        finally{engine.close();engine.db.close();}
    }

}
