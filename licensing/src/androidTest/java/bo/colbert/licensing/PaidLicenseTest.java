package bo.colbert.licensing;
import org.junit.*;
import static org.junit.Assert.*;
import org.json.*;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PaidLicenseTest {
 @Test public void encryptedPaidPermitReopensAfterRebootWithoutContactingBilling()throws Exception{
  android.content.Context context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();String namespace="paid-reboot-test";SecretStore vault=new SecretStore(context,namespace);String[] names={"clientConfig","permit","serverKey","licenseClock"};
  try{
   for(String name:names)vault.put(name,"");long wall=System.currentTimeMillis();JSONObject config=new JSONObject().put("version",1).put("url","https://127.0.0.1:1").put("token",LicenseProtocol.random()).put("contractId","client").put("productId","app");
   KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));KeyPair keys=g.generateKeyPair();JSONObject c=claims().put("issuedAt",wall-15*day).put("paidUntil",wall+15*day).put("expiresAt",wall+15*day);JSONObject envelope=LicenseProtocol.sign(c,keys.getPrivate(),keys.getPublic());
   vault.put("serverKey",envelope.getString("publicKey"));vault.put("permit",new JSONObject().put("envelope",envelope).put("boot",-123).put("elapsed",1000).put("wall",wall-15*day).toString());
   LicenseClient reopened=new LicenseClient(context,true,"app",config,namespace);assertTrue(reopened.allowed());assertTrue(reopened.refreshDue());assertTrue(new LicenseClient(context,true,"app",config,namespace).allowed());
   c.put("allowed",false).put("grantKind","denied").put("expiresAt",c.getLong("issuedAt"));vault.put("permit",new JSONObject().put("envelope",LicenseProtocol.sign(c,keys.getPrivate(),keys.getPublic())).put("boot",-123).put("elapsed",1000).put("wall",wall-15*day).toString());assertFalse(new LicenseClient(context,true,"app",config,namespace).allowed());
  }finally{for(String name:names)vault.put(name,"");}
 }
 final long start=1794844800000L,day=LicenseProtocol.OFFLINE_MS;
 JSONObject claims()throws Exception{return new JSONObject().put("version",2).put("grantKind","paid").put("contractId","client").put("productId","app").put("nonce","nonce").put("revision",7).put("allowed",true).put("issuedAt",start).put("expiresAt",start+30*day).put("paidUntil",start+30*day);}
 JSONObject cache()throws Exception{return new JSONObject().put("elapsed",10000).put("wall",start).put("boot",3);}
 @Test public void signedPaidMonthWorksPastDayOneAndStopsAtExactPaidEnd()throws Exception{
  KeyPairGenerator gen=KeyPairGenerator.getInstance("EC");gen.initialize(new ECGenParameterSpec("secp256r1"));KeyPair keys=gen.generateKeyPair();String pub=Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());JSONObject c=claims();
  JSONObject verified=LicenseProtocol.verify(LicenseProtocol.sign(c,keys.getPrivate(),keys.getPublic()),pub,"client","app","nonce");assertTrue(LicenseProtocol.usable(verified,29*day));assertTrue(LicenseProtocol.usable(verified,30*day-1));assertFalse(LicenseProtocol.usable(verified,30*day));
  c.put("expiresAt",start+31*day);try{LicenseProtocol.verify(LicenseProtocol.sign(c,keys.getPrivate(),keys.getPublic()),pub,"client","app","nonce");fail("Beyond paid date accepted");}catch(IllegalArgumentException expected){}
 }
 @Test public void rebootUsesAnchoredServerTimeAndRollbackCannotExtendAccess()throws Exception{
  JSONObject c=claims(),cache=cache(),point=new JSONObject().put("serverTime",start+5*day).put("wall",start+5*day);
  assertEquals(start+6*day,LicenseClock.now(c,cache,point,start+6*day,5000,4));
  assertEquals(-1,LicenseClock.now(c,cache,point,start+day,5000,4));
  assertEquals(start+6*day,LicenseClock.now(c,cache,point,start-10*day,10000+6*day,3));
  assertFalse(LicenseProtocol.usable(c,LicenseClock.now(c,cache,point,start+30*day,5000,4)-start));
 }
 @Test public void temporaryAndLegacyPermitsKeepTheirDayLimitAndRebootRule()throws Exception{
  JSONObject temporary=claims().put("grantKind","temporary").put("paidUntil",0).put("expiresAt",start+day);
  assertTrue(LicenseProtocol.usable(temporary,day-1));assertFalse(LicenseProtocol.usable(temporary,day));assertEquals(-1,LicenseClock.now(temporary,cache(),new JSONObject(),start+1000,500,4));
  assertEquals(-1,LicenseClock.now(claims().put("version",1),cache(),new JSONObject(),start+1000,500,4));
 }
 @Test public void renewalWarningOnlyAppearsInsideLastTwoDaysAndNeverBlocks()throws Exception{
  JSONObject c=claims();assertEquals("",LicenseClock.renewalNotice(c,start+27*day));assertTrue(LicenseClock.renewalNotice(c,start+28*day).contains("2 días"));assertTrue(LicenseClock.renewalNotice(c,start+29*day).contains("1 día"));assertEquals("",LicenseClock.renewalNotice(c,start+30*day));assertEquals("",LicenseClock.renewalNotice(c.put("allowed",false),start+29*day));
 }
}
