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
 private static final Object CACHE_LOCK=new Object();
 private volatile long refreshed;
 private volatile String cachedPublicKey;
 private String indexedDirectory="";private final Map<String,String> indexedAccounts=new HashMap<>();
 StaffAccess(Context c,PeerConfig config)throws Exception {this.context=c.getApplicationContext();this.config=config;global=new Vault(c,Companies.primary(c));companyVault=new Vault(c,Companies.company(c,config));JSONArray saved=profiles();String selected=global.get("staffSelected");boolean found=false;for(int i=0;i<saved.length();i++)found|=saved.getJSONObject(i).getString("profile").equals(selected);if(!found)selected=saved.length()==1?saved.getJSONObject(0).getString("profile"):"";profile=selected;config.staff=this;}
 static boolean enabled(PeerConfig c){return c.shared&&!c.raw.optString("staffAuthority").isEmpty();}
 static StaffAccess attach(Context c,PeerConfig config)throws Exception{return enabled(config)?new StaffAccess(c,config):null;}
 static synchronized void started(){if(activities++==0){if(backgroundAt>0&&SystemClock.elapsedRealtime()-backgroundAt>=120000)lock();backgroundAt=0;}}
 static synchronized void stopped(){activities=Math.max(0,activities-1);if(activities==0)backgroundAt=SystemClock.elapsedRealtime();}
 static void lock(){unlocked="";}
 static boolean session(String profile){return !profile.isEmpty()&&unlocked.equals(profile)&&(backgroundAt==0||SystemClock.elapsedRealtime()-backgroundAt<120000);}
 private static JSONArray validProfiles(String text)throws Exception {JSONArray a=new JSONArray(text);Set<String> ids=new HashSet<>();PeerConfig.require(a.length()<=10,"Lista de perfiles inválida.");for(int i=0;i<a.length();i++){JSONObject p=a.getJSONObject(i);PeerConfig.require(ids.add(UUID.fromString(p.getString("profile")).toString())&&p.getString("name").trim().length()>=3&&p.getString("salt").matches("[a-f0-9]{64}")&&p.getString("pin").matches("[a-f0-9]{64}"),"Perfil guardado inválido.");}return a;}
 JSONArray profiles()throws Exception {synchronized(CACHE_LOCK){
  String primary=null,backup=null;Exception problem=null;
  try{primary=global.read("staffProfiles");if(!primary.isEmpty())validProfiles(primary);}catch(Exception e){problem=e;primary=null;}
  try{backup=global.read("staffProfilesBackup");if(!backup.isEmpty())validProfiles(backup);}catch(Exception e){if(problem==null)problem=e;backup=null;}
  // A valid encrypted copy repairs a missing/corrupt index, retaining the original PIN and key.
  if(primary!=null&&!primary.isEmpty()&&!primary.equals("[]")){if(!primary.equals(backup))global.put("staffProfilesBackup",primary);return validProfiles(primary);}
  if(backup!=null&&!backup.isEmpty()&&!backup.equals("[]")){global.put("staffProfiles",backup);return validProfiles(backup);}
  if(problem!=null)throw problem;
  return new JSONArray();
 }}
 JSONObject local()throws Exception {JSONArray a=profiles();for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("profile").equals(profile))return a.getJSONObject(i);throw new IllegalArgumentException("Crea o selecciona tu perfil personal.");}
 private static String alias(String profile){return "comedor-staff-"+profile;}
 PrivateKey key()throws Exception {KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);return (PrivateKey)store.getKey(alias(profile),null);}
 String publicKey()throws Exception {String value=cachedPublicKey;if(value!=null)return value;KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);value=WalletProtocol.publicText(store.getCertificate(alias(profile)).getPublicKey());cachedPublicKey=value;return value;}
 String create(String name,String pin)throws Exception {return create(name,pin,"new");}
 String create(String name,String pin,String intent)throws Exception {synchronized(CACHE_LOCK){
  PeerConfig.require(Arrays.asList("new","recover","password").contains(intent),"Tipo de acceso inválido.");
  PeerConfig.require(name.trim().length()>=3&&name.trim().length()<=100&&pin.matches("[0-9]{6}"),"Escribe tu nombre y un PIN de seis números.");
  JSONArray a=profiles();PeerConfig.require(a.length()<10,"Este teléfono ya tiene diez perfiles.");String id=UUID.randomUUID().toString(),salt=PeerConfig.random();
  KeyPairGenerator gen=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");gen.initialize(new KeyGenParameterSpec.Builder(alias(id),KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build());gen.generateKeyPair();
  a.put(new JSONObject().put("profile",id).put("name",name.trim()).put("salt",salt).put("pin",pinHash(pin,salt)).put("intent",intent));
  Map<String,String> values=new HashMap<>();values.put("staffProfiles",a.toString());values.put("staffProfilesBackup",a.toString());values.put("staffSelected",id);global.putAll(values);unlocked=id;return id;
 }}
 static String pinHash(String pin,String salt)throws Exception {PBEKeySpec spec=new PBEKeySpec(pin.toCharArray(),salt.getBytes(java.nio.charset.StandardCharsets.UTF_8),210000,256);try{return PeerConfig.hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}finally{spec.clearPassword();}}
 void unlock(String pin)throws Exception {
  PeerConfig.require(certificate().isEmpty()||StaffProtocol.grant(certificate(),config).optString("username").isEmpty(),"Esta cuenta ya usa usuario y contraseña. Entra desde Iniciar sesión.");
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
 interface LoginTransport {JSONObject call(StaffAccess access,String action,JSONObject fields)throws Exception;}
 private JSONObject passwordRemote(String action,JSONObject fields)throws Exception {JSONObject body=new JSONObject(fields.toString()).put("action",action);JSONObject reply=SharedNetwork.http(config.raw.getString("coordinator")+"/staff?request="+PeerConfig.random(),sign("staff",body),"Bearer "+config.raw.getString("networkKey"));PeerConfig.require(reply.optBoolean("ok"),reply.optString("error","No se pudo iniciar sesión."));return reply;}
 static StaffAccess loginPassword(Context c,PeerConfig config,String username,String password)throws Exception{return loginPassword(c,config,username,password,(a,action,fields)->a.passwordRemote(action,fields));}
 static StaffAccess loginPassword(Context c,PeerConfig config,String username,String password,LoginTransport transport)throws Exception {
  String name=StaffPassword.username(username);StaffPassword.normalize(password);StaffAccess previous=new StaffAccess(c,config);String selected="";JSONArray all=previous.profiles();
  for(int i=0;i<all.length();i++){JSONObject p=all.getJSONObject(i);String id=p.getString("profile"),cert=previous.companyVault.get("staffCert:"+id),cached=previous.companyVault.get("staffLogin:"+id);boolean matches=p.optString("loginUsername").equals(name)&&p.optString("loginCluster").equals(config.cluster);if(!cached.isEmpty())matches|=new JSONObject(cached).optString("username").equals(name);if(!cert.isEmpty())matches|=StaffProtocol.grant(cert,config).optString("username").equals(name);if(matches){KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(ks.containsAlias(alias(id))){selected=id;break;}}}
  boolean created=selected.isEmpty(),completed=false;
  if(created){selected=previous.create("Usuario "+name,String.format(Locale.ROOT,"%06d",new SecureRandom().nextInt(1000000)),"password");synchronized(CACHE_LOCK){JSONArray profiles=previous.profiles();for(int i=0;i<profiles.length();i++)if(profiles.getJSONObject(i).getString("profile").equals(selected))profiles.getJSONObject(i).put("loginUsername",name).put("loginCluster",config.cluster);previous.writeProfiles(profiles);}}
  previous.select(selected);StaffAccess access=new StaffAccess(c,config);boolean attempted=false;
  try{
   access.passwordWait();attempted=true;
   String nonce=PeerConfig.random();JSONObject start=transport.call(access,"loginStart",new JSONObject().put("username",name).put("device",config.device).put("clientNonce",nonce));PeerConfig.require(start.optBoolean("ok"),start.optString("error","No se pudo iniciar sesión."));
   StaffPassword.Proof proof=StaffPassword.proof(name,password,nonce,start.getString("serverFirst"));JSONObject reply=transport.call(access,"loginFinish",new JSONObject().put("challenge",start.getString("challenge")).put("proof",proof.proof));PeerConfig.require(reply.optBoolean("ok"),reply.optString("error","Usuario o contraseña incorrectos."));
   PeerConfig.require(MessageDigest.isEqual(StaffPassword.bytes(reply.getString("serverSignature")),StaffPassword.bytes(proof.serverSignature)),"No se pudo verificar el servidor de acceso.");
   JSONObject grant=StaffProtocol.grant(reply.getString("certificate"),config);PeerConfig.require(grant.optString("username").equals(name)&&grant.getString("key").equals(access.publicKey())&&grant.getString("device").equals(config.device)&&grant.getBoolean("active"),"El acceso recibido pertenece a otra cuenta o teléfono.");access.saveReply(reply);access.rememberPassword(proof.verifier);access.passwordSucceeded();completed=true;return access;
  }catch(java.io.IOException offline){access.unlockPasswordOffline(name,password);completed=true;return access;}
  catch(Exception rejected){if(attempted)access.passwordFailed();throw rejected;}
  finally{if(!completed){if(created&&access.certificate().isEmpty()){synchronized(CACHE_LOCK){JSONArray profiles=previous.profiles(),kept=new JSONArray();for(int i=0;i<profiles.length();i++)if(!profiles.getJSONObject(i).getString("profile").equals(access.profile))kept.put(profiles.getJSONObject(i));previous.writeProfiles(kept);KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);ks.deleteEntry(alias(access.profile));access.companyVault.remove("staffPasswordFailures:"+access.profile);access.companyVault.remove("staffPasswordWait:"+access.profile);}}previous.global.put("staffSelected",previous.profile);config.staff=previous;lock();}}
 }
 private void writeProfiles(JSONArray profiles)throws Exception {Map<String,String> values=new HashMap<>();values.put("staffProfiles",profiles.toString());values.put("staffProfilesBackup",profiles.toString());global.putAll(values);}
 void rememberPassword(JSONObject credentials)throws Exception {JSONObject g=grant();PeerConfig.require(g.has("username")&&g.optLong("passwordVersion")>0,"Falta configurar la contraseña de esta cuenta.");JSONObject verifier=new JSONObject().put("scheme","SCRAM-SHA-256").put("iterations",StaffPassword.ITERATIONS).put("salt",credentials.getString("salt")).put("storedKey",credentials.getString("storedKey")).put("username",g.getString("username")).put("account",g.getString("id")).put("version",g.getLong("passwordVersion"));StaffPassword.bytes(verifier.getString("salt"));StaffPassword.bytes(verifier.getString("storedKey"));companyVault.put("staffLogin:"+profile,verifier.toString());synchronized(CACHE_LOCK){JSONArray profiles=profiles();for(int i=0;i<profiles.length();i++)if(profiles.getJSONObject(i).getString("profile").equals(profile))profiles.getJSONObject(i).put("name",g.getString("name"));writeProfiles(profiles);}}
 boolean passwordCached(){try{String text=companyVault.read("staffLogin:"+profile);if(text.isEmpty())return false;JSONObject v=new JSONObject(text),g=grant();return v.getString("account").equals(g.getString("id"))&&v.getString("username").equals(g.optString("username"))&&v.getLong("version")==g.optLong("passwordVersion");}catch(Exception e){return false;}}
 String username(){try{return grant().optString("username");}catch(Exception e){try{return local().optString("loginUsername");}catch(Exception ignored){return "";}}}
 private void passwordWait()throws Exception {String wait=companyVault.read("staffPasswordWait:"+profile);PeerConfig.require(wait.isEmpty()||System.currentTimeMillis()>=Long.parseLong(wait),"Demasiados intentos. Espera antes de volver a entrar.");}
 private void passwordFailed()throws Exception {String value=companyVault.read("staffPasswordFailures:"+profile);int n=Math.min(100,Integer.parseInt(value.isEmpty()?"0":value)+1);companyVault.put("staffPasswordFailures:"+profile,Integer.toString(n));if(n>=5)companyVault.put("staffPasswordWait:"+profile,Long.toString(System.currentTimeMillis()+Math.min(900000L,30000L*(1L<<Math.min(5,n-5)))));lock();}
 private void passwordSucceeded(){companyVault.remove("staffPasswordFailures:"+profile);companyVault.remove("staffPasswordWait:"+profile);unlocked=profile;}
 void unlockPasswordOffline(String username,String password)throws Exception {passwordWait();try{PeerConfig.require(passwordCached(),"Conecta este teléfono a internet para iniciar sesión por primera vez o actualizar tu contraseña.");JSONObject cached=new JSONObject(companyVault.read("staffLogin:"+profile));PeerConfig.require(cached.getString("username").equals(StaffPassword.username(username))&&StaffPassword.verifyLocal(password,cached),"Usuario o contraseña incorrectos.");passwordSucceeded();}catch(Exception e){passwordFailed();throw e;}}
 boolean biometricAllowed(){try{return profiles().length()==1&&authorized()&&(username().isEmpty()||passwordCached())&&global.get("staffBiometric:"+profile).equals("yes");}catch(Exception e){return false;}}
 String biometricSetupProblem(){try{
  if(!authorized())return "Primero inicia sesión con tu usuario y contraseña en esta empresa.";
  int count=profiles().length();if(count!=1)return "Hay "+count+" perfiles guardados en esta instalación. La huella solo se habilita con un único perfil personal; en un celular compartido utiliza contraseña. No borres los datos del comedor.";
  if(username().isEmpty())return "Esta cuenta todavía usa el PIN anterior. La cuenta maestra debe configurar su usuario y contraseña en la cuenta existente.";
  if(!passwordCached())return "Entra una vez con tu usuario y contraseña actual y conexión a internet para habilitar la huella en este celular.";
  return "";
 }catch(Exception e){return "No se pudo comprobar el perfil guardado. Reintenta sin borrar los datos.";}}
 void enableBiometric()throws Exception {PeerConfig.require(ready(),"Entra con tu contraseña antes de activar la huella.");String problem=biometricSetupProblem();PeerConfig.require(problem.isEmpty(),problem);global.put("staffBiometric:"+profile,"yes");}
 void disableBiometric()throws Exception {PeerConfig.require(ready(),"Entra con tu contraseña antes de cambiar el acceso.");global.put("staffBiometric:"+profile,"no");}
 void saveReply(JSONObject reply)throws Exception {synchronized(CACHE_LOCK){
  if(reply.has("replacement")&&!certificate().isEmpty()){String value=reply.getString("replacement");JSONObject newer=StaffProtocol.grant(value,config),old=StaffProtocol.grant(certificate(),config);PeerConfig.require(newer.getString("id").equals(old.getString("id"))&&newer.getLong("revision")>old.getLong("revision")&&!newer.getString("key").equals(old.getString("key")),"Respuesta de reemplazo inválida.");companyVault.put("staffReplacement:"+profile,value);}
  if(reply.has("directory")){String cert=reply.getString("directory");JSONObject dir=StaffProtocol.open(cert,config);PeerConfig.require(dir.getString("kind").equals("directory")&&dir.getLong("revision")>=0,"Lista de cuentas inválida.");String previous=companyVault.get("staffDirectory");long revision=previous.isEmpty()?0:StaffProtocol.open(previous,config).getLong("revision");
   if(dir.getLong("revision")>=revision){JSONArray users=dir.getJSONArray("accounts");Set<String> ids=new HashSet<>();for(int i=0;i<users.length();i++){JSONObject g=StaffProtocol.grant(users.getString(i),config);PeerConfig.require(ids.add(g.getString("id")),"Cuenta repetida.");}companyVault.put("staffDirectory",cert);for(int i=0;i<users.length();i++){String value=users.getString(i);JSONObject g=StaffProtocol.grant(value,config);if(g.getString("key").equals(publicKey()))saveGrant(value);}}
  }
  if(reply.has("certificate")){String value=reply.getString("certificate");JSONObject g=StaffProtocol.grant(value,config);if(g.getString("key").equals(publicKey()))saveGrant(value);}
 }}
 private void saveGrant(String value)throws Exception {JSONObject g=StaffProtocol.grant(value,config);String old=certificate();if(!old.isEmpty()&&StaffProtocol.grant(old,config).getLong("revision")>g.getLong("revision"))return;companyVault.put("staffCert:"+profile,value);}
 void refresh(boolean force)throws Exception {long now=SystemClock.elapsedRealtime();if(!force&&now-refreshed<30000)return;remote("status",new JSONObject());refreshed=now;}
 JSONObject directory()throws Exception {String value=companyVault.get("staffDirectory");return value.isEmpty()?null:StaffProtocol.open(value,config);}
 synchronized JSONObject currentFor(String cert)throws Exception {JSONObject g=StaffProtocol.grant(cert,config);String raw=companyVault.get("staffDirectory");if(!raw.equals(indexedDirectory)){Map<String,String> updated=new HashMap<>();if(!raw.isEmpty()){JSONObject dir=StaffProtocol.open(raw,config);PeerConfig.require(dir.getString("kind").equals("directory"),"Lista de cuentas inválida.");JSONArray all=dir.getJSONArray("accounts");for(int i=0;i<all.length();i++){JSONObject u=StaffProtocol.grant(all.getString(i),config);PeerConfig.require(updated.put(u.getString("id"),u.toString())==null,"Cuenta repetida.");}}indexedAccounts.clear();indexedAccounts.putAll(updated);indexedDirectory=raw;}String value=indexedAccounts.get(g.getString("id"));if(value!=null){JSONObject current=new JSONObject(value);if(current.getLong("revision")>=g.getLong("revision"))return current;}return g;}
 JSONObject authenticate(JSONObject proof,String route)throws Exception {String cert=proof.getString("certificate");JSONObject g=StaffProtocol.grant(cert,config),current=currentFor(cert);PeerConfig.require(current.getBoolean("active")&&current.getString("key").equals(g.getString("key"))&&current.getLong("revision")==g.getLong("revision")&&current.getLong("revision")==g.getLong("revision"),"Cuenta desactivada o permisos actualizados. Sincroniza tu acceso.");return StaffProtocol.verifyProof(proof,route,config,System.currentTimeMillis(),g);}
 JSONObject networkProof(String route,JSONObject request)throws Exception {JSONObject g=grant();PeerConfig.require(g.getBoolean("active"),"Cuenta desactivada.");return sign(route,request).put("certificate",certificate());}
 JSONArray users()throws Exception {JSONObject d=directory();JSONArray result=new JSONArray();if(d!=null){JSONArray all=d.getJSONArray("accounts");for(int i=0;i<all.length();i++)result.put(StaffProtocol.grant(all.getString(i),config));}return result;}
}
