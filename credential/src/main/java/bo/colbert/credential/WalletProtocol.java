package bo.colbert.credential;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Public protocol only. A worker never receives a comedor, tunnel or licensing secret. */
public final class WalletProtocol {
 public static final long DAY=86400000L, LIFETIME=30*DAY, STEP=30000;
 public static final String PAIR="CWPAIR1:", CERT="CWC1:", QR="CWQ1:";
 private WalletProtocol(){}
 public static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
 public static String b64(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 public static String hash(String text)throws Exception {return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
 public static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte x:bytes)b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString();}
 public static String nonce(){byte[] b=new byte[16];new SecureRandom().nextBytes(b);return hex(b);}
 public static String publicText(PublicKey key){return b64(key.getEncoded());}
 public static PublicKey publicKey(String value)throws Exception {
  require(value.length()<=160,"Clave de celular inválida.");PublicKey key=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getUrlDecoder().decode(value)));
  require(key instanceof ECPublicKey&&((ECPublicKey)key).getParams().getOrder().toString(16).equals("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551"),"Clave incompatible.");return key;
 }
 public static String sign(PrivateKey key,String message)throws Exception {Signature s=Signature.getInstance("SHA256withECDSA");s.initSign(key);s.update(message.getBytes(StandardCharsets.UTF_8));return b64(s.sign());}
 public static boolean verify(String key,String message,String signature)throws Exception {if(signature.length()>104)return false;Signature s=Signature.getInstance("SHA256withECDSA");s.initVerify(publicKey(key));s.update(message.getBytes(StandardCharsets.UTF_8));return s.verify(Base64.getUrlDecoder().decode(signature));}
 public static String pair(PrivateKey key,PublicKey pub,String nonce,long now)throws Exception {
  String body=b64(new JSONObject().put("k",publicText(pub)).put("n",nonce).put("t",now).toString().getBytes(StandardCharsets.UTF_8));return PAIR+body+"."+sign(key,PAIR+body);
 }
 public static JSONObject pairing(String qr,long now)throws Exception {
  require(qr!=null&&qr.startsWith(PAIR)&&qr.length()<1024,"Escanea Activar mi credencial desde la app del obrero; una tarjeta de comidas no activa celulares.");
  String[] a=qr.substring(PAIR.length()).split("\\.",-1);require(a.length==2,"Solicitud incompleta.");JSONObject b=new JSONObject(new String(Base64.getUrlDecoder().decode(a[0]),StandardCharsets.UTF_8));
  require(b.getString("n").matches("[a-f0-9]{32}")&&Math.abs(now-b.getLong("t"))<=300000,"La vinculación venció. Genera otra y revisa la hora automática.");require(verify(b.getString("k"),PAIR+a[0],a[1]),"El celular no confirmó su clave.");return b;
 }
 public static JSONObject certificate(String cert)throws Exception {
  require(cert!=null&&cert.startsWith(CERT)&&cert.length()<=3072,"Credencial inválida.");String[] a=cert.substring(CERT.length()).split("\\.",-1);require(a.length==2&&a[1].matches("[a-f0-9]{64}"),"Credencial inválida.");
  JSONObject b=new JSONObject(new String(Base64.getUrlDecoder().decode(a[0]),StandardCharsets.UTF_8));
  require(b.getInt("v")==1&&b.getString("c").matches("[a-f0-9]{64}")&&b.getString("tag").matches("[a-f0-9]{64}")&&b.getString("a").matches("[a-f0-9]{32}")&&b.getLong("r")>0,"Credencial incompatible.");
  require(b.getString("p").length()>0&&b.getString("p").length()<=100&&b.getString("name").length()<=100&&b.getString("plant").length()<=100&&b.getString("document").length()<=100&&b.getString("by").length()<=100,"Datos inválidos.");
  require(b.getLong("i")>0&&b.getLong("e")>b.getLong("i")&&b.getLong("e")-b.getLong("i")<=LIFETIME,"Vigencia inválida.");publicKey(b.getString("k"));
  require(b.getString("url").matches("https://[a-z0-9][a-z0-9.-]+[a-z0-9]"),"Dirección incompatible.");return b;
 }
 public static String certificateBody(String cert){return cert.substring(CERT.length(),cert.lastIndexOf('.'));}
 public static String certificateMac(String cert){return cert.substring(cert.lastIndexOf('.')+1);}
 public static boolean isQr(String value){return value!=null&&value.startsWith(QR)&&value.length()<=3400;}
 public static String qr(String cert,PrivateKey key,long now)throws Exception {JSONObject b=certificate(cert);require(now>=b.getLong("i")-15000&&now<b.getLong("e"),"Renueva la credencial con conexión o con el encargado.");long step=now/STEP;return QR+cert.substring(CERT.length())+"."+step+"."+sign(key,"qr|"+cert+"|"+step);}
 public static String qrCertificate(String qr){require(isQr(qr),"QR inválido.");String[] a=qr.substring(QR.length()).split("\\.",-1);require(a.length==4,"QR incompleto.");return CERT+a[0]+"."+a[1];}
 public static JSONObject verifyQr(String qr,long now)throws Exception {
  String cert=qrCertificate(qr);JSONObject b=certificate(cert);String[] a=qr.substring(QR.length()).split("\\.",-1);long step=Long.parseLong(a[2]);
  require(step>0&&Math.abs(step-now/STEP)<=2,"QR vencido. Muestra el código actual y revisa la hora automática.");long start=step*STEP;
  require(now>=start-15000&&now<start+STEP+15000&&now>=b.getLong("i")-15000&&now<b.getLong("e"),"QR vencido. Muestra el código actual.");
  require(verify(b.getString("k"),"qr|"+cert+"|"+step,a[3]),"QR alterado o de otro celular.");return b;
 }
 public static JSONObject historyRequest(String cert,PrivateKey key,long now)throws Exception {String n=nonce();return new JSONObject().put("cert",cert).put("time",now).put("nonce",n).put("signature",sign(key,"history|"+hash(cert)+"|"+now+"|"+n));}
 public static JSONObject verifyHistory(JSONObject request,long now)throws Exception {String cert=request.getString("cert");JSONObject b=certificate(cert);String n=request.getString("nonce");long t=request.getLong("time");require(n.matches("[a-f0-9]{32}")&&Math.abs(now-t)<=90000,"Solicitud vencida.");require(verify(b.getString("k"),"history|"+hash(cert)+"|"+t+"|"+n,request.getString("signature")),"Acceso denegado.");return b;}
 public static boolean productionEndpoint(String url){return url.equals("https://comedor.colbert2027.shop")||url.equals("https://frigor.colbert2027.shop");}
}
