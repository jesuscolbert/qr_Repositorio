package bo.soboce.comedor;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.Normalizer;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** SCRAM-SHA-256 proof calculations (RFC 5802/7677); never persist a password. */
final class StaffPassword {
 static final int ITERATIONS=600000;
 static String username(String value){String name=value.trim().toLowerCase(Locale.ROOT);PeerConfig.require(name.matches("[a-z0-9][a-z0-9._-]{2,39}"),"Usuario: de 3 a 40 letras, números, puntos, guiones o guiones bajos.");return name;}
 static String normalize(String password){String p=Normalizer.normalize(password,Normalizer.Form.NFKC);PeerConfig.require(p.length()>=10&&p.length()<=128&&p.codePoints().noneMatch(Character::isISOControl),"Usa una contraseña de 10 a 128 caracteres, sin saltos de línea.");return p;}
 static String b64(byte[] b){return Base64.getEncoder().encodeToString(b);}
 static byte[] bytes(String s)throws Exception {byte[] b=Base64.getDecoder().decode(s);PeerConfig.require(b.length==32&&b64(b).equals(s),"Verificador de contraseña inválido.");return b;}
 static byte[] hash(byte[] b)throws Exception{return MessageDigest.getInstance("SHA-256").digest(b);}
 static byte[] hmac(byte[] key,String text)throws Exception {Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));}
 static byte[] derive(String password,byte[] salt,int iterations)throws Exception {PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,iterations,256);try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}finally{spec.clearPassword();}}
 static JSONObject credentials(String password)throws Exception {byte[] salt=new byte[32];new SecureRandom().nextBytes(salt);byte[] salted=derive(normalize(password),salt,ITERATIONS);try{return new JSONObject().put("scheme","SCRAM-SHA-256").put("iterations",ITERATIONS).put("salt",b64(salt)).put("storedKey",b64(hash(hmac(salted,"Client Key")))).put("serverKey",b64(hmac(salted,"Server Key")));}finally{Arrays.fill(salted,(byte)0);}}
 static final class Proof {final String proof,serverSignature;final JSONObject verifier;Proof(String p,String s,JSONObject v){proof=p;serverSignature=s;verifier=v;}}
 static Proof proof(String user,String password,String clientNonce,String serverFirst)throws Exception {
  String name=username(user);String[] parts=serverFirst.split(",",-1);PeerConfig.require(clientNonce.matches("[a-f0-9]{64}")&&parts.length==3&&parts[0].startsWith("r="+clientNonce)&&parts[0].substring(2).matches("[a-f0-9]{128}")&&parts[1].startsWith("s=")&&parts[2].equals("i="+ITERATIONS),"Respuesta de acceso incompatible.");
  String salt=parts[1].substring(2),nonce=parts[0].substring(2),message="n="+name+",r="+clientNonce+","+serverFirst+",c=biws,r="+nonce;
  byte[] salted=derive(normalize(password),bytes(salt),ITERATIONS);try{byte[] clientKey=hmac(salted,"Client Key"),storedKey=hash(clientKey),signature=hmac(storedKey,message),proof=new byte[32];for(int i=0;i<32;i++)proof[i]=(byte)(clientKey[i]^signature[i]);return new Proof(b64(proof),b64(hmac(hmac(salted,"Server Key"),message)),new JSONObject().put("scheme","SCRAM-SHA-256").put("iterations",ITERATIONS).put("salt",salt).put("storedKey",b64(storedKey)));}finally{Arrays.fill(salted,(byte)0);}
 }
 static boolean verifyLocal(String password,JSONObject verifier)throws Exception {PeerConfig.require(verifier.getString("scheme").equals("SCRAM-SHA-256")&&verifier.getInt("iterations")==ITERATIONS,"Acceso guardado incompatible.");byte[] salted=derive(normalize(password),bytes(verifier.getString("salt")),ITERATIONS);try{return MessageDigest.isEqual(hash(hmac(salted,"Client Key")),bytes(verifier.getString("storedKey")));}finally{Arrays.fill(salted,(byte)0);}}
}
