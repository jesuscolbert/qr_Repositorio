package bo.colbert.obrero;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.security.keystore.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import bo.colbert.credential.WalletProtocol;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;

@RunWith(AndroidJUnit4.class)
public class WorkerTest {
 final Instrumentation i=InstrumentationRegistry.getInstrumentation();final Context context=i.getTargetContext();
 static void field(Object target,String name,Object value)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
 static void call(Object target,String name)throws Exception {Method m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(target);}
 static boolean text(View view,String value){if(view instanceof TextView&&((TextView)view).getText().toString().contains(value))return true;if(view instanceof ViewGroup)for(int n=0;n<((ViewGroup)view).getChildCount();n++)if(text(((ViewGroup)view).getChildAt(n),value))return true;return false;}
 String shell(String command)throws Exception {try(ParcelFileDescriptor fd=i.getUiAutomation().executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
 void shot(String name)throws Exception {i.waitForIdleSync();Thread.sleep(200);shell("mkdir -p /sdcard/Download/worker-design");shell("screencap -p /sdcard/Download/worker-design/"+name+".png");}
 void readableQr()throws Exception {android.graphics.Bitmap shot=i.getUiAutomation().takeScreenshot();assertNotNull(shot);try{int w=shot.getWidth(),h=shot.getHeight();int[] pixels=new int[w*h];shot.getPixels(pixels,0,w,0,0,w,h);String value=new com.google.zxing.qrcode.QRCodeReader().decode(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(w,h,pixels)))).getText();assertTrue("The entire rotating QR must be readable without scrolling",value.startsWith("CWQ1:"));}finally{shot.recycle();}}
 @Test public void personalScreensShowOnlyTheCredentialAndCachedHistoryWithSecureWindow()throws Exception {
  WorkerStore store=new WorkerStore(context);store.reset();KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));KeyPair key=g.generateKeyPair();long now=System.currentTimeMillis();
  JSONObject claims=new JSONObject().put("v",1).put("c","a".repeat(64)).put("p","CI-00010001").put("k",WalletProtocol.publicText(key.getPublic())).put("r",1).put("tag","b".repeat(64)).put("a","d".repeat(32)).put("i",now).put("e",now+WalletProtocol.LIFETIME).put("name","María · demostración").put("document","CI 00010001").put("plant","Planta de prueba").put("url","https://comedor.colbert2027.shop").put("by","Encargado de prueba");
  String cert=WalletProtocol.CERT+WalletProtocol.b64(claims.toString().getBytes(StandardCharsets.UTF_8))+"."+"0".repeat(64);
  store.put("certificate",cert);store.put("history",new JSONObject().put("serverTime",now).put("items",new JSONArray().put(new JSONObject().put("date","2026-11-12").put("time","12:05:00").put("meal","almuerzo").put("plant","Planta de prueba").put("review",""))).toString());
  MainActivity activity=(MainActivity)i.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try{i.waitForIdleSync();i.runOnMainSync(()->{try{assertTrue(text(activity.getWindow().getDecorView(),"Desbloquear con el teléfono"));assertTrue((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);field(activity,"identity",key);field(activity,"certificate",cert);field(activity,"unlockedUntil",SystemClock.elapsedRealtime()+120000);field(activity,"lastAutoAttempt",SystemClock.elapsedRealtime());call(activity,"home");activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);}catch(Exception e){throw new AssertionError(e);}});shot("01-mi-qr");readableQr();assertTrue(text(activity.getWindow().getDecorView(),"CI 00010001"));i.runOnMainSync(()->{try{call(activity,"history");}catch(Exception e){throw new AssertionError(e);}});shot("02-mis-consumos");assertTrue(text(activity.getWindow().getDecorView(),"ALMUERZO"));assertTrue(text(activity.getWindow().getDecorView(),"Última actualización"));i.runOnMainSync(()->{try{call(activity,"locked");}catch(Exception e){throw new AssertionError(e);}});assertFalse(text(activity.getWindow().getDecorView(),"CI 00010001"));}finally{i.runOnMainSync(activity::finish);store.reset();}
 }
 @Test public void storedDataIsEncryptedAndSigningKeyRequiresDeviceAuthentication()throws Exception {
  WorkerStore store=new WorkerStore(context);store.reset();store.put("certificate","test-private-credential");assertEquals("test-private-credential",store.get("certificate"));assertFalse(store.prefs.getAll().toString().contains("test-private-credential"));try{store.identity();fail("A restored certificate must not silently create a new signing identity");}catch(GeneralSecurityException expected){}store.reset();
  shell("locksettings set-pin 2580");try{assertTrue(((KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE)).isDeviceSecure());KeyPair pair=store.identity();assertNull(pair.getPrivate().getEncoded());KeyInfo info=KeyFactory.getInstance("EC","AndroidKeyStore").getKeySpec(pair.getPrivate(),KeyInfo.class);assertTrue(info.isUserAuthenticationRequired());assertEquals(300,info.getUserAuthenticationValidityDurationSeconds());}finally{store.reset();shell("locksettings clear --old 2580");}
 }
 @Test public void activationIsAtomicSingleIdentityAndResetRequiresNewEnrollment()throws Exception {
  WorkerStore store=new WorkerStore(context);store.reset();store.put("history","previous person's meals");store.put("pairNonce","pending");store.put("pairTime","123");store.activate("first-identity");assertTrue(store.enrolled());assertEquals("first-identity",store.get("certificate"));assertEquals("",store.get("history"));assertEquals("",store.get("pairNonce"));assertEquals("",store.get("pairTime"));
  try{store.activate("other-identity");fail("Second identity accepted");}catch(IllegalArgumentException expected){}assertEquals("first-identity",new WorkerStore(context).get("certificate"));store.reset();assertFalse(new WorkerStore(context).enrolled());store.activate("authorized-replacement");assertEquals("authorized-replacement",store.get("certificate"));store.reset();
 }
}
