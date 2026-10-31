package bo.colbert.licensing;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import java.nio.charset.StandardCharsets;

/** Private, authenticated storage. Failure never silently replaces an existing secret. */
public final class SecretStore {
 private final Context context;
 public SecretStore(Context context){this.context=context.getApplicationContext();}
 private synchronized SecretKey key() throws Exception {
  KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
  if(!ks.containsAlias("colbert-licenses")){
   KeyGenerator gen=KeyGenerator.getInstance("AES","AndroidKeyStore");
   gen.init(new KeyGenParameterSpec.Builder("colbert-licenses",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();
  }
  return (SecretKey)ks.getKey("colbert-licenses",null);
 }
 public synchronized String get(String name) throws Exception {
  String data=context.getSharedPreferences("colbert-secrets",0).getString(name,"");if(data.isEmpty())return "";
  String[] p=data.split(":");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p[0],Base64.NO_WRAP)));
  return new String(cipher.doFinal(Base64.decode(p[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
 }
 public synchronized void put(String name,String value) throws Exception {
  Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
  String data=Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
  if(!context.getSharedPreferences("colbert-secrets",0).edit().putString(name,data).commit())throw new Exception("No se pudo guardar el permiso.");
 }
}
