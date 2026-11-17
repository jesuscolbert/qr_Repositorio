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
 @Test public void masterMenuAndOperatorMenuAreDifferentOnTheSameApk()throws Exception{
  authorize("master",true,"Planta Norte","Planta Sur");android.app.Instrumentation i=InstrumentationRegistry.getInstrumentation();StaffActivity activity=(StaffActivity)i.startActivitySync(new android.content.Intent(fixture.context,StaffActivity.class).putExtra("company","soboce").putExtra("manage",true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));PeerDatabaseTest ui=new PeerDatabaseTest();try{ui.waitText(activity,"Encargados y permisos");ui.screenshot("30-maestro-cuentas");}finally{i.runOnMainSync(activity::finish);}
  authorize("encargado",true,"Planta Norte");StaffActivity operator=(StaffActivity)i.startActivitySync(new android.content.Intent(fixture.context,StaffActivity.class).putExtra("company","soboce").putExtra("manage",true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));try{ui.waitText(operator,"Mi cuenta");ui.screenshot("31-encargado-cuenta");}finally{i.runOnMainSync(operator::finish);}
 }
}
