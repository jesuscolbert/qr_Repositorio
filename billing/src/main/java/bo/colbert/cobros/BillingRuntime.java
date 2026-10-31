package bo.colbert.cobros;

import android.content.Context;
import bo.colbert.licensing.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

final class BillingRuntime {
 private static BillingRuntime instance;
 static synchronized BillingRuntime get(Context c)throws Exception{if(instance==null)instance=new BillingRuntime(c);return instance;}
 final BillingDb db;final SecretStore vault;final Context context;JSONObject config;PrivateKey privateKey;PublicKey publicKey;
 private BillingRuntime(Context c)throws Exception{
  context=c.getApplicationContext();vault=new SecretStore(context);db=new BillingDb(context,"colbert-billing.db");
  String key=vault.get("signingKey");if(!key.isEmpty())loadKey(new JSONObject(key));
  String cfg=vault.get("serverConfig");if(!cfg.isEmpty())config=new JSONObject(cfg);
 }
 void setup()throws Exception{
  if(privateKey==null){KeyPairGenerator gen=KeyPairGenerator.getInstance("EC");gen.initialize(new ECGenParameterSpec("secp256r1"));KeyPair pair=gen.generateKeyPair();JSONObject k=new JSONObject().put("private",Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())).put("public",Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));vault.put("signingKey",k.toString());loadKey(k);}
  if(config==null){try(InputStream in=context.getAssets().open("billing-server.json")){config=new JSONObject(new String(LicenseClient.read(in,1024*1024),StandardCharsets.UTF_8));}vault.put("serverConfig",config.toString());}
  JSONArray seeds=config.getJSONArray("contracts");for(int i=0;i<seeds.length();i++){JSONObject seed=seeds.getJSONObject(i);if(db.get("contract",seed.getString("id"))==null){vault.put("token:"+seed.getString("id"),seed.getString("token"));db.create(seed.getString("id"),seed.getString("customer"),seed.getString("productId"),seed.getString("productName"),seed.getString("token"),System.currentTimeMillis());}}
 }
 void loadKey(JSONObject k)throws Exception{KeyFactory f=KeyFactory.getInstance("EC");privateKey=f.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(k.getString("private"))));publicKey=f.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(k.getString("public"))));}
 JSONObject sign(JSONObject claims)throws Exception{if(privateKey==null)throw new IOException("Servidor sin inicializar.");return LicenseProtocol.sign(claims,privateKey,publicKey);}
 JSONObject clientConfig(String id)throws Exception{JSONObject c=db.contract(id);return new JSONObject().put("version",1).put("url",config.getString("url")).put("contractId",id).put("productId",c.getString("productId")).put("productName",c.getString("productName")).put("token",vault.get("token:"+id));}
 void add(String customer,String product,String productName)throws Exception{String id=java.util.UUID.randomUUID().toString(),token=LicenseProtocol.random();vault.put("token:"+id,token);db.create(id,customer,product,productName,token,System.currentTimeMillis());}
 JSONObject backup()throws Exception{JSONObject tokens=new JSONObject();JSONArray a=db.contracts();for(int i=0;i<a.length();i++){String id=a.getJSONObject(i).getString("id");tokens.put(id,vault.get("token:"+id));}return new JSONObject().put("format","COLBERT-BACKUP-1").put("serverConfig",config).put("signingKey",new JSONObject(vault.get("signingKey"))).put("tokens",tokens).put("database",db.exportData());}
 void restore(JSONObject b)throws Exception{
  LicenseProtocol.require(b.getString("format").equals("COLBERT-BACKUP-1")&&db.contracts().length()==0,"Respaldo incompatible o instalación no vacía.");
  JSONObject key=b.getJSONObject("signingKey"),cfg=b.getJSONObject("serverConfig"),tokens=b.getJSONObject("tokens");loadKey(key);
  // Save identity before data; an interrupted restore can be repeated while the database is empty.
  vault.put("signingKey",key.toString());vault.put("serverConfig",cfg.toString());for(java.util.Iterator<String> it=tokens.keys();it.hasNext();){String id=it.next();vault.put("token:"+id,tokens.getString(id));}db.restore(b.getJSONObject("database"));config=cfg;
 }
}
