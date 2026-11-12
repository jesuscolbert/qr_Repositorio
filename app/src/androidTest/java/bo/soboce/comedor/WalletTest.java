package bo.soboce.comedor;

import bo.colbert.credential.WalletProtocol;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;

@RunWith(AndroidJUnit4.class)
public class WalletTest {
 CompaniesTest fixture;PeerConfig config;PeerDatabase db;WalletRegistry registry;Memory remote;KeyPair identity;String id,staticQr,pair;long now;
 static final class Memory implements WalletRegistry.Remote {
  Map<String,JSONObject> state=new HashMap<>();long now;boolean offline;
  public JSONObject call(JSONObject r)throws Exception {
   if(offline)throw new java.io.IOException("offline");String tag=r.optString("tag");JSONObject previous=state.get(tag);
   if(r.getString("action").equals("get"))return new JSONObject().put("ok",true).put("binding",previous==null?JSONObject.NULL:previous);
   if(r.getString("action").equals("changes"))return new JSONObject().put("ok",true).put("items",new JSONArray(state.values())).put("after",state.size());
   if(previous!=null&&previous.optString("requestId").equals(r.getString("requestId")))return new JSONObject().put("ok",true).put("binding",previous);
   if((previous==null?0:previous.getLong("version"))!=r.getLong("expected"))return new JSONObject().put("ok",false).put("error","conflict");
   JSONObject next=new JSONObject().put("tag",tag).put("version",r.getLong("expected")+1).put("keyHash",r.optString("keyHash")).put("active",r.getString("action").equals("bind")).put("time",now).put("actor",r.getString("actor")).put("by",r.getString("by")).put("requestId",r.getString("requestId"));state.put(tag,next);return new JSONObject().put("ok",true).put("binding",next);
  }
 }
 static KeyPair key()throws Exception {KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));return g.generateKeyPair();}
 @Before public void setup()throws Exception {fixture=new CompaniesTest();fixture.setup();Companies.install(fixture.context);fixture.permission("soboce",true);now=fixture.now;config=fixture.config("soboce");db=new PeerDatabase(fixture.context,config);JSONObject result=PeerCards.request(db,config,fixture.worker("Planta Norte"),now);staticQr=result.getString("qr");id=PeerCards.resolve(db,config,staticQr,now).getString("id");identity=key();pair=WalletProtocol.pair(identity.getPrivate(),identity.getPublic(),WalletProtocol.nonce(),now);remote=new Memory();remote.now=now;registry=new WalletRegistry(db,config,remote);}
 @After public void cleanup(){if(db!=null)db.close();fixture.cleanup();}
 interface Attempt{void run()throws Exception;}
 static void rejected(Attempt a)throws Exception {try{a.run();fail("Unauthorized operation accepted");}catch(IllegalArgumentException expected){}}
 String activate()throws Exception{return registry.bind(id,pair,0,Companies.license(fixture.context,"soboce"),now);}
 @Test public void rotatingQrWorksOfflineAndSharesTheSameMealAllowanceWithPaper()throws Exception {
  String cert=activate();remote.offline=true;String first=WalletProtocol.qr(cert,identity.getPrivate(),now),second=WalletProtocol.qr(cert,identity.getPrivate(),now+30000);assertNotEquals(first,second);assertEquals(id,registry.resolve(first,now).getString("id"));assertEquals(id,registry.resolve(second,now+30000).getString("id"));
  assertEquals("accepted",new SharedLedger(db,config).scan(fixture.scan(first,"almuerzo","Planta Norte"),config.operator(-1),now).getString("status"));
  assertEquals("duplicate",new SharedLedger(db,config).scan(fixture.scan(second,"dieta","Planta Norte"),config.operator(-1),now+30000).getString("status"));
  assertEquals("duplicate",new SharedLedger(db,config).scan(fixture.scan(staticQr,"almuerzo","Planta Norte"),config.operator(-1),now+30000).getString("status"));
  assertEquals(1,db.rows("people").size());assertEquals(1,new OfflineLedger(db,config).records().size());
  assertFalse(cert.contains("SB4:"));assertFalse(cert.contains(config.raw.getString("networkKey")));
 }
 @Test public void stolenExpiredModifiedAndOtherCompanyCodesCannotAuthenticate()throws Exception {
  String cert=activate(),qr=WalletProtocol.qr(cert,identity.getPrivate(),now);KeyPair stranger=key();
  rejected(()->registry.resolve(qr,now+120000));rejected(()->registry.resolve(qr,now-120000));
  rejected(()->registry.resolve(WalletProtocol.qr(cert,stranger.getPrivate(),now),now));
  String bad=cert.substring(0,cert.length()-1)+(cert.endsWith("a")?"b":"a");rejected(()->registry.resolve(WalletProtocol.qr(bad,identity.getPrivate(),now),now));
  rejected(()->registry.history(WalletProtocol.historyRequest(cert,stranger.getPrivate(),now),now));
  try(PeerDatabase other=new PeerDatabase(fixture.context,fixture.config("frigor"))){rejected(()->new WalletRegistry(other,fixture.config("frigor"),remote).resolve(qr,now));}
  rejected(()->WalletProtocol.pairing(staticQr,now));rejected(()->WalletProtocol.pairing(pair,now+300001));
 }
 @Test public void enrollmentRequiresLicenseAndConnectionAndRevocationKeepsPastReceipts()throws Exception {
  fixture.permission("soboce",false);rejected(()->activate());assertTrue(remote.state.isEmpty());fixture.permission("soboce",true);remote.offline=true;try{activate();fail();}catch(java.io.IOException expected){}assertTrue(remote.state.isEmpty());remote.offline=false;
  String cert=activate();String qr=WalletProtocol.qr(cert,identity.getPrivate(),now);new SharedLedger(db,config).scan(fixture.scan(qr,"desayuno","Planta Norte"),config.operator(-1),now);
  remote.now=now+1000;registry.revoke(id,1,PeerConfig.random(),Companies.license(fixture.context,"soboce"));rejected(()->registry.resolve(qr,now+1000));
  assertEquals(id,registry.resolve(qr,now).getString("id"));assertEquals(1,new OfflineLedger(db,config).records().size());assertEquals(id,PeerCards.resolve(db,config,staticQr,now+1000).getString("id"));
  KeyPair replacement=key();String request=WalletProtocol.pair(replacement.getPrivate(),replacement.getPublic(),WalletProtocol.nonce(),now+1000);String next=registry.bind(id,request,2,Companies.license(fixture.context,"soboce"),now+1000);
  assertEquals(id,registry.resolve(WalletProtocol.qr(next,replacement.getPrivate(),now+1000),now+1000).getString("id"));assertEquals(3,db.rows("wallet_audit").size());
 }
 @Test public void historyOnlyContainsThisWorkerAndRenewsWithoutRecordingOrChangingIdentity()throws Exception {
  String cert=activate();new SharedLedger(db,config).scan(fixture.scan(staticQr,"desayuno","Planta Norte"),config.operator(-1),now);
  String other=PeerCards.request(db,config,fixture.worker("Planta Norte").put("document","99887766").put("name","Other worker private name"),now).getString("qr");new SharedLedger(db,config).scan(fixture.scan(other,"almuerzo","Planta Norte"),config.operator(-1),now);
  JSONObject result=registry.history(WalletProtocol.historyRequest(cert,identity.getPrivate(),now+1000),now+1000);assertEquals(1,result.getJSONArray("items").length());assertEquals("desayuno",result.getJSONArray("items").getJSONObject(0).getString("meal"));
  assertFalse(result.toString().contains("Other worker"));assertFalse(result.toString().contains("99887766"));assertFalse(result.toString().contains("precio"));assertFalse(result.toString().contains(config.raw.getString("networkKey")));
  JSONObject renewed=WalletProtocol.certificate(result.getString("certificate"));assertEquals(id,renewed.getString("p"));assertEquals(1,renewed.getLong("r"));assertEquals(2,new OfflineLedger(db,config).records().size());
  remote.now=now+2000;registry.revoke(id,1,PeerConfig.random(),Companies.license(fixture.context,"soboce"));rejected(()->registry.history(WalletProtocol.historyRequest(cert,identity.getPrivate(),now+2000),now+2000));
 }
 @Test public void walletHttpRejectsAnonymousRequestsReplaysAndAnOldLeader()throws Exception {
  String cert=activate();new SharedLedger(db,config).scan(fixture.scan(staticQr,"desayuno","Planta Norte"),config.operator(-1),now);
  try(PeerEngine engine=new PeerEngine(fixture.context,config){@Override WalletRegistry walletRegistry(){return new WalletRegistry(db,config,remote);}}){
   PeerDatabaseTest helper=new PeerDatabaseTest();helper.elect(new PeerEngine[]{engine},0,1);engine.start();String url="http://127.0.0.1:"+engine.localPort+"/wallet";
   try{SharedNetwork.http(url,new JSONObject(),null);fail("Anonymous history accepted");}catch(java.io.IOException expected){}
   JSONObject request=WalletProtocol.historyRequest(cert,identity.getPrivate(),System.currentTimeMillis());assertEquals(1,SharedNetwork.http(url,request,null).getJSONArray("items").length());
   try{SharedNetwork.http(url,request,null);fail("Replay accepted");}catch(java.io.IOException expected){}
   long time=android.os.SystemClock.elapsedRealtime();engine.shared.lease.update(new JSONObject().put("owner","e".repeat(64)).put("boot","f".repeat(64)).put("epoch",2).put("remaining",30000),time,time);
   try{SharedNetwork.http(url,WalletProtocol.historyRequest(cert,identity.getPrivate(),System.currentTimeMillis()),null);fail("Former leader served history");}catch(java.io.IOException expected){}
  }
 }
 @Test public void versionFiveUpgradeKeepsPeopleAndReceipts()throws Exception {
  new SharedLedger(db,config).scan(fixture.scan(staticQr,"desayuno","Planta Norte"),config.operator(-1),now);String path=db.getWritableDatabase().getPath();db.close();db=null;
  try(android.database.sqlite.SQLiteDatabase old=android.database.sqlite.SQLiteDatabase.openDatabase(path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)){old.execSQL("DROP TABLE wallet_bindings");old.execSQL("DROP TABLE wallet_audit");old.setVersion(5);}
  db=new PeerDatabase(fixture.context,config);assertEquals(6,db.getReadableDatabase().getVersion());assertNotNull(db.get("people",id));assertEquals(1,new OfflineLedger(db,config).records().size());assertTrue(db.rows("wallet_bindings").isEmpty());assertTrue(db.rows("wallet_audit").isEmpty());
 }
 @Test public void operatorEnrollmentScreenKeepsWorkerIdentityAndShowsPhysicalVerification()throws Exception {
  android.app.Instrumentation i=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();WorkerLinkActivity activity=(WorkerLinkActivity)i.startActivitySync(new android.content.Intent(fixture.context,WorkerLinkActivity.class).putExtra("company","soboce").putExtra("personId",id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
  try{i.waitForIdleSync();PeerDatabaseTest ui=new PeerDatabaseTest();i.runOnMainSync(()->{assertTrue(ui.containsText(activity.getWindow().getDecorView(),"Vincular celular"));assertTrue(ui.containsText(activity.getWindow().getDecorView(),"CI 0012345"));assertTrue((activity.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0);activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);});ui.screenshot("14-vincular-obrero");assertEquals(1,db.rows("people").size());assertEquals(0,new OfflineLedger(db,config).records().size());}finally{i.runOnMainSync(activity::finish);}
 }
}
