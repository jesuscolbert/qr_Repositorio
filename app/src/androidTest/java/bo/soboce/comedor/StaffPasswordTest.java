package bo.soboce.comedor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class StaffPasswordTest {
 StaffTest f;JSONObject vector,credentials;String user,password,account;int version=1;String currentKey;boolean allowed=true,wrongServer=false;
 @Before public void setup()throws Exception {f=new StaffTest();f.setup();try(java.io.InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("staff-password-vector.json")){vector=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}credentials=vector.getJSONObject("credentials");user=vector.getString("username");password=vector.getString("password");account=UUID.randomUUID().toString();}
 @After public void cleanup(){f.cleanup();}
 JSONObject grant(StaffAccess a)throws Exception{return new JSONObject().put("kind","grant").put("cluster",f.config.cluster).put("id",account).put("key",a.publicKey()).put("device",f.config.device).put("name","Encargado con contraseña").put("role","encargado").put("active",true).put("plants",new JSONArray().put("Planta Norte")).put("permissions",new JSONArray().put("scan").put("link").put("people").put("report")).put("revision",++f.revision).put("issued",System.currentTimeMillis()).put("username",user).put("passwordVersion",version);}
 JSONObject reply(JSONObject grant)throws Exception {String cert=f.seal(grant);return new JSONObject().put("ok",true).put("certificate",cert).put("directory",f.seal(new JSONObject().put("kind","directory").put("cluster",f.config.cluster).put("revision",f.revision).put("time",System.currentTimeMillis()).put("accounts",new JSONArray().put(cert))));}
 StaffAccess.LoginTransport transport(){return new StaffAccess.LoginTransport(){String message;public JSONObject call(StaffAccess a,String action,JSONObject r)throws Exception{
  if(!allowed)return new JSONObject().put("ok",false).put("error","Cuenta desactivada.");
  if(action.equals("loginStart")){String nonce=r.getString("clientNonce"),joined=nonce+"2".repeat(64),first="r="+joined+",s="+credentials.getString("salt")+",i=600000";message="n="+user+",r="+nonce+","+first+",c=biws,r="+joined;return new JSONObject().put("ok",true).put("challenge","fixture").put("serverFirst",first);}
  byte[] proof=StaffPassword.bytes(r.getString("proof")),signature=StaffPassword.hmac(StaffPassword.bytes(credentials.getString("storedKey")),message),key=new byte[32];for(int i=0;i<32;i++)key[i]=(byte)(proof[i]^signature[i]);if(!java.security.MessageDigest.isEqual(StaffPassword.hash(key),StaffPassword.bytes(credentials.getString("storedKey"))))return new JSONObject().put("ok",false).put("error","Contraseña incorrecta.");
  JSONObject result=reply(grant(a));currentKey=a.publicKey();return result.put("serverSignature",wrongServer?StaffPassword.b64(new byte[32]):StaffPassword.b64(StaffPassword.hmac(StaffPassword.bytes(credentials.getString("serverKey")),message)));
 }};}
 StaffAccess login()throws Exception{return StaffAccess.loginPassword(f.fixture.context,f.config,user,password,transport());}
 @Test public void javaMatchesIndependentNodeVectorAndRfc7677()throws Exception {
  StaffPassword.Proof proof=StaffPassword.proof(user,password,vector.getString("clientNonce"),vector.getString("serverFirst"));assertEquals(vector.getString("proof"),proof.proof);assertEquals(vector.getString("serverSignature"),proof.serverSignature);assertEquals(credentials.getString("storedKey"),proof.verifier.getString("storedKey"));assertTrue(StaffPassword.verifyLocal(password,proof.verifier));assertFalse(StaffPassword.verifyLocal("Clave-incorrecta",proof.verifier));
  byte[] salted=StaffPassword.derive("pencil",Base64.getDecoder().decode("W22ZaJ0SNY7soEsUEjb6gQ=="),4096),client=StaffPassword.hmac(salted,"Client Key"),stored=StaffPassword.hash(client);String nonce="rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0",message="n=user,r=rOprNGfwEbeRWgbNEkqO,r="+nonce+",s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096,c=biws,r="+nonce;byte[] sig=StaffPassword.hmac(stored,message),answer=new byte[32];for(int i=0;i<32;i++)answer[i]=(byte)(client[i]^sig[i]);assertEquals("dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=",StaffPassword.b64(answer));
  WalletTest.rejected(()->StaffPassword.proof(user,password,vector.getString("clientNonce"),vector.getString("serverFirst").replace("600000","1")));WalletTest.rejected(()->StaffPassword.credentials("1234"));
 }
 @Test public void onlineLoginPersistsAcrossRestartAndOfflineLoginKeepsBillingAndMeals()throws Exception {
  StaffAccess a=login();assertTrue(a.ready());assertEquals(account,a.grant().getString("id"));assertEquals(currentKey,a.publicKey());assertFalse(a.master());String id=a.profile;StaffAccess.lock();
  a=StaffAccess.loginPassword(f.fixture.context,f.config,user,password,(x,action,r)->{throw new java.io.IOException("offline");});assertEquals(id,a.profile);assertTrue(a.ready());a.require("scan","Planta Norte");WalletTest.rejected(()->aRequireOutside());assertEquals(1,new OfflineLedger(f.db,f.config).records().size());assertTrue(Companies.license(f.fixture.context,"soboce").allowed());
  f.fixture.permission("soboce",false);try(PeerEngine engine=new PeerEngine(f.fixture.context,f.config)){assertFalse(engine.request(f.fixture.scan(f.north,"almuerzo","Planta Norte")).optBoolean("ok"));}
  String stored=a.companyVault.read("staffLogin:"+id);assertFalse(stored.contains(password));assertFalse(stored.contains("serverKey"));
 }
 void aRequireOutside()throws Exception {new StaffAccess(f.fixture.context,f.config).require("scan","Planta Sur");}
 @Test public void wrongPasswordAndExplicitRevocationNeverFallBackToCachedOfflineAccess()throws Exception {
  StaffAccess a=login();StaffAccess.lock();WalletTest.rejected(()->StaffAccess.loginPassword(f.fixture.context,f.config,user,"Clave-incorrecta",transport()));assertFalse(StaffAccess.session(a.profile));allowed=false;WalletTest.rejected(()->StaffAccess.loginPassword(f.fixture.context,f.config,user,password,transport()));assertFalse(StaffAccess.session(a.profile));
 }
 @Test public void resetInvalidatesBiometricsOldPasswordAndPreviousDeviceWhenReceived()throws Exception {
  // Remove the unrelated legacy fixture profile so this is a personal phone.
  f.auth.global.remove("staffProfiles");f.auth.global.remove("staffProfilesBackup");f.auth.global.remove("staffSelected");StaffAccess a=login();a.global.put("staffBiometric:"+a.profile,"yes");assertTrue(a.biometricAllowed());
  JSONObject updated=grant(a).put("passwordVersion",2);a.saveReply(reply(updated));assertFalse(a.passwordCached());assertFalse(a.biometricAllowed());WalletTest.rejected(()->a.unlockPasswordOffline(user,password));
  updated.put("key",bo.colbert.credential.WalletProtocol.publicText(WalletTest.key().getPublic())).put("revision",++f.revision);a.saveReply(new JSONObject().put("replacement",f.seal(updated)));assertFalse(a.authorized());
 }
 @Test public void failedNewUserAttemptsDoNotAccumulateProfilesAndCannotTrustAnInvalidServerProof()throws Exception {
  int before=f.auth.profiles().length();String selected=f.auth.profile;for(int i=0;i<12;i++){String name="unknown"+i;WalletTest.rejected(()->StaffAccess.loginPassword(f.fixture.context,f.config,name,password,(a,action,r)->new JSONObject().put("ok",false).put("error","Incorrecto")));}assertEquals(before,f.auth.profiles().length());assertEquals(selected,new StaffAccess(f.fixture.context,f.config).profile);
  wrongServer=true;WalletTest.rejected(()->login());assertEquals(before,f.auth.profiles().length());assertEquals(1,new OfflineLedger(f.db,f.config).records().size());
 }
 @Test public void legacyProfileGetsPasswordWithoutAnotherLocalAccountAndCannotUseOldPin()throws Exception {
  f.authorize("encargado",true,"Planta Norte");account=f.auth.grant().getString("id");JSONObject migrated=f.auth.grant().put("username",user).put("passwordVersion",1).put("revision",++f.revision);f.auth.saveReply(reply(migrated));String id=f.auth.profile;
  StaffAccess a=login();assertEquals(id,a.profile);assertEquals(1,a.profiles().length());WalletTest.rejected(()->a.unlock("591234"));assertTrue(a.ready());
 }
}
