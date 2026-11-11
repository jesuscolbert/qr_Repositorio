package bo.soboce.comedor;

import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;
import bo.colbert.licensing.*;

@RunWith(AndroidJUnit4.class)
public class CompaniesTest {
 Context context;JSONObject previous;boolean loaded;Map<String,Map<String,?>> saved=new HashMap<>();long now=System.currentTimeMillis();
 @Before public void setup()throws Exception{
  context=InstrumentationRegistry.getInstrumentation().getTargetContext();previous=Companies.catalog;loaded=Companies.loaded;
  for(String name:new String[]{"vault","vault-frigor","company-selection","colbert-secrets","colbert-secrets-frigor"}){SharedPreferences p=context.getSharedPreferences(name,0);saved.put(name,new HashMap<>(p.getAll()));p.edit().clear().commit();}
  context.deleteDatabase("comedor-peers.db");context.deleteDatabase("comedor-peers-frigor.db");
  Companies.catalog=new JSONObject().put("format","COLBERT-COMPANIES-1").put("companies",new JSONArray().put(company("soboce","a")).put(company("frigor","b")));Companies.validate(Companies.catalog);Companies.loaded=true;Companies.clearClients();
 }
 @After public void cleanup(){
  context.stopService(new Intent(context,PeerService.class));Companies.clearClients();Companies.catalog=previous;Companies.loaded=loaded;
  for(String name:saved.keySet()){SharedPreferences.Editor e=context.getSharedPreferences(name,0).edit().clear();for(Map.Entry<String,?> item:saved.get(name).entrySet()){Object v=item.getValue();if(v instanceof String)e.putString(item.getKey(),(String)v);else if(v instanceof Boolean)e.putBoolean(item.getKey(),(Boolean)v);else if(v instanceof Integer)e.putInt(item.getKey(),(Integer)v);else if(v instanceof Long)e.putLong(item.getKey(),(Long)v);}e.commit();}
  context.deleteDatabase("comedor-peers.db");context.deleteDatabase("comedor-peers-frigor.db");
 }
 JSONObject company(String id,String key)throws Exception{
  JSONArray plants=new JSONArray().put("Planta Norte").put("Planta Sur"),nodes=new JSONArray();
  KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));String pub=Base64.getEncoder().encodeToString(g.generateKeyPair().getPublic().getEncoded());
  for(int i=0;i<4;i++)nodes.put(new JSONObject().put("id",i).put("name","Encargado "+i).put("plant","Planta Norte").put("role","encargado").put("url","https://node"+i+".example.com").put("publicKey",pub));
  String seed=new JSONObject().put("people",new JSONArray()).put("plants",plants).put("rates",new JSONArray().put(new JSONObject().put("desde","2020-01-01").put("desayuno",0).put("almuerzo",0).put("cena",0))).toString();
  String manifest=new JSONObject().put("installation",id+"-test").put("seedHash",PeerConfig.hash(seed)).put("nodes",nodes).toString();
  JSONObject network=new JSONObject().put("format","SOBOCE-SHARED-BUNDLE-1").put("seed",seed).put("manifest",manifest).put("networkKey",key.repeat(64)).put("url","https://"+id+".example.com").put("coordinator","https://"+id+"-coord.example.com").put("tunnel",new JSONObject().put("TunnelID","00000000-0000-0000-0000-000000000000").put("TunnelSecret","test-only-"+id).put("AccountTag","test-only"));
  JSONObject license=new JSONObject().put("version",1).put("url","https://billing.example.com").put("contractId",id+"-comedor").put("productId",id+"-comedor").put("token",LicenseProtocol.random());
  return new JSONObject().put("id",id).put("network",network).put("license",license);
 }
 PeerConfig config(String id)throws Exception{return new PeerConfig(new Vault(context,id).get("peerConfig"));}
 JSONObject worker(String plant)throws Exception{return new JSONObject().put("action","worker").put("plant",plant).put("document","0012345").put("name","Trabajador de prueba");}
 JSONObject scan(String qr,String meal,String plant)throws Exception{return new JSONObject().put("action","scan").put("requestId",UUID.randomUUID().toString()).put("qr",qr).put("meal",meal).put("plant",plant);}
 @Test public void twoCompaniesKeepIndependentWorkersMealsQrAndAllPlants()throws Exception{
  Companies.install(context);PeerConfig a=config("soboce"),b=config("frigor");assertNotEquals(a.cluster,b.cluster);assertNotEquals(a.device,b.device);
  try(PeerDatabase ad=new PeerDatabase(context,a);PeerDatabase bd=new PeerDatabase(context,b)){
   assertNotEquals(ad.getDatabaseName(),bd.getDatabaseName());assertEquals("comedor-peers.db",ad.getDatabaseName());
   assertEquals(2,PeerCards.request(ad,a,new JSONObject().put("action","session"),now).getJSONArray("plants").length());
   String aq=PeerCards.request(ad,a,worker("Planta Sur"),now).getString("qr"),bq=PeerCards.request(bd,b,worker("Planta Norte"),now).getString("qr");assertNotEquals(aq,bq);
   assertEquals(1,ad.rows("people").size());assertEquals(1,bd.rows("people").size());
   assertEquals("accepted",new SharedLedger(ad,a).scan(scan(aq,"almuerzo","Planta Sur"),a.operator(-1),now).getString("status"));
   assertEquals("accepted",new SharedLedger(bd,b).scan(scan(bq,"almuerzo","Planta Norte"),b.operator(-1),now).getString("status"));
   assertEquals("duplicate",new SharedLedger(ad,a).scan(scan(aq,"dieta","Planta Norte"),a.operator(-1),now).getString("status"));
   try{PeerCards.resolve(bd,b,aq,now);fail("QR crossed companies");}catch(IllegalArgumentException expected){}
   assertEquals(1,new OfflineLedger(ad,a).records().size());assertEquals(1,new OfflineLedger(bd,b).records().size());
  }
  new Vault(context,"soboce").put("pending","pending-soboce");new Vault(context,"frigor").put("pending","pending-frigor");Companies.select(context,"frigor");assertEquals("pending-frigor",new Vault(context).get("pending"));Companies.select(context,"soboce");assertEquals("pending-soboce",new Vault(context).get("pending"));
  Companies.install(context);assertEquals(a.device,config("soboce").device);assertEquals(b.device,config("frigor").device);
 }
 void permission(String id,boolean allowed)throws Exception{
  SecretStore vault=new SecretStore(context,Companies.suffix(context,id));KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));KeyPair pair=g.generateKeyPair();
  JSONObject claims=new JSONObject().put("version",1).put("contractId",id+"-comedor").put("productId",id+"-comedor").put("nonce",LicenseProtocol.random()).put("issuedAt",now).put("expiresAt",allowed?now+LicenseProtocol.OFFLINE_MS:now).put("paidUntil",now+30*LicenseProtocol.OFFLINE_MS).put("allowed",allowed);
  vault.put("serverKey",Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));vault.put("permit",new JSONObject().put("envelope",LicenseProtocol.sign(claims,pair.getPrivate(),pair.getPublic())).put("elapsed",android.os.SystemClock.elapsedRealtime()).put("boot",android.provider.Settings.Global.getInt(context.getContentResolver(),"boot_count",-1)).toString());
 }
 @Test public void billingSeparatesCompaniesAndBlocksWorkerMutationsInOnlyTheUnpaidCompany()throws Exception{
  Companies.install(context);permission("soboce",true);permission("frigor",false);LicenseClient a=Companies.license(context,"soboce"),b=Companies.license(context,"frigor");assertTrue(a.allowed());assertFalse(b.allowed());
  try(PeerEngine ae=new PeerEngine(context,config("soboce"));PeerEngine be=new PeerEngine(context,config("frigor"))){
   assertTrue(ae.request(worker("Planta Sur")).optBoolean("ok"));assertFalse(be.request(worker("Planta Sur")).optBoolean("ok"));assertEquals(0,be.db.rows("people").size());
  }
  Companies.select(context,"frigor");assertSame(b,LicenseClient.get(context));Companies.select(context,"soboce");assertSame(a,LicenseClient.get(context));assertTrue(a.allowed());
 }
 @Test public void legacyPrimaryHistoryAndPinnedBillingIdentitySurviveAddingAnotherCompany()throws Exception{
  SharedSetup.apply(new Vault(context,"soboce"),Companies.item(context,"soboce").getJSONObject("network").toString());PeerConfig before=config("soboce");
  String qr;try(PeerDatabase db=new PeerDatabase(context,before)){qr=PeerCards.request(db,before,worker("Planta Norte"),now).getString("qr");assertEquals("accepted",new SharedLedger(db,before).scan(scan(qr,"cena","Planta Norte"),before.operator(-1),now).getString("status"));}
  SecretStore vault=new SecretStore(context);vault.put("clientConfig",Companies.item(context,"soboce").getJSONObject("license").toString());permission("soboce",true);String pinned=vault.get("serverKey"),permit=vault.get("permit");
  Companies.install(context);PeerConfig after=config("soboce");assertEquals(before.device,after.device);assertEquals(before.cluster,after.cluster);assertEquals("admin",after.operator(-1).getString("role"));
  assertTrue(Companies.license(context,"soboce").allowed());assertEquals(pinned,vault.get("serverKey"));assertEquals(permit,vault.get("permit"));
  try(PeerDatabase db=new PeerDatabase(context,after)){assertEquals(1,db.rows("people").size());assertEquals(1,new OfflineLedger(db,after).records().size());assertNotNull(PeerCards.resolve(db,after,qr,now));}
 }
 @Test public void companyPickerSwitchesScreensAndKeepsPlantSelection()throws Exception{
  Companies.install(context);for(String id:new String[]{"soboce","frigor"}){new Vault(context,id).remove("needsProfile");permission(id,true);}
  PeerDatabaseTest ui=new PeerDatabaseTest();ui.shellUi("pm grant "+context.getPackageName()+" android.permission.POST_NOTIFICATIONS");
  java.util.concurrent.atomic.AtomicReference<MainActivity> current=new java.util.concurrent.atomic.AtomicReference<>();
  android.app.Application app=(android.app.Application)context.getApplicationContext();
  android.app.Application.ActivityLifecycleCallbacks listener=new android.app.Application.ActivityLifecycleCallbacks(){
   public void onActivityResumed(android.app.Activity a){if(a instanceof MainActivity)current.set((MainActivity)a);}
   public void onActivityCreated(android.app.Activity a,android.os.Bundle b){}public void onActivityStarted(android.app.Activity a){}public void onActivityPaused(android.app.Activity a){}public void onActivityStopped(android.app.Activity a){}public void onActivitySaveInstanceState(android.app.Activity a,android.os.Bundle b){}public void onActivityDestroyed(android.app.Activity a){}
  };app.registerActivityLifecycleCallbacks(listener);
  MainActivity first=(MainActivity)InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try{
   ui.waitText(first,"INICIAR ESCANEO");InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{first.remember("selectedPlant","Planta Sur");first.chooseCompany();});ui.waitText(first,"Elegir empresa");ui.screenshot("09-elegir-empresa");
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->first.switchCompany("frigor"));MainActivity second=awaitActivity(current,first);ui.waitText(second,"INICIAR ESCANEO");assertEquals("FRIGOR",second.companyName());ui.screenshot("10-frigor-empresa");
   InstrumentationRegistry.getInstrumentation().runOnMainSync(second::newWorker);ui.waitText(second,"PLANTA DEL TRABAJADOR");ui.screenshot("11-empresa-planta-trabajador");
   new Vault(context,"frigor").put("pending","saved-frigor-request");InstrumentationRegistry.getInstrumentation().runOnMainSync(()->second.switchCompany("soboce"));assertEquals("frigor",Companies.current(context));new Vault(context,"frigor").remove("pending");
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->second.switchCompany("soboce"));MainActivity third=awaitActivity(current,second);ui.waitText(third,"INICIAR ESCANEO");assertEquals("SOBOCE",third.companyName());assertEquals("Planta Sur",new Vault(context).get("selectedPlant"));
  }finally{
   MainActivity active=current.get();if(active!=null)InstrumentationRegistry.getInstrumentation().runOnMainSync(active::finish);app.unregisterActivityLifecycleCallbacks(listener);context.stopService(new Intent(context,PeerService.class));long end=System.currentTimeMillis()+5000;while(PeerService.running&&System.currentTimeMillis()<end)Thread.sleep(50);
  }
 }
 MainActivity awaitActivity(java.util.concurrent.atomic.AtomicReference<MainActivity> current,MainActivity previous)throws Exception{long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){MainActivity next=current.get();if(next!=null&&next!=previous)return next;Thread.sleep(50);}throw new AssertionError("Company screen did not reopen");}
}
