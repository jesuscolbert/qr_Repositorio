package bo.colbert.cobros;
import bo.colbert.licensing.LicenseProtocol;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class BillingBackup {
 static byte[] encrypt(JSONObject data,char[] password)throws Exception{LicenseProtocol.require(password.length>=10,"Usa una contraseña de al menos 10 caracteres.");byte[] salt=new byte[16],iv=new byte[12];new SecureRandom().nextBytes(salt);new SecureRandom().nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(password,salt),new GCMParameterSpec(128,iv));c.updateAAD("COLBERT-ENCRYPTED-1".getBytes(StandardCharsets.UTF_8));byte[] ciphertext=c.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));return new JSONObject().put("format","COLBERT-ENCRYPTED-1").put("salt",b64(salt)).put("iv",b64(iv)).put("data",b64(ciphertext)).toString().getBytes(StandardCharsets.UTF_8);}
 static JSONObject decrypt(byte[] bytes,char[] password)throws Exception{JSONObject b=new JSONObject(new String(bytes,StandardCharsets.UTF_8));LicenseProtocol.require(b.getString("format").equals("COLBERT-ENCRYPTED-1"),"Formato incorrecto.");byte[] salt=Base64.getDecoder().decode(b.getString("salt")),iv=Base64.getDecoder().decode(b.getString("iv"));LicenseProtocol.require(salt.length==16&&iv.length==12,"Respaldo inválido.");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(password,salt),new GCMParameterSpec(128,iv));c.updateAAD("COLBERT-ENCRYPTED-1".getBytes(StandardCharsets.UTF_8));return new JSONObject(new String(c.doFinal(Base64.getDecoder().decode(b.getString("data"))),StandardCharsets.UTF_8));}
 private static javax.crypto.SecretKey key(char[] password,byte[] salt)throws Exception{PBEKeySpec spec=new PBEKeySpec(password,salt,210000,256);try{return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(),"AES");}finally{spec.clearPassword();}}
 private static String b64(byte[] b){return Base64.getEncoder().encodeToString(b);}
}
