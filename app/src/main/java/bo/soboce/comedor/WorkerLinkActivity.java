package bo.soboce.comedor;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import bo.colbert.credential.WalletProtocol;
import bo.colbert.licensing.LicenseClient;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Operator-controlled enrollment; QR food cards are never accepted as enrollment credentials. */
public final class WorkerLinkActivity extends Activity {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();private String company,personId,pairing="";private PeerConfig config;private PeerDatabase db;private WalletRegistry registry;private LicenseClient license;private JSONObject person,current;private boolean busy;private LinearLayout page;private TextView status;private String activation="";
 @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
  try{company=getIntent().getStringExtra("company");if(company==null)company=Companies.current(this);personId=getIntent().getStringExtra("personId");config=new PeerConfig(new Vault(this,company).get("peerConfig"));PeerConfig.require(Companies.company(this,config).equals(company),"Empresa incompatible.");db=new PeerDatabase(this,config);registry=new WalletRegistry(db,config);license=Companies.license(this,company);person=registry.person(personId);registry.authority(person);render();}
  catch(Exception e){error(e.getMessage());}
 }
 private void render(){ScrollView scroll=new ScrollView(this);page=Design.column(this);page.setPadding(22,24,22,28);page.setBackgroundColor(Design.BG);scroll.addView(page);setContentView(scroll);scroll.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(0,in.getSystemWindowInsetTop(),0,in.getSystemWindowInsetBottom());return in;});
  label(company.toUpperCase(Locale.ROOT)+" / APP OPCIONAL",12);label("Vincular celular",28);label(person.optString("nombre"),21);label(PersonIdentity.label(person)+" · "+person.optString("planta"),15);
  label("Comprueba el carnet original y que la persona presente sea su titular. Esta operación conserva el trabajador y sus consumos.",15);
  status=label("Consulta la vinculación con internet antes de activar o reemplazar un celular.",14);
  button("Consultar celular vinculado",this::lookup);
  button("Escanear Activar mi credencial",()->{if(!allowed())return;new IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setPrompt("Escanea el QR de activación de la app del obrero").setBeepEnabled(false).setOrientationLocked(true).initiateScan();});
  button("Revocar celular",this::revoke);
  label("Al reemplazar o revocar, los otros encargados conocerán el cambio al sincronizar. La tarjeta física sigue siendo una alternativa. La app del obrero no recibe la base de datos ni permisos del encargado.",14);
  button("Volver",this::finish);
 }
 private TextView label(String s,int size){TextView t=Design.text(this,s,size,Design.INK,size>=20);t.setPadding(0,12,0,12);page.addView(t);return t;}
 private void button(String text,Runnable action){Button b=Design.button(this,text,Design.BLUE,Color.WHITE,()->{if(!busy)action.run();});page.addView(b,new LinearLayout.LayoutParams(-1,-2));}
 private void error(String text){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("Vinculación del obrero").setMessage(text==null?"No se pudo completar. Vuelve a consultar antes de reintentar.":text).setPositiveButton("Entendido",(d,w)->{if(person==null)finish();}).show();}
 private boolean allowed(){if(license!=null&&license.allowed())return true;error("Renueva el permiso de esta empresa en Licencia y pagos.");return false;}
 interface Task{String run()throws Exception;}
 private void task(Task task,java.util.function.Consumer<String> done){busy=true;status.setText("Comprobando con el coordinador…");worker.execute(()->{String value="",problem=null;try{value=task.run();}catch(Exception e){problem=e instanceof java.io.IOException?"No se confirmó la operación por internet. Consulta la vinculación y vuelve a intentar.":e.getMessage();}String result=value,failure=problem;runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed())return;if(failure!=null){status.setText("Sin confirmación. No se cambió el usuario ni su historial.");error(failure);}else done.accept(result);});});}
 private String description(){return current==null?"Sin celular vinculado.":(current.optBoolean("active")?"Celular activo":"Celular revocado")+" · revisión "+current.optLong("version")+"\nAutorizado por "+current.optString("by")+" · "+MealLedger.day(current.optLong("time"))+" "+MealLedger.time(current.optLong("time"));}
 private void lookup(){task(()->{current=registry.lookup(personId);return "";},x->status.setText(description()));}
 private void confirmBind(){if(!allowed())return;task(()->{WalletProtocol.pairing(pairing,System.currentTimeMillis());current=registry.lookup(personId);return "";},x->{status.setText(description());long expected=current==null?0:current.optLong("version");new AlertDialog.Builder(this).setTitle(current!=null&&current.optBoolean("active")?"Reemplazar celular":"Activar celular").setMessage(person.optString("nombre")+"\n"+PersonIdentity.label(person)+"\n\nConfirma que verificaste el carnet en persona. El celular anterior, si existe, se revoca al sincronizar.").setNegativeButton("Cancelar",null).setPositiveButton("Identidad verificada · activar",(d,w)->task(()->registry.bind(personId,pairing,expected,license,System.currentTimeMillis()),this::showActivation)).show();});}
 private void showActivation(String cert){activation=cert;status.setText("Vinculación autorizada. El obrero debe escanear este código desde su app.");try{ImageView image=new ImageView(this);BitMatrix m=new MultiFormatWriter().encode(cert,BarcodeFormat.QR_CODE,900,900,Collections.singletonMap(EncodeHintType.MARGIN,4));Bitmap bitmap=Bitmap.createBitmap(900,900,Bitmap.Config.RGB_565);for(int y=0;y<900;y++)for(int x=0;x<900;x++)bitmap.setPixel(x,y,m.get(x,y)?Color.BLACK:Color.WHITE);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);new AlertDialog.Builder(this).setTitle("Activar la credencial del obrero").setMessage("En su celular: Escanear autorización del encargado. Este código solo corresponde al celular que acabas de vincular.").setView(image).setPositiveButton("Listo",null).show();}catch(Exception e){error("La vinculación está guardada. Escanea de nuevo la misma solicitud para recuperar la autorización.");}}
 private void revoke(){if(!allowed())return;task(()->{current=registry.lookup(personId);return "";},x->{status.setText(description());if(current==null||!current.optBoolean("active")){error("No hay un celular activo para revocar.");return;}long version=current.optLong("version");new AlertDialog.Builder(this).setTitle("Revocar celular").setMessage("Se desactiva la credencial digital de "+person.optString("nombre")+". Los encargados sin conexión lo aplicarán cuando sincronicen. Se conservan sus consumos y su tarjeta física.").setNegativeButton("Cancelar",null).setPositiveButton("Revocar",(d,w)->task(()->{registry.revoke(personId,version,PeerConfig.random(),license);current=registry.lookup(personId);return "";},r->status.setText(description()))).show();});}
 @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);IntentResult scan=IntentIntegrator.parseActivityResult(request,result,data);if(scan!=null&&scan.getContents()!=null){pairing=scan.getContents();confirmBind();}}
 @Override protected void onDestroy(){if(db!=null)worker.execute(db::close);worker.shutdown();super.onDestroy();}
}
