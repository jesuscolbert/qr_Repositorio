package bo.colbert.cobros;
import android.app.*;
import android.content.*;
import android.os.ParcelFileDescriptor;
import android.view.WindowManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.lang.reflect.Field;
import java.io.*;
import java.security.*;
import java.security.spec.*;
import bo.colbert.licensing.*;

@RunWith(AndroidJUnit4.class)
public class BillingUiTest {
 @Test public void ownerDashboardAndCustomerPaymentAreSeparate()throws Exception{
  Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context ctx=ins.getTargetContext();BillingRuntime runtime=BillingRuntime.get(ctx);String token=LicenseProtocol.random();runtime.config=new JSONObject().put("url","https://licencias.example.test");runtime.db.create("ui-customer","Empresa de prueba","comedor-demo","Comedor de prueba",token,System.currentTimeMillis());runtime.db.plan("ui-customer",2000,"month","QR de pago del administrador · cuenta de prueba","",System.currentTimeMillis());runtime.db.charge("ui-customer",System.currentTimeMillis());
  BillingActivity owner=(BillingActivity)ins.startActivitySync(new Intent(ctx,BillingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  // Test-only instrumentation can enter synthetic data views. Production always uses device credential authentication.
  ins.runOnMainSync(()->{try{Field r=BillingActivity.class.getDeclaredField("runtime");r.setAccessible(true);r.set(owner,runtime);Field auth=BillingActivity.class.getDeclaredField("authorized");auth.setAccessible(true);auth.setBoolean(owner,true);owner.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);owner.home();}catch(Exception e){throw new RuntimeException(e);}});ins.waitForIdleSync();capture(ins,"01-administracion");
  ins.runOnMainSync(()->owner.contract("ui-customer"));ins.waitForIdleSync();capture(ins,"02-cliente");ins.runOnMainSync(owner::finish);
  SecretStore vault=new SecretStore(ctx);String before=vault.get("clientConfig"),permit=vault.get("permit"),pinned=vault.get("serverKey");Field singleton=LicenseClient.class.getDeclaredField("singleton");singleton.setAccessible(true);Object previous=singleton.get(null);
  try{
   JSONObject config=new JSONObject().put("version",1).put("url","https://127.0.0.1:1").put("token",token).put("contractId","ui-customer").put("productId","comedor-demo").put("productName","Comedor de prueba");vault.put("clientConfig",config.toString());KeyPairGenerator gen=KeyPairGenerator.getInstance("EC");gen.initialize(new ECGenParameterSpec("secp256r1"));KeyPair pair=gen.generateKeyPair();JSONObject env=LicenseProtocol.sign(runtime.db.status("ui-customer",LicenseProtocol.random(),System.currentTimeMillis()),pair.getPrivate(),pair.getPublic());vault.put("serverKey",env.getString("publicKey"));vault.put("permit",new JSONObject().put("envelope",env).put("elapsed",android.os.SystemClock.elapsedRealtime()).put("boot",android.provider.Settings.Global.getInt(ctx.getContentResolver(),"boot_count",-1)).toString());singleton.set(null,null);
   LicenseActivity customer=(LicenseActivity)ins.startActivitySync(new Intent(ctx,LicenseActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));ins.waitForIdleSync();capture(ins,"03-pago-cliente");ins.runOnMainSync(customer::finish);
  }finally{vault.put("clientConfig",before);vault.put("permit",permit);vault.put("serverKey",pinned);singleton.set(null,previous);}
 }
 static void capture(Instrumentation ins,String name)throws Exception{shell(ins,"mkdir -p /sdcard/Download/colbert-billing-design");shell(ins,"screencap -p /sdcard/Download/colbert-billing-design/"+name+".png");}
 static void shell(Instrumentation ins,String command)throws Exception{try(ParcelFileDescriptor fd=ins.getUiAutomation().executeShellCommand(command);InputStream in=new FileInputStream(fd.getFileDescriptor())){byte[] b=new byte[4096];while(in.read(b)!=-1){}}}
}
