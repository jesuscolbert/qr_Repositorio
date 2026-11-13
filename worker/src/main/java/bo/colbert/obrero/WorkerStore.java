package bo.colbert.obrero;

import android.content.Context;
import android.security.keystore.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class WorkerStore {
 private static final String SIGN="worker-sign-v1",DATA="worker-data-v1";final android.content.SharedPreferences prefs;
 WorkerStore(Context context){prefs=context.getSharedPreferences("worker-private",0);}
 static KeyStore keyStore()throws Exception {KeyStore s=KeyStore.getInstance("AndroidKeyStore");s.load(null);return s;}
 KeyPair identity()throws Exception {
  KeyStore s=keyStore();if(!s.containsAlias(SIGN)){KeyPairGenerator g=KeyPairGenerator.getInstance("EC","AndroidKeyStore");g.initialize(new KeyGenParameterSpec.Builder(SIGN,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(true).setUserAuthenticationValidityDurationSeconds(300).build());g.generateKeyPair();s=keyStore();}
  return new KeyPair(s.getCertificate(SIGN).getPublicKey(),(PrivateKey)s.getKey(SIGN,null));
 }
 private SecretKey dataKey()throws Exception {KeyStore s=keyStore();if(!s.containsAlias(DATA)){KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(DATA,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}return (SecretKey)keyStore().getKey(DATA,null);}
 private String encrypt(String value)throws Exception {Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,dataKey());return Base64.getEncoder().encodeToString(c.getIV())+"."+Base64.getEncoder().encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)));}
 void put(String key,String value)throws Exception {if(!prefs.edit().putString(key,encrypt(value)).commit())throw new java.io.IOException("No se pudo guardar la credencial.");}
 boolean enrolled(){return prefs.contains("certificate");}
 void activate(String certificate)throws Exception {if(enrolled())throw new IllegalArgumentException("Este celular ya está vinculado. Reactívalo con el encargado para cambiar de persona.");if(!prefs.edit().putString("certificate",encrypt(certificate)).remove("history").remove("pairNonce").remove("pairTime").commit())throw new java.io.IOException("No se pudo guardar la credencial.");}
 String get(String key)throws Exception {String value=prefs.getString(key,"");if(value.isEmpty())return "";String[] a=value.split("\\.");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,dataKey(),new GCMParameterSpec(128,Base64.getDecoder().decode(a[0])));return new String(c.doFinal(Base64.getDecoder().decode(a[1])),StandardCharsets.UTF_8);}
 void reset()throws Exception {if(!prefs.edit().clear().commit())throw new java.io.IOException("No se pudo borrar la credencial local.");KeyStore s=keyStore();if(s.containsAlias(SIGN))s.deleteEntry(SIGN);if(s.containsAlias(DATA))s.deleteEntry(DATA);}
}
