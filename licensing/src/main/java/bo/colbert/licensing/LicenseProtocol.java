package bo.colbert.licensing;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

public final class LicenseProtocol {
 public static final long OFFLINE_MS=24*60*60*1000L;
 private LicenseProtocol(){}
 public static void require(boolean yes,String message){if(!yes)throw new IllegalArgumentException(message);}
 public static String random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return hex(b);}
 public static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return s.toString();}
 public static String hash(String s)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
 public static JSONObject sign(JSONObject claims,PrivateKey key,PublicKey pub)throws Exception {
  String text=claims.toString();Signature s=Signature.getInstance("SHA256withECDSA");s.initSign(key);s.update(text.getBytes(StandardCharsets.UTF_8));
  return new JSONObject().put("claims",text).put("signature",Base64.getEncoder().encodeToString(s.sign())).put("publicKey",Base64.getEncoder().encodeToString(pub.getEncoded()));
 }
 public static JSONObject verify(JSONObject envelope,String publicKey,String contract,String product,String nonce)throws Exception {
  require(publicKey.equals(envelope.getString("publicKey")),"Cambió la identidad del servidor. Contacta al administrador.");
  PublicKey key=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
  Signature verify=Signature.getInstance("SHA256withECDSA");verify.initVerify(key);verify.update(envelope.getString("claims").getBytes(StandardCharsets.UTF_8));
  require(verify.verify(Base64.getDecoder().decode(envelope.getString("signature"))),"Permiso sin firma válida.");
  JSONObject c=new JSONObject(envelope.getString("claims"));
  require(c.getInt("version")==1&&c.getString("contractId").equals(contract)&&c.getString("productId").equals(product),"Permiso de otro cliente o aplicación.");
  if(nonce!=null)require(c.getString("nonce").equals(nonce),"Respuesta de otra consulta.");
  long issued=c.getLong("issuedAt"),expires=c.getLong("expiresAt"),paid=c.getLong("paidUntil");
  require(issued>0&&expires>=issued&&expires-issued<=OFFLINE_MS,"Vigencia inválida.");
  if(c.getBoolean("allowed"))require(expires>issued&&(paid==0||expires<=paid),"El permiso supera el período pagado.");
  return c;
 }
 public static boolean usable(JSONObject claims,long elapsedSinceValidation){
  return claims.optBoolean("allowed")&&elapsedSinceValidation>=0&&elapsedSinceValidation<OFFLINE_MS&&elapsedSinceValidation<claims.optLong("expiresAt")-claims.optLong("issuedAt");
 }
}
