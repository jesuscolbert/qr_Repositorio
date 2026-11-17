package bo.soboce.comedor;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** One app for master, administrators and operators; accounts are not worker identities. */
public final class StaffActivity extends Activity {
 private final ExecutorService executor=Executors.newSingleThreadExecutor();
 private PeerConfig config;private StaffAccess auth;private String company,scanMode="",replaceId="";private LinearLayout page;private TextView state;private boolean busy;private JSONObject editing;private android.os.CancellationSignal biometric;
 @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);try{
  company=getIntent().getStringExtra("company");if(company==null)company=Companies.current(this);config=new PeerConfig(new Vault(this,company).get("peerConfig"));auth=StaffAccess.attach(this,config);PeerConfig.require(auth!=null,"Esta instalación no tiene configuradas las cuentas.");
  if(auth.ready()){if(getIntent().getBooleanExtra("manage",false))dashboard();else complete();}else login();
 }catch(Exception e){screen("Acceso del personal","Se conservan los trabajadores y las entregas.");error(e.getMessage());button("Volver",this::finish);}}
 private void reload()throws Exception {auth=new StaffAccess(this,config);}
 private void screen(String title,String subtitle){ScrollView s=new ScrollView(this);s.setBackgroundColor(Design.BG);page=Design.column(this);page.setPadding(24,30,24,30);s.addView(page);setContentView(s);s.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(0,in.getSystemWindowInsetTop(),0,in.getSystemWindowInsetBottom());return in;});label((company==null?"COLBERT":company.toUpperCase(Locale.ROOT))+" / ACCESO PERSONAL",12,Design.BLUE);label(title,28,Design.INK);label(subtitle,15,Design.MUTED);state=label("",14,Design.RED);}
 private TextView label(String text,int size,int color){TextView v=Design.text(this,text,size,color,size>=20);v.setPadding(0,10,0,10);page.addView(v);return v;}
 private EditText field(String hint,String text,boolean pin){EditText v=new EditText(this);v.setHint(hint);v.setText(text);v.setSingleLine(true);if(pin){v.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);v.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});}page.addView(v,new LinearLayout.LayoutParams(-1,Design.dp(this,58)));return v;}
 private void button(String title,Runnable run){Button b=Design.button(this,title,Design.BLUE,Color.WHITE,()->{if(!busy)run.run();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=10;page.addView(b,p);}
 private void error(String message){if(state!=null)state.setText(message==null?"No se pudo completar. Vuelve a intentarlo.":message);}
 interface Work {JSONObject run()throws Exception;}
 private void task(Work work,java.util.function.Consumer<JSONObject> done){if(busy)return;busy=true;state.setText("Comprobando autorización…");executor.execute(()->{JSONObject reply=null;String problem=null;try{reply=work.run();}catch(Exception e){problem=e instanceof java.io.IOException?"No se confirmó la conexión. Tus datos y permisos guardados se conservan.":e.getMessage();}JSONObject r=reply;String p=problem;runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed())return;if(p!=null)error(p);else{state.setText("");done.accept(r);}});});}
 private void login(){try{
  JSONArray profiles=auth.profiles();if(profiles.length()==0){createProfile();return;}
  screen("Tu cuenta","Cada encargado usa su propio PIN. El teléfono puede seguir sincronizando mientras la pantalla está bloqueada.");
  for(int i=0;i<profiles.length();i++){JSONObject p=profiles.getJSONObject(i);String id=p.getString("profile");button(p.getString("name"),()->{try{auth.select(id);reload();pin();}catch(Exception e){error(e.getMessage());}});}
  button("Agregar otro perfil en este teléfono",this::createProfile);button("Volver",this::finish);
 }catch(Exception e){error(e.getMessage());}}
 private void createProfile(){screen("Preparar mi acceso","Este perfil todavía necesita autorización del usuario maestro. No crea un trabajador ni cambia registros existentes.");EditText name=field("Tu nombre completo","",false),pin=field("PIN personal de 6 números","",true),again=field("Repite tu PIN","",true);
  button("Crear perfil personal",()->task(()->{PeerConfig.require(pin.getText().toString().equals(again.getText().toString()),"Los PIN no coinciden.");auth.create(name.getText().toString(),pin.getText().toString());reload();return new JSONObject();},r->pending()));button("Volver",this::finish);
 }
 private void pin(){try{screen("Hola, "+auth.local().getString("name"),"Desbloquea tu perfil para comenzar el turno.");EditText input=field("PIN de 6 números","",true);
  button("Entrar",()->task(()->{auth.unlock(input.getText().toString());try{auth.refresh(true);}catch(java.io.IOException ignored){/* A valid cached account and paid lease remain usable offline. */}return new JSONObject();},r->{if(auth.authorized()){if(getIntent().getBooleanExtra("manage",false))dashboard();else complete();}else pending();}));
  if(android.os.Build.VERSION.SDK_INT>=28&&auth.profiles().length()==1&&auth.global.get("staffBiometric:"+auth.profile).equals("yes"))button("Usar huella o biometría del teléfono",this::biometric);
  button("Cambiar de perfil",this::login);button("Olvidé mi PIN",()->{screen("Recuperar acceso","El PIN no se envía a otros teléfonos. Crea un perfil nuevo y pide al maestro reemplazar tu acceso. Si eres el maestro, utiliza tu código privado de recuperación. Los consumos permanecen guardados.");button("Crear perfil nuevo",this::createProfile);button("Volver",this::login);});
 }catch(Exception e){error(e.getMessage());}}
 @android.annotation.TargetApi(28) private void biometric(){biometric=new android.os.CancellationSignal();new android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle("Desbloquear cuenta personal").setSubtitle("Usa el PIN si este teléfono es compartido").setNegativeButton("Usar PIN",getMainExecutor(),(d,w)->{}).build().authenticate(biometric,getMainExecutor(),new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback(){@Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result){StaffAccess.unlocked=auth.profile;task(()->{try{auth.refresh(true);}catch(java.io.IOException ignored){}return new JSONObject();},r->{if(auth.authorized())complete();else pending();});}@Override public void onAuthenticationError(int code,CharSequence text){error(text.toString());}});}
 private void pending(){try{
  screen("Autorizar este teléfono",auth.local().getString("name")+" · Los trabajadores, QR y consumos que ya tenías siguen guardados.");
  if(!auth.certificate().isEmpty()&&!auth.authorized())label("Cuenta desactivada o equipo reemplazado. Solo el maestro puede cambiar ese permiso.",15,Design.RED);
  button("Mostrar solicitud al maestro",()->task(()->auth.remote("request",new JSONObject().put("name",auth.local().getString("name")).put("device",config.device)),r->{try{if(r.optBoolean("alreadyKnown")){error("Esta cuenta ya existe. El maestro puede reactivarla o reemplazarla desde su lista.");return;}String qr=StaffProtocol.REQUEST+new JSONObject().put("cluster",config.cluster).put("request",r.getString("id")).put("expires",r.getLong("expires")).toString();screen("Solicitud de acceso","El maestro debe escanear este QR en Encargados y permisos. Dura diez minutos y solo autoriza este perfil y teléfono.");showQr(qr);button("Ya me autorizaron · comprobar",this::check);button("Volver",this::pending);}catch(Exception e){error(e.getMessage());}}));
  button("Ya me autorizaron · comprobar",this::check);button("Activar o recuperar usuario maestro",()->scan("master"));button("Cambiar de perfil",this::login);button("Cambiar empresa",()->{try{PeerConfig.require(new Vault(this,company).get("pending").isEmpty(),"Hay una entrega pendiente. Conserva esta empresa hasta comprobarla.");stopService(new Intent(this,PeerService.class));new Handler(getMainLooper()).postDelayed(()->{try{Companies.select(this,company.equals("soboce")?"frigor":"soboce");startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();}catch(Exception e){error(e.getMessage());}},800);}catch(Exception e){error(e.getMessage());}});
 }catch(Exception e){error(e.getMessage());}}
 private void check(){task(()->auth.remote("status",new JSONObject()),r->{if(auth.authorized())complete();else error("El usuario maestro aún no habilitó este perfil para "+company.toUpperCase(Locale.ROOT)+".");});}
 private void complete(){setResult(RESULT_OK);finish();}
 private void dashboard(){try{
  JSONObject g=auth.grant();screen("Mi cuenta",g.getString("name")+" · "+role(g.getString("role")));label("Plantas: "+g.getJSONArray("plants").join(", ").replace("\"",""),15,Design.INK);
  if(auth.master()){button("Encargados y permisos",this::list);button("Autorizar teléfono · escanear QR",()->{editing=null;scan("grant");});}
  if(android.os.Build.VERSION.SDK_INT>=28&&auth.profiles().length()==1){CheckBox personal=new CheckBox(this);personal.setText("Es mi teléfono personal: permitir biometría");personal.setChecked(auth.global.get("staffBiometric:"+auth.profile).equals("yes"));page.addView(personal);personal.setOnCheckedChangeListener((b,on)->{try{auth.require("people","");auth.global.put("staffBiometric:"+auth.profile,on?"yes":"no");}catch(Exception e){error(e.getMessage());}});label("La biometría acepta las huellas o rostros registrados en Android. En teléfonos compartidos utiliza un PIN distinto por encargado.",13,Design.MUTED);}
  button("Actualizar mis permisos",()->task(()->auth.remote("status",new JSONObject()),r->{if(auth.authorized())dashboard();else pending();}));button("Bloquear y cambiar encargado",()->{StaffAccess.lock();login();});button("Volver al comedor",this::complete);
 }catch(Exception e){pending();}}
 static String role(String r){return r.equals("master")?"Usuario maestro":r.equals("admin")?"Administrador":"Encargado";}
 private void list(){task(()->{PeerConfig.require(auth.master(),"Solo el maestro administra cuentas.");return auth.remote("list",new JSONObject());},r->{try{
  screen("Encargados y permisos","Solo el usuario maestro crea, modifica y desactiva cuentas. Desactivar conserva las entregas anteriores.");button("Crear administrador o encargado",()->{editing=null;scan("grant");});
  JSONArray all=auth.users();for(int i=0;i<all.length();i++){JSONObject u=all.getJSONObject(i);if(u.getString("role").equals("master")){label(u.getString("name")+" · Maestro",17,Design.INK);continue;}button(u.getString("name")+" · "+role(u.getString("role"))+(u.optBoolean("active")?"":" · Desactivado"),()->form(u,null));}
  button("Volver",this::dashboard);
 }catch(Exception e){error(e.getMessage());}});}
 private void form(JSONObject existing,JSONObject request){try{
  PeerConfig.require(auth.master(),"Solo el maestro administra cuentas.");editing=existing;replaceId=request==null?"":request.getString("request");
  screen(existing==null?"Crear cuenta":"Editar cuenta","El permiso se limita a esta empresa. Para SOBOCE y FRIGOR se autoriza el mismo perfil en cada empresa.");
  EditText name=field("Nombre del encargado",existing==null?request.optString("name",""):existing.getString("name"),false);
  Spinner role=new Spinner(this);role.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Encargado","Administrador"}));role.setSelection(existing!=null&&existing.getString("role").equals("admin")?1:0);page.addView(role);
  label("PLANTAS AUTORIZADAS",13,Design.BLUE);List<CheckBox> plants=new ArrayList<>();JSONArray all=config.seed.getJSONArray("plants");for(int i=0;i<all.length();i++){CheckBox c=new CheckBox(this);c.setText(all.getString(i));c.setChecked(existing!=null?StaffProtocol.contains(existing.getJSONArray("plants"),all.getString(i)):all.length()==1);plants.add(c);page.addView(c);}
  label("El encargado siempre puede escanear, vincular obreros y consultar sus plantas. Permisos adicionales:",14,Design.MUTED);List<CheckBox> extras=new ArrayList<>();String[] keys={"issue","worker","visitor"},titles={"Emitir e imprimir QR físicos","Crear nuevos trabajadores","Autorizar visitantes"};for(int i=0;i<keys.length;i++){CheckBox c=new CheckBox(this);c.setText(titles[i]);c.setTag(keys[i]);c.setChecked(existing!=null&&StaffProtocol.contains(existing.getJSONArray("permissions"),keys[i]));extras.add(c);page.addView(c);}
  CheckBox active=new CheckBox(this);active.setText("Cuenta habilitada");active.setChecked(existing==null||existing.getBoolean("active"));page.addView(active);
  if(!replaceId.isEmpty()&&existing!=null)label("Se reemplazará el teléfono anterior cuando guardes.",15,Design.RED);
  button("Guardar autorización",()->task(()->{PeerConfig.require(auth.master(),"Solo el maestro administra cuentas.");if(active.isChecked())QrPrintData.requirePermission(Companies.license(this,company));JSONArray ps=new JSONArray(),perms=new JSONArray();for(CheckBox c:plants)if(c.isChecked())ps.put(c.getText().toString());for(CheckBox c:extras)if(c.isChecked())perms.put(c.getTag().toString());JSONObject body=new JSONObject().put("name",name.getText().toString()).put("role",role.getSelectedItemPosition()==1?"admin":"encargado").put("plants",ps).put("permissions",perms).put("active",active.isChecked());
    if(existing!=null)body.put("id",existing.getString("id")).put("expected",existing.getLong("revision"));if(!replaceId.isEmpty())body.put("request",replaceId);return auth.remote(existing==null?"grant":"update",body);
   },r->{screen("Autorización guardada","El teléfono autorizado debe tocar Comprobar. Los equipos desconectados aplicarán los cambios al sincronizar.");button("Volver a las cuentas",this::list);}));
  if(existing!=null)button("Reemplazar teléfono o recuperar PIN",()->{editing=existing;scan("grant");});button("Cancelar",this::list);
 }catch(Exception e){error(e.getMessage());}}
 private void scan(String mode){try{PeerConfig.require(StaffAccess.session(auth.profile),"Desbloquea tu perfil.");if(mode.equals("grant"))PeerConfig.require(auth.master(),"Solo el maestro autoriza cuentas.");scanMode=mode;new IntentIntegrator(this).setCaptureActivity(bo.colbert.credential.CredentialScanActivity.class).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setPrompt(mode.equals("master")?"Escanea tu activación o recuperación privada":"Escanea la solicitud del nuevo encargado").setBeepEnabled(false).setOrientationLocked(true).initiateScan();}catch(Exception e){error(e.getMessage());}}
 @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);IntentResult scan=IntentIntegrator.parseActivityResult(request,result,data);if(scan==null||scan.getContents()==null)return;try{
  String qr=scan.getContents();PeerConfig.require(qr.length()<16000,"Código demasiado grande.");
  if(scanMode.equals("master")){PeerConfig.require(qr.startsWith(StaffProtocol.MASTER),"Este no es el código privado del maestro.");JSONObject b=new JSONObject(qr.substring(StaffProtocol.MASTER.length()));JSONArray companies=b.getJSONArray("companies");String code="";for(int i=0;i<companies.length();i++)if(companies.getJSONObject(i).getString("cluster").equals(config.cluster))code=companies.getJSONObject(i).getString("code");PeerConfig.require(!code.isEmpty(),"El código pertenece a otra empresa.");String secret=code,action=b.optString("kind").equals("recovery")?"recover":"claim";
   task(()->auth.remote(action,new JSONObject().put("code",secret).put("name",auth.local().getString("name")).put("device",config.device)),r->complete());
  }else{PeerConfig.require(qr.startsWith(StaffProtocol.REQUEST),"Escanea la solicitud del encargado, no el QR de un trabajador.");JSONObject r=new JSONObject(qr.substring(StaffProtocol.REQUEST.length()));PeerConfig.require(r.getString("cluster").equals(config.cluster)&&r.getLong("expires")>=System.currentTimeMillis(),"Solicitud vencida o de otra empresa.");JSONObject target=editing;
   task(()->auth.remote("list",new JSONObject()),reply->{try{JSONArray pending=reply.getJSONArray("pending");JSONObject found=null;for(int i=0;i<pending.length();i++)if(pending.getJSONObject(i).getString("id").equals(r.getString("request")))found=pending.getJSONObject(i);PeerConfig.require(found!=null,"La solicitud ya se utilizó o venció.");r.put("name",found.getString("name"));form(target,r);}catch(Exception e){error(e.getMessage());}});
  }
 }catch(Exception e){error(e.getMessage());}}
 private void showQr(String value)throws Exception {BitMatrix m=new MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,800,800);Bitmap bitmap=Bitmap.createBitmap(800,800,Bitmap.Config.RGB_565);for(int y=0;y<800;y++)for(int x=0;x<800;x++)bitmap.setPixel(x,y,m.get(x,y)?Color.BLACK:Color.WHITE);ImageView v=new ImageView(this);v.setImageBitmap(bitmap);v.setAdjustViewBounds(true);page.addView(v,new LinearLayout.LayoutParams(-1,600));}
 @Override protected void onDestroy(){if(biometric!=null)biometric.cancel();executor.shutdown();super.onDestroy();}
}
