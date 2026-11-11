package bo.colbert.licensing;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import org.json.*;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

@RunWith(AndroidJUnit4.class)
public class LicenseTest {
 @Test public void endpointMigrationRequiresTheSameContractAndAnExplicitForwardRevision()throws Exception{
  JSONObject old=new JSONObject().put("version",1).put("url","https://old.example.com").put("contractId","customer").put("productId","product").put("token",LicenseProtocol.random());
  JSONObject next=new JSONObject(old.toString()).put("url","https://new.example.com").put("endpointRevision",1).put("previousUrls",new JSONArray().put(old.getString("url")));
  JSONObject migrated=EndpointMigration.license(old,next);assertEquals(next.getString("url"),migrated.getString("url"));assertEquals(old.getString("token"),migrated.getString("token"));assertEquals(1,migrated.getLong("endpointRevision"));
  assertEquals(migrated.toString(),EndpointMigration.license(migrated,old).toString());assertEquals(migrated.toString(),EndpointMigration.license(migrated,next).toString());
  for(String field:new String[]{"token","contractId","productId"}){JSONObject other=new JSONObject(next.toString()).put(field,field.equals("token")?LicenseProtocol.random():"other");rejects(()->EndpointMigration.license(old,other));}
  rejects(()->EndpointMigration.license(old,new JSONObject(next.toString()).put("previousUrls",new JSONArray().put("https://another.example.com"))));
  rejects(()->EndpointMigration.license(old,new JSONObject(next.toString()).put("endpointRevision",0)));
  rejects(()->EndpointMigration.license(old,new JSONObject(next.toString()).put("url","http://new.example.com")));
 }
 @Test public void domainChangeKeepsPinnedKeyCachedDeadlineAndPendingReceipt()throws Exception{
  android.content.Context c=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();SecretStore vault=new SecretStore(c);String[] keys={"clientConfig","serverKey","permit","pendingReceipt"};String[] saved=new String[keys.length];for(int i=0;i<keys.length;i++)saved[i]=vault.get(keys[i]);
  try{
   JSONObject old=new JSONObject().put("version",1).put("url","https://old.example.com").put("contractId","customer").put("productId","product").put("token",LicenseProtocol.random());vault.put("clientConfig",old.toString());vault.put("serverKey",pub);
   String cached=new JSONObject().put("envelope",signed()).put("elapsed",android.os.SystemClock.elapsedRealtime()).put("boot",android.provider.Settings.Global.getInt(c.getContentResolver(),"boot_count",-1)).toString();vault.put("permit",cached);vault.put("pendingReceipt","saved-request");
   JSONObject next=new JSONObject(old.toString()).put("url","https://new.example.com").put("endpointRevision",1).put("previousUrls",new JSONArray().put(old.getString("url")));vault.put("clientConfig",EndpointMigration.license(old,next).toString());
   assertTrue(new LicenseClient(c,true,"product").allowed());assertEquals(pub,vault.get("serverKey"));assertEquals(cached,vault.get("permit"));assertEquals("saved-request",vault.get("pendingReceipt"));
   claims.put("allowed",false).put("expiresAt",now);vault.put("permit",new JSONObject(cached).put("envelope",signed()).toString());assertFalse(new LicenseClient(c,true,"product").allowed());
  }finally{for(int i=0;i<keys.length;i++)vault.put(keys[i],saved[i]);}
 }
 @Test public void requiredProductsFailClosedWithoutConfigurationOrWithAnotherProduct()throws Exception{
  android.content.Context c=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();SecretStore vault=new SecretStore(c);String previous=vault.get("clientConfig");
  try{
   vault.put("clientConfig","");LicenseClient missing=new LicenseClient(c,true,"frigor-comedor");assertTrue(missing.configured());assertFalse(missing.allowed());
   vault.put("clientConfig",new JSONObject().put("version",1).put("url","https://example.com").put("contractId","customer").put("productId","soboce-comedor").put("token",LicenseProtocol.random()).toString());
   LicenseClient other=new LicenseClient(c,true,"frigor-comedor");assertTrue(other.configured());assertFalse(other.allowed());
   vault.put("clientConfig","broken");assertFalse(new LicenseClient(c,true,"frigor-comedor").allowed());
  }finally{vault.put("clientConfig",previous);}
 }
 KeyPair key;String pub,nonce;JSONObject claims;long now=1793462400000L;
 @Before public void setup()throws Exception{KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));key=g.generateKeyPair();pub=Base64.getEncoder().encodeToString(key.getPublic().getEncoded());nonce=LicenseProtocol.random();claims=new JSONObject().put("version",1).put("contractId","customer").put("productId","product").put("nonce",nonce).put("issuedAt",now).put("expiresAt",now+LicenseProtocol.OFFLINE_MS).put("paidUntil",now+7*LicenseProtocol.OFFLINE_MS).put("allowed",true);}
 JSONObject signed()throws Exception{return LicenseProtocol.sign(claims,key.getPrivate(),key.getPublic());}
 interface Work{void run()throws Exception;}
 void rejects(Work w)throws Exception{try{w.run();fail("Accepted invalid license");}catch(IllegalArgumentException|SignatureException expected){}}
 @Test public void authenticLeaseWorksUntilExactDeadline()throws Exception{JSONObject c=LicenseProtocol.verify(signed(),pub,"customer","product",nonce);assertTrue(LicenseProtocol.usable(c,0));assertTrue(LicenseProtocol.usable(c,LicenseProtocol.OFFLINE_MS-1));assertFalse(LicenseProtocol.usable(c,LicenseProtocol.OFFLINE_MS));assertFalse(LicenseProtocol.usable(c,-1));}
 @Test public void forgedClaimAndOtherProductAreRejected()throws Exception{JSONObject envelope=signed();envelope.put("claims",claims.put("paidUntil",now+100*LicenseProtocol.OFFLINE_MS).toString());rejects(()->LicenseProtocol.verify(envelope,pub,"customer","product",nonce));rejects(()->LicenseProtocol.verify(signed(),pub,"customer","other",nonce));}
 @Test public void anotherCustomerNonceAndKeyAreRejected()throws Exception{rejects(()->LicenseProtocol.verify(signed(),pub,"other","product",nonce));rejects(()->LicenseProtocol.verify(signed(),pub,"customer","product",LicenseProtocol.random()));rejects(()->LicenseProtocol.verify(signed(),"bad-key","customer","product",nonce));}
 @Test public void excessiveLeaseAndBeyondPaymentAreRejected()throws Exception{claims.put("expiresAt",now+LicenseProtocol.OFFLINE_MS+1);rejects(()->LicenseProtocol.verify(signed(),pub,"customer","product",nonce));claims.put("expiresAt",now+1000).put("paidUntil",now+999);rejects(()->LicenseProtocol.verify(signed(),pub,"customer","product",nonce));}
 @Test public void blockedSignedStatusCannotAuthorizeEvenWithOldPaidDate()throws Exception{claims.put("allowed",false).put("expiresAt",now);assertFalse(LicenseProtocol.usable(LicenseProtocol.verify(signed(),pub,"customer","product",nonce),0));}
 @Test public void insecureAndAmbiguousUrlsAreRejected()throws Exception{JSONObject c=new JSONObject().put("version",1).put("url","http://example.com").put("token",LicenseProtocol.random()).put("contractId","customer").put("productId","product");rejects(()->LicenseClient.validateConfig(c));c.put("url","https://user:password@example.com/");rejects(()->LicenseClient.validateConfig(c));c.put("url","https://example.com/?other=1");rejects(()->LicenseClient.validateConfig(c));c.put("url","https://example.com");LicenseClient.validateConfig(c);}
}
