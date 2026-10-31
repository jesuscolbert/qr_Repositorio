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
