package bo.soboce.comedor;

import android.content.Context;
import android.os.SystemClock;
import android.security.keystore.*;
import bo.colbert.credential.WalletProtocol;
import org.json.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Local PIN unlock and signed, cached account grants. Neither grants nor PINs affect paid licenses. */
final class StaffAccess {
 final Context context;final PeerConfig config;final Vault global,companyVault;final String profile;
 static volatile String unlocked="";static volatile long backgroundAt;static int activities;
 private volatile long refreshed;
 StaffAccess(Context c,PeerConfig config)throws Exception {this.context=c.getApplicationContext();this.config=config;global=new Vault(c,Companies.primary(c));companyVault=new Vault(c,Companies.company(c,config));profile=global.get("staffSelected");config.staff=this;}
 static boolean enabled(PeerConfig c){return c.shared&&!c.raw.optString("staffAuthority").isEmpty();}
 static StaffAccess attach(Context c,PeerConfig config)throws Exception{return enabled(config)?new StaffAccess(c,config):null;}
 static synchronized void started(){if(activities++==0){if(backgroundAt>0&&SystemClock.elapsedRealtime()-backgroundAt>=120000)lock();backgroundAt=0;}}
 static synchronized void stopped(){activities=Math.max(0,activities-1);if(activities==0)backgroundAt=SystemClock.elapsedRealtime();}
 static void lock(){unlocked="";}
 static boolean session(String profile){return !profile.isEmpty()&&unlocked.equals(profile)&&(backgroundAt==0||SystemClock.elapsedRealtime()-backgroundAt<120000);}
 JSONArray profiles()throws Exception {String s=global.get("staffProfiles");return s.isEmpty()?new JSONArray():new JSONArray(s);}
 JSONObject local()throws Exception {JSONArray a=profiles();for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("profile").equals(profile))return a.getJSONObject(i);throw new IllegalArgumentException("Crea o selecciona tu perfil personal.");}
 private static String alias(String profile){return "comedor-staff-"+profile;}
 PrivateKey key()throws Exception {KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);return (PrivateKey)store.getKey(alias(profile),null);}
 String publicKey()throws Exception {KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);return WalletProtocol.publicText(store.getCertificate(alias(profile)).getPublicKey());}
 String create(String name,String pin)throws Exception {
  PeerConfig.require(name.trim().length()>=3&&name.trim().length()<=100&&pin.matches("[0-9]{6}"),"Escribe tu nombre y un PIN de seis números.");
  JSONArray a=profiles();PeerConfig.require(a.length()<10,"Este teléfono ya tiene diez perfiles.");String id=UUID.randomUUID().toString(),salt=PeerConfig.random();
  KeyPairGenerator gen=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");gen.initialize(new KeyGenParameterSpec.Builder(alias(id),KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build());gen.generateKeyPair();
  a.put(new JSONObject().put("profile",id).put("name",name.trim()).put("salt",salt).put("pin",pinHash(pin,salt)));
  global.put("staffProfiles",a.toString());global.put("staffSelected",id);unlocked=id;return id;
 }
 static String pinHash(String pin,String salt)throws Exception {PBEKeySpec spec=new PBEKeySpec(pin.toCharArray(),salt.getBytes(java.nio.charset.StandardCharsets.UTF_8),210000,256);try{return PeerConfig.hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}finally{spec.clearPassword();}}
 void unlock(String pin)throws Exception {
  JSONObject p=local();long now=System.currentTimeMillis(),until=Long.parseLong(global.get("staffWait:"+profile).isEmpty()?"0":global.get("staffWait:"+profile));
  PeerConfig.require(now>=until,"Demasiados intentos. Espera antes de volver a probar.");
  if(!PeerConfig.equal(pinHash(pin,p.getString("salt")),p.getString("pin"))){String previous=global.get("staffFailures:"+profile);int failures=Math.min(100,Integer.parseInt(previous.isEmpty()?"0":previous)+1);global.put("staffFailures:"+profile,Integer.toString(failures));if(failures>=5)global.put("staffWait:"+profile,Long.toString(now+Math.min(900000L,30000L*(1L<<Math.min(5,failures-5)))));throw new IllegalArgumentException("PIN incorrecto.");}
  global.remove("staffFailures:"+profile);global.remove("staffWait:"+profile);unlocked=profile;
 }
 void select(String id)throws Exception {boolean exists=false;JSONArray a=profiles();for(int i=0;i<a.length();i++)exists|=a.getJSONObject(i).getString("profile").equals(id);PeerConfig.require(exists,"Perfil desconocido.");global.put("staffSelected",id);lock();}
 String certificate(){return companyVault.get("staffCert:"+profile);}
 JSONObject grant()throws Exception {PeerConfig.require(!certificate().isEmpty(),"Este perfil está pendiente de autorización del usuario maestro.");JSONObject g=StaffProtocol.grant(certificate(),config);String replacement=companyVault.get("staffReplacement:"+profile);if(!replacement.isEmpty()){JSONObject newer=StaffProtocol.grant(replacement,config);PeerConfig.require(!newer.getString("id").equals(g.getString("id"))||newer.getLong("revision")<=g.getLong("revision"),"Este teléfono fue reemplazado. Solicita una nueva autorización.");}JSONObject current=currentFor(certificate());PeerConfig.require(current.getBoolean("active")&&current.getString("key").equals(g.getString("key"))&&current.getLong("revision")==g.getLong("revision"),"Cuenta desactivada o teléfono reemplazado.");PeerConfig.require(g.getString("key").equals(publicKey())&&g.getString("device").equals(config.device),"Autoriza nuevamente este teléfono.");return g;}
 boolean authorized(){try{return grant().getBoolean("active");}catch(Exception e){return false;}}
 boolean ready(){return session(profile)&&authorized();}
 void require(String action,String plant)throws Exception {PeerConfig.require(session(profile),"Desbloquea tu cuenta con el PIN.");StaffProtocol.require(grant(),StaffProtocol.action(action),plant);}
 boolean can(String action){try{return session(profile)&&StaffProtocol.allows(grant(),StaffProtocol.action(action),"");}catch(Exception e){return false;}}
 boolean master(){try{return session(profile)&&grant().getBoolean("active")&&grant().getString("role").equals("master");}catch(Exception e){return false;}}
 JSONObject operator()throws Exception {JSONObject g=grant();PeerConfig.require(g.getBoolean("active"),"Cuenta desactivada. Contacta al usuario maestro.");return StaffProtocol.operator(g);}
 JSONObject sign(String route,JSONObject data)throws Exception {return StaffProtocol.proof(key(),publicKey(),config,route,data,System.currentTimeMillis());}
 JSONObject remote(String action,JSONObject fields)throws Exception {
  if(!action.equals("status"))PeerConfig.require(session(profile),"Desbloquea tu perfil antes de administrar el acceso.");
  JSONObject body=new JSONObject(fields.toString()).put("action",action),proof=sign("staff",body);
  JSONObject reply=SharedNetwork.http(config.raw.getString("coordinator")+"/staff?request="+PeerConfig.random(),proof,"Bearer "+config.raw.getString("networkKey"));
  PeerConfig.require(reply.optBoolean("ok"),reply.optString("error","No se confirmó el permiso."));saveReply(reply);return reply;
 }
 synchronized void saveReply(JSONObject reply)throws Exception {
  if(reply.has("replacement")&&!certificate().isEmpty()){String value=reply.getString("replacement");JSONObject newer=StaffProtocol.grant(value,config),old=StaffProtocol.grant(certificate(),config);PeerConfig.require(newer.getString("id").equals(old.getString("id"))&&newer.getLong("revision")>old.getLong("revision")&&!newer.getString("key").equals(old.getString("key")),"Respuesta de reemplazo inválida.");companyVault.put("staffReplacement:"+profile,value);}
  if(reply.has("directory")){String cert=reply.getString("directory");JSONObject dir=StaffProtocol.open(cert,config);PeerConfig.require(dir.getString("kind").equals("directory")&&dir.getLong("revision")>=0,"Lista de cuentas inválida.");String previous=companyVault.get("staffDirectory");long revision=previous.isEmpty()?0:StaffProtocol.open(previous,config).getLong("revision");
   if(dir.getLong("revision")>=revision){JSONArray users=dir.getJSONArray("accounts");Set<String> ids=new HashSet<>();for(int i=0;i<users.length();i++){JSONObject g=StaffProtocol.grant(users.getString(i),config);PeerConfig.require(ids.add(g.getString("id")),"Cuenta repetida.");}companyVault.put("staffDirectory",cert);for(int i=0;i<users.length();i++){String value=users.getString(i);JSONObject g=StaffProtocol.grant(value,config);if(g.getString("key").equals(publicKey()))saveGrant(value);}}
  }
  if(reply.has("certificate")){String value=reply.getString("certificate");JSONObject g=StaffProtocol.grant(value,config);if(g.getString("key").equals(publicKey()))saveGrant(value);}
 }
 private void saveGrant(String value)throws Exception {JSONObject g=StaffProtocol.grant(value,config);String old=certificate();if(!old.isEmpty()&&StaffProtocol.grant(old,config).getLong("revision")>g.getLong("revision"))return;companyVault.put("staffCert:"+profile,value);}
 void refresh(boolean force)throws Exception {long now=SystemClock.elapsedRealtime();if(!force&&now-refreshed<30000)return;remote("status",new JSONObject());refreshed=now;}
 JSONObject directory()throws Exception {String value=companyVault.get("staffDirectory");return value.isEmpty()?null:StaffProtocol.open(value,config);}
 JSONObject currentFor(String cert)throws Exception {JSONObject g=StaffProtocol.grant(cert,config),dir=directory();if(dir!=null){JSONArray all=dir.getJSONArray("accounts");for(int i=0;i<all.length();i++){JSONObject u=StaffProtocol.grant(all.getString(i),config);if(u.getString("id").equals(g.getString("id"))&&u.getLong("revision")>=g.getLong("revision"))return u;}}return g;}
 JSONObject authenticate(JSONObject proof,String route)throws Exception {String cert=proof.getString("certificate");JSONObject g=StaffProtocol.grant(cert,config),current=currentFor(cert);PeerConfig.require(current.getBoolean("active")&&current.getString("key").equals(g.getString("key"))&&current.getLong("revision")==g.getLong("revision")&&current.getLong("revision")==g.getLong("revision"),"Cuenta desactivada o permisos actualizados. Sincroniza tu acceso.");return StaffProtocol.verifyProof(proof,route,config,System.currentTimeMillis(),g);}
 JSONObject networkProof(String route,JSONObject request)throws Exception {JSONObject g=grant();PeerConfig.require(g.getBoolean("active"),"Cuenta desactivada.");return sign(route,request).put("certificate",certificate());}
 JSONArray users()throws Exception {JSONObject d=directory();JSONArray result=new JSONArray();if(d!=null){JSONArray all=d.getJSONArray("accounts");for(int i=0;i<all.length();i++)result.put(StaffProtocol.grant(all.getString(i),config));}return result;}
}
