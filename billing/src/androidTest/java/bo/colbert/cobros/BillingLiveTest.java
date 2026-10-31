package bo.colbert.cobros;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.lang.Process;
import bo.colbert.licensing.*;

/** Runs only during initial provisioning, before the owner installs this new server. */
@RunWith(AndroidJUnit4.class)
public class BillingLiveTest {
 @Test public void freshNamedTunnelAcceptsProofAndReturnsSignedRenewal()throws Exception{
  JSONObject cfg;try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("billing-live-test.json")){cfg=new JSONObject(new String(LicenseClient.read(in,1024*1024),StandardCharsets.UTF_8));}catch(FileNotFoundException absent){org.junit.Assume.assumeTrue("Dedicated live tunnel credentials provided only for initial provisioning",false);return;}
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();String name="billing-live-validation.db";context.deleteDatabase(name);JSONObject seed=cfg.getJSONArray("contracts").getJSONObject(0);String token=seed.getString("token"),id=seed.getString("id"),product=seed.getString("productId"),url=cfg.getString("url");
  KeyPairGenerator gen=KeyPairGenerator.getInstance("EC");gen.initialize(new ECGenParameterSpec("secp256r1"));KeyPair keys=gen.generateKeyPair();String pub=Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
  File dir=new File(context.getFilesDir(),"billing-live-test");assertTrue(dir.exists()||dir.mkdirs());Process connector=null;
  try(BillingDb db=new BillingDb(context,name);BillingHttp http=new BillingHttp(db,c->LicenseProtocol.sign(c,keys.getPrivate(),keys.getPublic()))){
   long now=System.currentTimeMillis();db.create(id,"Cliente sintético de validación",product,"Prueba de licencias",token,now);db.plan(id,1000,"day","Cuenta ficticia para prueba automatizada","",now);db.charge(id,now);http.start(8790);
   File creds=new File(dir,"credentials.json"),yaml=new File(dir,"config.yml");try(FileOutputStream out=new FileOutputStream(creds)){out.write(cfg.getJSONObject("tunnel").toString().getBytes(StandardCharsets.UTF_8));}
   String tunnelId=cfg.getJSONObject("tunnel").getString("TunnelID");String text="tunnel: "+tunnelId+"\ncredentials-file: "+creds.getAbsolutePath()+"\nprotocol: http2\ningress:\n  - hostname: "+new URL(url).getHost()+"\n    service: http://127.0.0.1:8790\n  - service: http_status:404\n";try(FileOutputStream out=new FileOutputStream(yaml)){out.write(text.getBytes(StandardCharsets.UTF_8));}
   connector=new ProcessBuilder(new File(context.getApplicationInfo().nativeLibraryDir,"libcloudflared.so").getAbsolutePath(),"tunnel","--config",yaml.getAbsolutePath(),"--no-autoupdate","run",tunnelId).directory(dir).redirectErrorStream(true).start();Process running=connector;Thread drain=new Thread(()->{try(InputStream in=running.getInputStream()){byte[] b=new byte[4096];while(in.read(b)!=-1){/* No credentials or raw tunnel logs in public artifacts. */}}catch(IOException ignored){}});drain.setDaemon(true);drain.start();
   long deadline=System.currentTimeMillis()+120000;JSONObject blocked=null;
   while(System.currentTimeMillis()<deadline){try{blocked=status(url,token,pub,id,product);break;}catch(IOException unavailable){Thread.sleep(1200);}}
   assertNotNull("New billing tunnel did not reach the Android server",blocked);assertFalse(blocked.getBoolean("allowed"));String invoice=blocked.getJSONObject("invoice").getString("id");Thread.sleep(200);
   JSONObject receipt=post(url+"/v1/receipt",token,new JSONObject().put("requestId",UUID.randomUUID().toString()).put("invoiceId",invoice).put("image",BillingTest.picture(android.graphics.Color.BLUE)).put("note","Comprobante sintético; no es un pago real"));assertEquals("pending",receipt.getString("state"));Thread.sleep(200);assertFalse(status(url,token,pub,id,product).getBoolean("allowed"));
   db.review(receipt.getString("receiptId"),true,"Prueba automatizada",System.currentTimeMillis());Thread.sleep(200);JSONObject active=status(url,token,pub,id,product);assertTrue(active.getBoolean("allowed"));assertTrue(active.getLong("expiresAt")<=active.getLong("paidUntil"));assertTrue(active.getLong("expiresAt")-active.getLong("issuedAt")<=LicenseProtocol.OFFLINE_MS);
  }finally{if(connector!=null){connector.destroyForcibly();connector.waitFor(10,TimeUnit.SECONDS);}context.deleteDatabase(name);File[] files=dir.listFiles();if(files!=null)for(File f:files)f.delete();dir.delete();}
 }
 static JSONObject status(String url,String token,String publicKey,String id,String product)throws Exception{String nonce=LicenseProtocol.random();return LicenseProtocol.verify(post(url+"/v1/status",token,new JSONObject().put("nonce",nonce)),publicKey,id,product,nonce);}
 static JSONObject post(String url,String token,JSONObject request)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setInstanceFollowRedirects(false);c.setConnectTimeout(5000);c.setReadTimeout(7000);c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Content-Type","application/json");byte[] body=request.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(body.length);try{try(OutputStream out=c.getOutputStream()){out.write(body);}if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());try(InputStream in=c.getInputStream()){return new JSONObject(new String(LicenseClient.read(in,4*1024*1024),StandardCharsets.UTF_8));}}finally{c.disconnect();}}
}
