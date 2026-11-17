package bo.soboce.comedor;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
final class Vault {
 // Reuse decrypted values only while the persisted ciphertext is exactly the same.
 // Every reader (including an existing service) observes updates/removals immediately.
 private static final java.util.LinkedHashMap<String,String[]> cache=new java.util.LinkedHashMap<>(16,.75f,true);
 private static int cacheChars;
 private static synchronized String cached(String id,String encrypted){String[] found=cache.get(id);return found!=null&&found[0].equals(encrypted)?found[1]:null;}
 private static synchronized void remember(String id,String encrypted,String plain){
  String[] old=cache.remove(id);if(old!=null)cacheChars-=old[0].length()+old[1].length();
  if(encrypted.length()+plain.length()>2*1024*1024)return;
  cache.put(id,new String[]{encrypted,plain});cacheChars+=encrypted.length()+plain.length();
  while(cache.size()>64||cacheChars>4*1024*1024){String first=cache.keySet().iterator().next();String[] removed=cache.remove(first);cacheChars-=removed[0].length()+removed[1].length();}
 }
 private final Context context;private final String preferences;
 Vault(Context c){this(c,Companies.current(c));}
 Vault(Context c,String company){context=c;preferences="vault"+Companies.suffix(c,company);}
 private javax.crypto.SecretKey key() throws Exception {
  KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
  if(!store.containsAlias("comedor")){KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder("comedor",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}
  return (javax.crypto.SecretKey)store.getKey("comedor",null);
 }
 void put(String name,String value) throws Exception {Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());String data=Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);if(!context.getSharedPreferences(preferences,0).edit().putString(name,data).commit())throw new Exception("No se pudo guardar");}
 String get(String name){try{String value=context.getSharedPreferences(preferences,0).getString(name,"");if(value.isEmpty())return "";String id=context.getPackageName()+"|"+preferences+"|"+name,hit=cached(id,value);if(hit!=null)return hit;String[] parts=value.split(":");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));String plain=new String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);remember(id,value,plain);return plain;}catch(Exception e){return "";}}
 void remove(String name){context.getSharedPreferences(preferences,0).edit().remove(name).commit();}
}
