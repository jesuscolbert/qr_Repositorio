package bo.soboce.comedor;

import android.content.Intent;
import android.widget.CheckBox;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class StaffBiometricTest {
 StaffPasswordTest password;StaffTest f;
 @Before public void setup()throws Exception {password=new StaffPasswordTest();password.setup();f=password.f;}
 @After public void cleanup(){password.cleanup();}
 void migrate(String role)throws Exception {
  f.authorize(role,true,"Planta Norte");JSONObject g=f.auth.grant().put("username",password.user).put("passwordVersion",1).put("revision",++f.revision);
  f.auth.saveReply(new JSONObject().put("certificate",f.seal(g)));f.auth.rememberPassword(password.credentials);
 }
 StaffActivity open(boolean direct){return (StaffActivity)InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(f.fixture.context,StaffActivity.class).putExtra("company","soboce").putExtra("manage",true).putExtra("biometric",direct).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
 void click(StaffActivity activity,String text){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->new PeerDatabaseTest().findText(activity.getWindow().getDecorView(),text).performClick());}
 void close(StaffActivity activity){InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);}
 @Test public void masterAndSupervisorAlwaysSeeBiometricSetupEvenBeforePasswordMigration()throws Exception {
  PeerDatabaseTest ui=new PeerDatabaseTest();
  for(String role:new String[]{"master","encargado"}){f.authorize(role,true,"Planta Norte");StaffActivity activity=open(false);try{
   ui.waitText(activity,"Configurar acceso con huella");click(activity,"Configurar acceso con huella");ui.waitText(activity,"Acceso con huella");ui.waitText(activity,"Es mi celular personal: entrar con huella");
   ui.waitText(activity,f.auth.biometricSetupProblem());InstrumentationRegistry.getInstrumentation().runOnMainSync(()->assertFalse(ui.findText(activity.getWindow().getDecorView(),"Es mi celular personal: entrar con huella").isEnabled()));
   ui.screenshot(role.equals("master")?"34-huella-maestro-pendiente":"35-huella-encargado-pendiente");
  }finally{close(activity);}}
 }
 @Test public void multipleProfilesExplainWhyBiometricIsUnavailableWithoutDeletingAnything()throws Exception {
  migrate("master");String selected=f.auth.profile;f.auth.create("Perfil de prueba","123456");f.auth.select(selected);f.auth=new StaffAccess(f.fixture.context,f.config);StaffAccess.unlocked=selected;
  String before=f.auth.global.read("staffProfiles");assertTrue(f.auth.biometricSetupProblem().startsWith("Hay 2 perfiles"));StaffActivity activity=open(true);PeerDatabaseTest ui=new PeerDatabaseTest();
  try{ui.waitText(activity,"Acceso con huella");ui.waitText(activity,f.auth.biometricSetupProblem());ui.screenshot("36-huella-varios-perfiles");}finally{close(activity);}
  assertEquals(before,f.auth.global.read("staffProfiles"));assertEquals(1,new OfflineLedger(f.db,f.config).records().size());WalletTest.rejected(()->f.auth.enableBiometric());
 }
 @Test public void deviceDiagnosticAndEnrollmentActionRemainVisibleOnLoginWithoutGrantingAccess()throws Exception {
  migrate("encargado");StaffAccess.lock();StaffActivity activity=open(false);PeerDatabaseTest ui=new PeerDatabaseTest();try{
   ui.waitText(activity,"Iniciar sesión");click(activity,"Configurar acceso con huella");ui.waitText(activity,"Acceso con huella");ui.waitText(activity,"Abrir ajustes de huella");ui.waitText(activity,"Entrar con usuario y contraseña");
   String problem=StaffBiometric.problem(StaffBiometric.status(activity));if(!problem.isEmpty())ui.waitText(activity,problem);
   assertFalse(f.auth.ready());assertFalse(f.auth.biometricAllowed());InstrumentationRegistry.getInstrumentation().runOnMainSync(()->assertFalse(ui.findText(activity.getWindow().getDecorView(),"Es mi celular personal: entrar con huella").isEnabled()));ui.screenshot("37-huella-desde-login");
  }finally{close(activity);}
 }
 @Test public void enablingRequiresAuthenticatedCurrentPasswordAndSingleProfileAndKeepsBilling()throws Exception {
  f.authorize("master",true,"Planta Norte");WalletTest.rejected(()->f.auth.enableBiometric());migrate("master");assertEquals("",f.auth.biometricSetupProblem());
  StaffAccess.lock();WalletTest.rejected(()->f.auth.enableBiometric());StaffAccess.unlocked=f.auth.profile;f.auth.enableBiometric();assertTrue(f.auth.biometricAllowed());f.auth.disableBiometric();assertFalse(f.auth.biometricAllowed());
  f.auth.enableBiometric();JSONObject changed=f.auth.grant().put("passwordVersion",2).put("revision",++f.revision);f.auth.saveReply(new JSONObject().put("certificate",f.seal(changed)));assertFalse(f.auth.biometricAllowed());assertTrue(f.auth.biometricSetupProblem().contains("contraseña actual"));WalletTest.rejected(()->f.auth.enableBiometric());
  assertTrue(Companies.license(f.fixture.context,"soboce").allowed());assertEquals(1,new OfflineLedger(f.db,f.config).records().size());
 }
}
