package bo.soboce.comedor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import org.json.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import bo.colbert.credential.WalletProtocol;
import bo.colbert.licensing.*;

@RunWith(AndroidJUnit4.class)
public class StaffTest {
 CompaniesTest fixture;PeerConfig config;PeerDatabase db;StaffAccess auth;KeyPair root;String north,south;long now;long revision;
 @Before public void setup()throws Exception {
  fixture=new CompaniesTest();fixture.setup();Companies.install(fixture.context);fixture.permission("soboce",true);now=System.currentTimeMillis();root=WalletTest.key();
  config=fixture.config("soboce");db=new PeerDatabase(fixture.context,config);
  north=PeerCards.request(db,config,fixture.worker("Planta Norte"),now).getString("qr");south=PeerCards.request(db,config,fixture.worker("Planta Sur").put("document","99887766").put("name","Persona Sur"),now).getString("qr");
  new SharedLedger(db,config).scan(fixture.scan(north,"desayuno","Planta Norte"),config.operator(-1),now);db.close();
  Companies.item(fixture.context,"soboce").getJSONObject("network").put("staffAuthority",WalletProtocol.publicText(root.getPublic()));Companies.install(fixture.context);
  config=fixture.config("soboce");auth=StaffAccess.attach(fixture.context,config);auth.create("Encargado autorizado","591234");auth=StaffAccess.attach(fixture.context,config);db=new PeerDatabase(fixture.context,config);revision=0;
 }
 @After public void cleanup(){StaffAccess.lock();if(db!=null)db.close();fixture.cleanup();}
 String seal(JSONObject body)throws Exception{String data=WalletProtocol.b64(body.toString().getBytes(StandardCharsets.UTF_8));return "CSA1:"+data+"."+WalletProtocol.sign(root.getPrivate(),"CSA1:"+data);}
 String authorize(String role,boolean active,String... plants)throws Exception{
  String id=auth.certificate().isEmpty()?UUID.randomUUID().toString():StaffProtocol.grant(auth.certificate(),config).getString("id");
  JSONArray permissions=new JSONArray().put("scan").put("link").put("people").put("report");if(!role.equals("encargado"))permissions.put("worker").put("issue").put("visitor");
  JSONObject body=new JSONObject().put("kind","grant").put("cluster",config.cluster).put("id",id).put("key",auth.publicKey()).put("device",config.device).put("name","Encargado autorizado").put("role",role).put("active",active).put("plants",new JSONArray(Arrays.asList(plants))).put("permissions",permissions).put("revision",++revision).put("issued",now);
  String grant=seal(body),directory=seal(new JSONObject().put("kind","directory").put("cluster",config.cluster).put("revision",revision).put("time",now).put("accounts",new JSONArray().put(grant)));auth.saveReply(new JSONObject().put("certificate",grant).put("directory",directory));return grant;
 }
 JSONObject scan(String qr,String meal,String plant)throws Exception{JSONObject req=fixture.scan(qr,meal,plant);return req.put("staffProof",auth.networkProof("scan",new JSONObject(req.toString())));}
 @Test public void existingPinAndAccountSurviveUpdateAndCompanySwitchWithoutCreatingAnotherAccount()throws Exception{
  String certificate=authorize("encargado",true,"Planta Norte"),id=auth.profile,key=auth.publicKey(),device=config.device;
  // Exact legacy format: only the primary encrypted index, without the new intent field.
  JSONArray legacy=auth.profiles();legacy.getJSONObject(0).remove("intent");auth.global.put("staffProfiles",legacy.toString());auth.global.remove("staffProfilesBackup");auth.global.remove("staffSelected");StaffAccess.lock();
  Companies.install(fixture.context);Companies.install(fixture.context);Companies.select(fixture.context,"frigor");Companies.select(fixture.context,"soboce");
  StaffAccess reopened=new StaffAccess(fixture.context,fixture.config("soboce"));assertEquals(id,reopened.profile);assertEquals(1,reopened.profiles().length());assertEquals(key,reopened.publicKey());assertEquals(device,reopened.config.device);assertFalse(reopened.ready());
  reopened.unlock("591234");assertTrue(reopened.ready());assertEquals(certificate,reopened.certificate());assertEquals(auth.grant().getString("id"),reopened.grant().getString("id"));assertFalse(reopened.master());assertTrue(Companies.license(fixture.context,"soboce").allowed());assertEquals(1,new OfflineLedger(db,config).records().size());
 }
 @Test public void encryptedBackupRestoresMissingAndCorruptProfileIndexWithOriginalPin()throws Exception{
  authorize("encargado",true,"Planta Norte");String id=auth.profile,key=auth.publicKey();auth.global.remove("staffProfiles");StaffAccess.lock();
  StaffAccess restored=new StaffAccess(fixture.context,config);assertEquals(id,restored.profile);restored.unlock("591234");assertTrue(restored.ready());assertEquals(key,restored.publicKey());
  android.content.SharedPreferences prefs=fixture.context.getSharedPreferences("vault",0);prefs.edit().putString("staffProfiles","damaged-index").commit();StaffAccess.lock();restored=new StaffAccess(fixture.context,config);restored.unlock("591234");assertTrue(restored.ready());assertNotEquals("damaged-index",prefs.getString("staffProfiles",""));assertEquals(1,restored.profiles().length());
 }
 @Test public void unreadableProfilesNeverBecomeAnEmptyListOrOverwritePreviousCredentials()throws Exception{
  android.content.SharedPreferences prefs=fixture.context.getSharedPreferences("vault",0);prefs.edit().putString("staffProfiles","unreadable-original").putString("staffProfilesBackup","unreadable-backup").commit();
  for(WalletTest.Attempt attempt:Arrays.<WalletTest.Attempt>asList(()->auth.profiles(),()->auth.create("Otro encargado","123456"),()->new StaffAccess(fixture.context,config))){try{attempt.run();fail("Unreadable credentials were overwritten or ignored");}catch(java.io.IOException expected){}}
  assertEquals("unreadable-original",prefs.getString("staffProfiles",""));assertEquals("unreadable-backup",prefs.getString("staffProfilesBackup",""));assertEquals(1,new OfflineLedger(db,config).records().size());assertTrue(Companies.license(fixture.context,"soboce").allowed());
 }
 @Test public void recoveryProfileIsPendingAndCannotReuseAnotherPersonsGrants()throws Exception{
  String old=authorize("encargado",true,"Planta Norte"),id=auth.profile;auth.create("Encargado autorizado","123456","recover");StaffAccess recovery=new StaffAccess(fixture.context,config);
  assertNotEquals(id,recovery.profile);assertEquals("recover",recovery.local().getString("intent"));assertFalse(recovery.authorized());recovery.saveReply(new JSONObject().put("certificate",old));assertFalse(recovery.ready());
  recovery.select(id);StaffAccess original=new StaffAccess(fixture.context,config);original.unlock("591234");assertTrue(original.ready());assertEquals(2,original.profiles().length());assertEquals(1,new OfflineLedger(db,config).records().size());
 }
 @Test public void anUnreadableNetworkIdentityIsNotReplacedDuringAnUpdate()throws Exception{
  authorize("encargado",true,"Planta Norte");String profiles=auth.global.read("staffProfiles"),device=config.device;
  android.content.SharedPreferences prefs=fixture.context.getSharedPreferences("vault",0);prefs.edit().putString("peerConfig","temporarily-unreadable").commit();
  try{Companies.install(fixture.context);fail("Update replaced an unreadable installation identity");}catch(java.io.IOException expected){}
  assertEquals("temporarily-unreadable",prefs.getString("peerConfig",""));assertEquals(profiles,auth.global.read("staffProfiles"));assertEquals(device,auth.global.read("sharedDevice"));assertEquals(1,new OfflineLedger(db,config).records().size());
 }
 @Test public void loginIsVisibleWithSavedAccountsAndOnAnEmptyInstallation()throws Exception{
  authorize("encargado",true,"Planta Norte");StaffAccess.lock();android.app.Instrumentation i=InstrumentationRegistry.getInstrumentation();PeerDatabaseTest ui=new PeerDatabaseTest();
  StaffActivity saved=(StaffActivity)i.startActivitySync(new android.content.Intent(fixture.context,StaffActivity.class).putExtra("company","soboce").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
  try{ui.waitText(saved,"Iniciar sesión");ui.waitText(saved,"Usuario");ui.waitText(saved,"Contraseña");ui.waitText(saved,"Acceso anterior con PIN");i.runOnMainSync(()->ui.findText(saved.getWindow().getDecorView(),"Acceso anterior con PIN").performClick());ui.waitText(saved,"Entrar · Encargado autorizado");ui.screenshot("32-login-cuenta-existente");}finally{i.runOnMainSync(saved::finish);}
  auth.global.remove("staffProfiles");auth.global.remove("staffProfilesBackup");auth.global.remove("staffSelected");
  StaffActivity empty=(StaffActivity)i.startActivitySync(new android.content.Intent(fixture.context,StaffActivity.class).putExtra("company","soboce").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
  try{ui.waitText(empty,"Iniciar sesión");ui.waitText(empty,"Entrar");ui.waitText(empty,"Olvidé mi contraseña");ui.screenshot("33-login-contrasena");}finally{i.runOnMainSync(empty::finish);}
 }
 @Test public void migrationPreservesExistingDataPendingOperationsAndBillingWithoutAutoGrantingAdmin()throws Exception{
  assertEquals(2,db.rows("people").size());assertEquals(1,new OfflineLedger(db,config).records().size());assertFalse(auth.authorized());assertTrue(Companies.license(fixture.context,"soboce").allowed());
  WalletTest.rejected(()->config.operator(-1));String old=db.get("people","CI-0012345").toString();new Vault(fixture.context,"soboce").put("pending","legacy-pending");
  authorize("encargado",true,"Planta Norte");Companies.install(fixture.context);assertEquals(old,db.get("people","CI-0012345").toString());assertEquals("legacy-pending",new Vault(fixture.context,"soboce").get("pending"));assertEquals("encargado",config.operator(-1).getString("role"));assertEquals(6,db.getReadableDatabase().getVersion());
  config.raw.getJSONObject("operator").put("role","admin").put("name","Nombre inventado");assertEquals("Encargado autorizado",config.operator(-1).getString("name"));assertEquals("encargado",config.operator(-1).getString("role"));
 }
 @Test public void accountPinLocksOnRestartAndRateLimitsWithoutErasingHistory()throws Exception{
  authorize("encargado",true,"Planta Norte");assertTrue(auth.ready());StaffAccess.lock();assertFalse(auth.ready());WalletTest.rejected(()->auth.require("scan","Planta Norte"));auth.unlock("591234");assertTrue(auth.ready());
  for(int i=0;i<5;i++)WalletTest.rejected(()->auth.unlock("000000"));WalletTest.rejected(()->auth.unlock("591234"));assertEquals(1,new OfflineLedger(db,config).records().size());
 }
 @Test public void offlineScansHaveIndividualIdentityAndCannotEscalatePlantOrRole()throws Exception{
  authorize("encargado",true,"Planta Norte");SharedLedger ledger=new SharedLedger(db,config);JSONObject req=scan(north,"almuerzo","Planta Norte");assertEquals("accepted",ledger.scan(req,config.operator(-1),System.currentTimeMillis()).getString("status"));
  assertEquals("recovered",ledger.scan(req,config.operator(-1),System.currentTimeMillis()).getString("status"));assertEquals("duplicate",ledger.scan(scan(north,"dieta","Planta Norte"),config.operator(-1),System.currentTimeMillis()).getString("status"));
  assertFalse(ledger.scan(scan(south,"cena","Planta Sur"),config.operator(-1),System.currentTimeMillis()).optBoolean("ok"));WalletTest.rejected(()->auth.require("worker","Planta Norte"));assertFalse(auth.master());
  List<JSONObject> rows=new OfflineLedger(db,config).records();assertEquals(2,rows.size());JSONObject latest=rows.stream().filter(x->x.has("operadorId")).findFirst().orElseThrow();assertEquals(auth.grant().getString("id"),latest.getString("operadorId"));assertEquals("Encargado autorizado",latest.getString("operador"));assertTrue(ledger.eventFor(req.getString("requestId")).contains("shared-meal-v2"));
 }
 @Test public void reportExportAndWorkerLinkingRespectAssignedPlantsAndBilling()throws Exception{
  authorize("encargado",true,"Planta Norte");assertEquals(1,PeerCards.request(db,config,new JSONObject().put("action","session"),now).getJSONArray("plants").length());
  WalletRegistry registry=new WalletRegistry(db,config,new WalletTest.Memory());registry.authority(registry.person("CI-0012345"));WalletTest.rejected(()->registry.authority(registry.person("CI-99887766")));WalletTest.rejected(()->QrPrintData.snapshot(db,config,Companies.license(fixture.context,"soboce"),"Planta Norte",now));
  authorize("admin",true,"Planta Norte");assertFalse(auth.master());assertEquals(1,QrPrintData.plants(config).size());fixture.permission("soboce",false);WalletTest.rejected(()->QrPrintData.snapshot(db,config,Companies.license(fixture.context,"soboce"),"Planta Norte",now));
  try(PeerEngine engine=new PeerEngine(fixture.context,config)){assertFalse(engine.request(scan(north,"cena","Planta Norte")).optBoolean("ok"));}
 }
 @Test public void signedDirectoryPreventsRollbackAndRevocationPreservesPastReceipts()throws Exception{
  String old=authorize("encargado",true,"Planta Norte");String directory=auth.companyVault.get("staffDirectory");new SharedLedger(db,config).scan(scan(north,"cena","Planta Norte"),config.operator(-1),System.currentTimeMillis());authorize("encargado",false,"Planta Norte");assertFalse(auth.authorized());auth.saveReply(new JSONObject().put("certificate",old).put("directory",directory));assertFalse(auth.authorized());assertEquals(2,new OfflineLedger(db,config).records().size());
 }
 @Test public void individualProofRejectsTamperedRolesRequestsAndOtherCompanies()throws Exception{
  String cert=authorize("encargado",true,"Planta Norte");JSONObject tampered=StaffProtocol.grant(cert,config);tampered.put("role","master");String bad="CSA1:"+WalletProtocol.b64(tampered.toString().getBytes(StandardCharsets.UTF_8))+cert.substring(cert.lastIndexOf('.'));WalletTest.rejected(()->StaffProtocol.grant(bad,config));
  JSONObject proof=auth.networkProof("scan",fixture.scan(north,"almuerzo","Planta Norte"));JSONObject changed=new JSONObject(proof.getString("payload")).put("plant","Planta Sur");proof.put("payload",changed.toString());WalletTest.rejected(()->auth.authenticate(proof,"scan"));
  PeerConfig other=fixture.config("frigor");other.raw.put("staffAuthority",config.raw.getString("staffAuthority"));WalletTest.rejected(()->StaffProtocol.grant(cert,other));
 }
 @Test public void authorizedWorkerCreationKeepsPrintableQrAndReplicatedSignature()throws Exception{
  authorize("master",true,"Planta Norte","Planta Sur");JSONObject reply=PeerCards.request(db,config,fixture.worker("Planta Sur").put("document","000778899").put("name","Trabajador con alta autorizada"),now);
  assertTrue(reply.optBoolean("ok"));String qr=reply.getString("qr");assertTrue(qr.length()<2900);assertTrue(QrPrintDocument.matrix(qr).getWidth()>0);assertEquals("CI-000778899",PeerCards.resolve(db,config,qr,now).getString("id"));
  db.close();db=new PeerDatabase(fixture.context,config);assertNotNull(db.get("people","CI-000778899"));assertEquals(1,new OfflineLedger(db,config).records().size());
 }
 @Test public void longNamesRemainPrintableWithSignedPermissionsAndBadCompressedQrIsRejected()throws Exception{
  authorize("admin",true,"Planta Norte","Planta Sur");String name="ÁÉÍÓÚÑ".repeat(16)+"Luis";JSONObject g=auth.grant();g.put("name",name).put("revision",++revision);auth.saveReply(new JSONObject().put("certificate",seal(g)));
  JSONObject reply=PeerCards.request(db,config,fixture.worker("Planta Sur").put("document","000112233").put("name",name),now);assertTrue(reply.optBoolean("ok"));String qr=reply.getString("qr");assertTrue(qr.startsWith("SB5:"));assertTrue(qr.length()<2300);assertTrue(QrPrintDocument.matrix(qr).getWidth()>0);assertEquals(name,PeerCards.resolve(db,config,qr,now).getString("nombre"));
  try{RosterLedger.unpack(RosterLedger.pack("x".repeat(9000)));fail("Oversized credential accepted");}catch(java.io.IOException expected){}
 }
 JSONObject rpc(JSONObject request,boolean withAccount)throws Exception {long time=System.currentTimeMillis();String nonce=PeerConfig.random(),payload=request.toString();JSONObject result=new JSONObject().put("epoch",1).put("time",time).put("nonce",nonce).put("payload",payload).put("signature",config.mac("rpc|1|"+time+"|"+nonce+"|"+payload));if(withAccount)result.put("staff",auth.networkProof("p2p",request));return result;}
 @Test public void peerHttpRequiresIndividualAccountAndCannotPromoteAnOperator()throws Exception{
  authorize("encargado",true,"Planta Norte");PeerEngine engine=new PeerEngine(fixture.context,config);PeerDatabaseTest client=new PeerDatabaseTest();
  try{client.elect(new PeerEngine[]{engine},0,1);engine.start();JSONObject status=new JSONObject().put("method","status").put("protocol",8);
   try{client.localSharedHttp(rpc(status,false));fail("Shared key alone was accepted");}catch(java.io.IOException expected){}
   JSONObject request=rpc(status,true),response=client.localSharedHttp(request);assertEquals(8,new JSONObject(response.getString("payload")).getInt("protocol"));assertEquals(8,auth.authenticate(response.getJSONObject("staff"),"reply:"+request.getString("nonce")).getInt("protocol"));
   try{client.localSharedHttp(request);fail("Replay accepted");}catch(java.io.IOException expected){}
   JSONObject forbidden=new JSONObject().put("method","scan").put("protocol",8).put("request",scan(south,"almuerzo","Planta Sur")).put("operator",new JSONObject().put("name","Intruso").put("role","admin"));
   JSONObject denied=client.localSharedHttp(rpc(forbidden,true));assertFalse(new JSONObject(denied.getString("payload")).optBoolean("ok"));assertEquals(1,new OfflineLedger(db,config).records().size());
  }finally{engine.close();engine.db.close();}
 }
 @Test public void masterMenuAndOperatorMenuAreDifferentOnTheSameApk()throws Exception{
  authorize("master",true,"Planta Norte","Planta Sur");android.app.Instrumentation i=InstrumentationRegistry.getInstrumentation();StaffActivity activity=(StaffActivity)i.startActivitySync(new android.content.Intent(fixture.context,StaffActivity.class).putExtra("company","soboce").putExtra("manage",true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));PeerDatabaseTest ui=new PeerDatabaseTest();try{ui.waitText(activity,"Encargados y permisos");ui.screenshot("30-maestro-cuentas");}finally{i.runOnMainSync(activity::finish);}
  authorize("encargado",true,"Planta Norte");StaffActivity operator=(StaffActivity)i.startActivitySync(new android.content.Intent(fixture.context,StaffActivity.class).putExtra("company","soboce").putExtra("manage",true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));try{ui.waitText(operator,"Mi cuenta");ui.screenshot("31-encargado-cuenta");}finally{i.runOnMainSync(operator::finish);}
 }
 @Test public void cachedAccountsObserveChangesAcrossInstancesAndNeverTrustModifiedCertificates()throws Exception{
  String cert=authorize("encargado",true,"Planta Norte");StaffAccess service=new StaffAccess(fixture.context,config);assertTrue(service.authorized());
  long start=android.os.SystemClock.elapsedRealtime();for(int i=0;i<100;i++)assertTrue(service.authorized());long millis=android.os.SystemClock.elapsedRealtime()-start;
  java.io.File folder=new java.io.File(fixture.context.getExternalFilesDir(null),"staff-performance");folder.mkdirs();try(java.io.FileWriter w=new java.io.FileWriter(new java.io.File(folder,"authorization.json"))){w.write(new JSONObject().put("cachedAuthorizationChecks",100).put("elapsedMs",millis).toString());}
  PeerDatabaseTest ui=new PeerDatabaseTest();ui.shellUi("mkdir -p /sdcard/Download/soboce-design");ui.shellUi("cp "+new java.io.File(folder,"authorization.json").getAbsolutePath()+" /sdcard/Download/soboce-design/authorization.json");
  // Cached JSON objects are copies, not mutable shared authority state.
  StaffProtocol.grant(cert,config).put("role","master");assertEquals("encargado",StaffProtocol.grant(cert,config).getString("role"));
  authorize("encargado",false,"Planta Norte");assertFalse(service.authorized());authorize("admin",true,"Planta Sur");assertTrue(service.authorized());assertFalse(service.can("scan")&&StaffProtocol.contains(service.grant().getJSONArray("plants"),"Planta Norte"));
  auth.companyVault.remove("staffCert:"+auth.profile);assertFalse(service.authorized());auth.companyVault.put("staffCert:"+auth.profile,cert);assertFalse(service.authorized());
  Vault another=new Vault(fixture.context,"soboce");another.put("cache-test","first");assertEquals("first",auth.companyVault.get("cache-test"));another.put("cache-test","second");assertEquals("second",auth.companyVault.get("cache-test"));another.remove("cache-test");assertEquals("",auth.companyVault.get("cache-test"));
  PeerConfig wrong=new PeerConfig(config.raw.toString());wrong.raw.put("staffAuthority",WalletProtocol.publicText(WalletTest.key().getPublic()));WalletTest.rejected(()->StaffProtocol.grant(cert,wrong));
 }
 @Test public void existingWorkerCredentialAndDesktopKeepWorkingWhenStaffPinIsLocked()throws Exception{
  // Issue the worker credential using the pre-account configuration, then keep it.
  JSONObject legacyRaw=new JSONObject(config.raw.toString());legacyRaw.remove("staffAuthority");PeerConfig legacy=new PeerConfig(legacyRaw.toString());WalletTest.Memory remote=new WalletTest.Memory();remote.now=System.currentTimeMillis();KeyPair worker=WalletTest.key();
  String pair=WalletProtocol.pair(worker.getPrivate(),worker.getPublic(),WalletProtocol.nonce(),remote.now),cert=new WalletRegistry(db,legacy,remote).bind("CI-0012345",pair,0,Companies.license(fixture.context,"soboce"),remote.now);
  authorize("encargado",true,"Planta Norte");String qr=WalletProtocol.qr(cert,worker.getPrivate(),System.currentTimeMillis());assertEquals("accepted",new SharedLedger(db,config).scan(scan(qr,"almuerzo","Planta Norte"),config.operator(-1),System.currentTimeMillis()).getString("status"));assertEquals("duplicate",new SharedLedger(db,config).scan(scan(north,"dieta","Planta Norte"),config.operator(-1),System.currentTimeMillis()).getString("status"));
  try(PeerEngine engine=new PeerEngine(fixture.context,config){@Override WalletRegistry walletRegistry(){return new WalletRegistry(db,config,remote);}@Override public void close(){super.close();db.close();}}){
   new PeerDatabaseTest().elect(new PeerEngine[]{engine},0,1);engine.start();StaffAccess.lock();assertTrue(engine.shared.leading());
   JSONObject history=WalletTest.walletPost(engine.localPort,WalletProtocol.historyRequest(cert,worker.getPrivate(),System.currentTimeMillis()));assertEquals(2,history.getJSONArray("items").length());assertEquals("CI-0012345",WalletProtocol.certificate(history.getString("certificate")).getString("p"));assertFalse(history.toString().contains("Persona Sur"));
   DesktopReports reports=new DesktopReports(engine);JSONObject query=new JSONObject().put("action","report").put("period","day").put("date",MealLedger.day(System.currentTimeMillis())).put("plant","");assertEquals(2,new JSONObject(ExcelReportTest.post(engine.localPort,ExcelReportTest.sign(reports.key(),query)).getString("payload")).getInt("count"));
   authorize("encargado",false,"Planta Norte");assertFalse(engine.shared.leading());
  }
 }
}
