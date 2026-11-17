package bo.soboce.comedor;

import bo.colbert.credential.WalletProtocol;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.*;

/** Individually signed permissions. The APK contains the authority public key only. */
final class StaffProtocol {
 static final String CERT="CSA1:", REQUEST="CSR1:", MASTER="CSM1:";
 static final List<String> ACTIONS=Arrays.asList("scan","link","people","report","issue","worker","visitor");
 static JSONObject open(String cert,PeerConfig config)throws Exception {
  PeerConfig.require(cert!=null&&cert.startsWith(CERT)&&cert.length()<=1048576,"Permiso de cuenta inválido.");
  String[] parts=cert.substring(CERT.length()).split("\\.",-1);PeerConfig.require(parts.length==2,"Permiso incompleto.");
  PeerConfig.require(WalletProtocol.verify(config.raw.getString("staffAuthority"),CERT+parts[0],parts[1]),"Firma de cuenta no válida.");
  JSONObject body=new JSONObject(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8));
  PeerConfig.require(body.getString("cluster").equals(config.cluster),"La cuenta pertenece a otra empresa.");return body;
 }
 static JSONObject grant(String cert,PeerConfig config)throws Exception {
  JSONObject g=open(cert,config);PeerConfig.require(g.getString("kind").equals("grant")&&g.getString("id").matches("[a-f0-9-]{36}")&&g.getString("device").matches("[a-f0-9]{64}")&&g.getLong("revision")>0&&g.getLong("issued")>0,"Cuenta incompatible.");
  PeerConfig.require(Arrays.asList("master","admin","encargado").contains(g.getString("role"))&&g.getString("name").length()>=3&&g.getString("name").length()<=100,"Rol o nombre inválido.");
  WalletProtocol.publicKey(g.getString("key"));g.getBoolean("active");JSONArray plants=g.getJSONArray("plants");PeerConfig.require(plants.length()>0&&plants.length()<=100,"Sin plantas autorizadas.");Set<String> seen=new HashSet<>();
  for(int i=0;i<plants.length();i++)PeerConfig.require(seen.add(plants.getString(i))&&contains(config.seed.getJSONArray("plants"),plants.getString(i)),"Planta desconocida.");
  JSONArray permissions=g.getJSONArray("permissions");for(int i=0;i<permissions.length();i++)PeerConfig.require(ACTIONS.contains(permissions.getString(i)),"Permiso desconocido.");return g;
 }
 static boolean contains(JSONArray a,String value){if(a==null)return false;for(int i=0;i<a.length();i++)if(value.equals(a.optString(i)))return true;return false;}
 static boolean allows(JSONObject g,String action,String plant){return g!=null&&g.optBoolean("active")&&(plant==null||plant.isEmpty()||contains(g.optJSONArray("plants"),plant))&&(g.optString("role").equals("master")||contains(g.optJSONArray("permissions"),action));}
 static void require(JSONObject g,String action,String plant){PeerConfig.require(allows(g,action,plant),"Tu cuenta no tiene permiso para esta operación o planta.");}
 static JSONObject operator(JSONObject g)throws Exception{return new JSONObject().put("name",g.getString("name")).put("role",g.getString("role").equals("encargado")?"encargado":"admin").put("plant",g.getJSONArray("plants").getString(0)).put("plants",g.getJSONArray("plants")).put("staffId",g.getString("id")).put("staffRole",g.getString("role")).put("staffRevision",g.getLong("revision"));}
 static boolean plant(JSONObject op,String plant){return op.has("staffId")?contains(op.optJSONArray("plants"),plant):op.optString("role").equals("admin")||plant.equals(op.optString("plant"));}
 static String action(String action){return Arrays.asList("today","conflicts","report").contains(action)?"report":action.equals("session")?"people":action;}
 static JSONObject proof(PrivateKey key,String pub,PeerConfig config,String route,JSONObject payload,long now)throws Exception {
  String data=payload.toString(),nonce=PeerConfig.random();return new JSONObject().put("key",pub).put("time",now).put("nonce",nonce).put("payload",data).put("signature",WalletProtocol.sign(key,"staff-request|"+config.cluster+"|"+route+"|"+now+"|"+nonce+"|"+data));
 }
 static JSONObject verifyProof(JSONObject proof,String route,PeerConfig config,long now,JSONObject grant)throws Exception{
  long time=proof.getLong("time");String nonce=proof.getString("nonce"),payload=proof.getString("payload");
  PeerConfig.require(Math.abs(now-time)<=120000&&nonce.matches("[a-f0-9]{64}")&&payload.length()<=1048576,"Solicitud vencida; comprueba la hora.");
  PeerConfig.require(grant.optBoolean("active")&&grant.getLong("issued")<=time+120000&&grant.getString("key").equals(proof.getString("key"))&&WalletProtocol.verify(grant.getString("key"),"staff-request|"+config.cluster+"|"+route+"|"+time+"|"+nonce+"|"+payload,proof.getString("signature")),"Solicitud de otro encargado.");return new JSONObject(payload);
 }
 static JSONObject evidence(PeerConfig config,StaffAccess auth,String purpose,String body)throws Exception{return new JSONObject().put("certificate",auth.certificate()).put("signature",WalletProtocol.sign(auth.key(),"staff-event|"+config.cluster+"|"+purpose+"|"+body));}
 static JSONObject verifyEvidence(PeerConfig config,JSONObject proof,String purpose,String body,long when)throws Exception{
  JSONObject g=grant(proof.getString("certificate"),config);PeerConfig.require(g.optBoolean("active")&&g.getLong("issued")<=when+120000&&WalletProtocol.verify(g.getString("key"),"staff-event|"+config.cluster+"|"+purpose+"|"+body,proof.getString("signature")),"Registro sin autorización individual válida.");return g;
 }
}
