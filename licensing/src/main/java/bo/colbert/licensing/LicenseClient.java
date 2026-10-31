package bo.colbert.licensing;

import android.content.*;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** One reusable guard for native Android products. The server owns all prices and decisions. */
public final class LicenseClient {
 private static LicenseClient singleton;
 public static synchronized LicenseClient get(Context c){if(singleton==null)singleton=new LicenseClient(c);return singleton;}
 private final Context context;private final SecretStore vault;private JSONObject config;private boolean broken;
 public LicenseClient(Context c){
  context=c.getApplicationContext();vault=new SecretStore(context);
  try{
   String saved=vault.get("clientConfig");
   if(!saved.isEmpty())config=new JSONObject(saved);
   else try(InputStream in=context.getAssets().open("license-client.json")){config=new JSONObject(new String(read(in,32768),StandardCharsets.UTF_8));vault.put("clientConfig",config.toString());}catch(FileNotFoundException absent){config=null;}
   if(config!=null)validateConfig(config);
  }catch(Exception e){broken=true;}
 }
 public static void validateConfig(JSONObject c)throws Exception {
  URL u=new URL(c.getString("url"));LicenseProtocol.require(u.getProtocol().equals("https")&&u.getUserInfo()==null&&u.getQuery()==null&&u.getRef()==null&&(u.getPath().isEmpty()||u.getPath().equals("/")),"URL de permisos inválida.");
  LicenseProtocol.require(c.getInt("version")==1&&c.getString("token").matches("[a-f0-9]{64}")&&c.getString("contractId").matches("[a-zA-Z0-9_-]{1,80}")&&c.getString("productId").matches("[a-zA-Z0-9_-]{1,80}"),"Configuración de licencia inválida.");
 }
 public boolean configured(){return config!=null||broken;}
 private int boot(){return Settings.Global.getInt(context.getContentResolver(),"boot_count",-1);}
 public synchronized boolean allowed(){
  if(!configured())return true;if(broken)return false;
  try{
   JSONObject cache=new JSONObject(vault.get("permit"));String key=vault.get("serverKey");
   JSONObject claims=LicenseProtocol.verify(cache.getJSONObject("envelope"),key,config.getString("contractId"),config.getString("productId"),null);
   // After a reboot we demand a fresh validation: wall-clock changes cannot extend a lease.
   if(boot()<0||cache.getInt("boot")!=boot())return false;
   return LicenseProtocol.usable(claims,SystemClock.elapsedRealtime()-cache.getLong("elapsed"));
  }catch(Exception e){return false;}
 }
 public synchronized JSONObject details(){try{String s=vault.get("permit");return s.isEmpty()?new JSONObject():new JSONObject(new JSONObject(s).getJSONObject("envelope").getString("claims"));}catch(Exception e){return new JSONObject();}}
 public synchronized boolean refreshDue(){try{JSONObject p=new JSONObject(vault.get("permit"));return !allowed()||SystemClock.elapsedRealtime()-p.getLong("elapsed")>60000;}catch(Exception e){return true;}}
 public JSONObject refresh()throws Exception {
  if(config==null)throw new IOException("Falta configurar la licencia.");String nonce=LicenseProtocol.random();long sent=SystemClock.elapsedRealtime();
  JSONObject reply=post("/v1/status",new JSONObject().put("nonce",nonce));
  synchronized(this){
   String pinned=vault.get("serverKey");if(pinned.isEmpty())pinned=reply.getString("publicKey"); // First contact authenticated by HTTPS and the contract credential.
   JSONObject claims=LicenseProtocol.verify(reply,pinned,config.getString("contractId"),config.getString("productId"),nonce);
   JSONObject previous=details();if(previous.has("revision")&&(claims.getLong("revision")<previous.getLong("revision")||claims.getLong("issuedAt")<previous.getLong("issuedAt")))return previous;
   vault.put("serverKey",pinned);vault.put("permit",new JSONObject().put("envelope",reply).put("elapsed",sent).put("boot",boot()).toString());
   return claims;
  }
 }
 public JSONObject submitReceipt(String invoice,String requestId,String image,String note)throws Exception {
  return post("/v1/receipt",new JSONObject().put("invoiceId",invoice).put("requestId",requestId).put("image",image).put("note",note));
 }
 public synchronized void savePending(JSONObject pending)throws Exception{vault.put("pendingReceipt",pending.toString());}
 public synchronized JSONObject pending(){try{String s=vault.get("pendingReceipt");return s.isEmpty()?null:new JSONObject(s);}catch(Exception e){return null;}}
 public synchronized void clearPending()throws Exception{vault.put("pendingReceipt","");}
 public String productName(){return config==null?"Licencia":config.optString("productName",config.optString("productId"));}
 public static byte[] read(InputStream in,int max)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("Archivo demasiado grande.");out.write(b,0,n);}return out.toByteArray();}
 private JSONObject post(String route,JSONObject request)throws Exception {
  HttpURLConnection c=(HttpURLConnection)new URL(config.getString("url").replaceAll("/$","")+route).openConnection();c.setConnectTimeout(7000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Authorization","Bearer "+config.getString("token"));c.setRequestProperty("Content-Type","application/json");
  byte[] bytes=request.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);
  try{try(OutputStream out=c.getOutputStream()){out.write(bytes);}if(c.getResponseCode()!=200){String problem="Servidor de permisos no disponible (HTTP "+c.getResponseCode()+").";if(c.getResponseCode()==400||c.getResponseCode()==403||c.getResponseCode()==429)try(InputStream error=c.getErrorStream()){if(error!=null){String detail=new JSONObject(new String(read(error,4096),StandardCharsets.UTF_8)).optString("error");if(!detail.isEmpty()&&detail.length()<=500)problem=detail;}}catch(Exception ignored){}throw new IOException(problem);}try(InputStream in=c.getInputStream()){return new JSONObject(new String(read(in,4*1024*1024),StandardCharsets.UTF_8));}}finally{c.disconnect();}
 }
 public boolean showIfBlocked(android.app.Activity a){if(allowed())return false;a.startActivity(new Intent(a,LicenseActivity.class));return true;}
}
