package bo.soboce.comedor;

import android.app.*;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private Vault vault; LinearLayout page; private TextView status;
 private JSONObject session; private Spinner plantBox; private String plant="",meal="almuerzo"; private boolean busy=false;
 private final int navy=Design.INK, teal=Design.BLUE;
 ScanScreen scanner;private boolean resumeScan=false;
 boolean isBusy(){return busy;}
 interface Reply { void accept(JSONObject r) throws Exception; }
 @Override public void onCreate(Bundle b){super.onCreate(b);vault=new Vault(this);try{BundledSetup.install(this,vault);}catch(Exception e){screen("Instalación pendiente",e.getMessage()==null?"No se pudo aplicar la configuración incorporada.":e.getMessage());button("Reintentar",this::recreate);return;}if(vault.get("needsProfile").equals("yes")){profile();return;}if(b!=null){plant=b.getString("plant","");meal=b.getString("meal","almuerzo");resumeScan=b.getBoolean("scanning",false);}if(vault.get("mode").equals("p2p")||!vault.get("url").isEmpty())connect();else settings();}
 @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("plant",plant);b.putString("meal",meal);b.putBoolean("scanning",scanner!=null);}
 int dp(int v){return (int)(v*getResources().getDisplayMetrics().density);}
 void screen(String title,String sub){stopScanner();ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Design.INK);scroll.setFillViewport(true);page=Design.column(this);page.setPadding(dp(22),dp(20),dp(22),dp(28));page.setBackgroundColor(Design.BG);scroll.addView(page);setContentView(scroll);scroll.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(0,i.getSystemWindowInsetTop(),0,i.getSystemWindowInsetBottom());return i;});label("SOBOCE  /  COMEDOR",12,teal);TextView heading=label(title,30,navy);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));label(sub,14,Design.MUTED);status=label("",13,teal);}
 TextView label(String text,int size,int color){TextView t=Design.text(this,text,size,color,false);t.setPadding(0,dp(8),0,dp(8));page.addView(t);return t;}
 Button button(String text,Runnable action){Button b=Design.button(this,text,teal,Color.WHITE,()->{if(!busy)action.run();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(12),0,dp(2));page.addView(b,lp);return b;}
 EditText field(String hint,String value){EditText e=new EditText(this);e.setHint(hint);e.setText(value);e.setSingleLine(true);e.setTextSize(16);e.setTextColor(navy);e.setHintTextColor(Design.MUTED);e.setPadding(dp(15),dp(10),dp(15),dp(10));e.setBackground(Design.shape(this,Color.WHITE,12));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.setMargins(0,dp(6),0,dp(6));page.addView(e,lp);return e;}
 void error(String message){new AlertDialog.Builder(this).setTitle("Revisa la operación").setMessage(message).setPositiveButton("Entendido",null).show();}
 JSONObject obj(String... values){JSONObject o=new JSONObject();try{for(int i=0;i<values.length;i+=2)o.put(values[i],values[i+1]);}catch(Exception ignored){}return o;}
 void settings(){if(sharedMode()){profile();return;}screen("Conectar comedor","Elige la red de teléfonos o Google Sheets.");button("Importar archivo P2P",()->{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.setType("application/json");pick.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,717);});if(!vault.get("peerConfig").isEmpty())button("Usar red de teléfonos",()->{try{vault.put("mode","p2p");connect();}catch(Exception e){error("No se pudo activar la red.");}});label("P2P: registra comidas con un solo teléfono, incluso sin internet. Sincroniza con los demás al reconectarse. Cada equipo usa su propio archivo.",14,Color.DKGRAY);EditText url=field("URL de Google Apps Script",vault.get("url"));EditText token=field("Clave personal del encargado","");token.setInputType(129);button("Guardar y conectar",()->{String u=url.getText().toString().trim(),t=token.getText().toString().trim();if(!u.matches("https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]+/exec")||!t.matches("[a-f0-9]{64}")){error("Comprueba la URL y la clave de 64 caracteres.");return;}try{vault.put("url",u);vault.put("token",t);vault.put("mode","sheets");connect();}catch(Exception e){error("No se pudieron guardar las credenciales.");}});label("La clave se guarda cifrada en este teléfono. Cada encargado debe usar su propio acceso.",14,Color.DKGRAY);}
 void connect(){if(isFinishing()||isDestroyed())return;if(vault.get("mode").equals("p2p")){if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},718);startForegroundService(new Intent(this,PeerService.class));}screen("Conectando…","Comprobando acceso y plantas disponibles.");api(obj("action","session"),r->{session=r;if(resumeScan&&success(r)){resumeScan=false;openScanner();}else home();});button("Configurar conexión",this::settings);}
 void api(JSONObject request,Reply reply){
  if(busy||isFinishing()||isDestroyed())return;busy=true;LinearLayout requestedPage=page;status.setText("Consultando registro…");
  worker.execute(()->{JSONObject response;try{if(!vault.get("mode").equals("p2p"))request.put("token",vault.get("token"));response=post(request);}catch(Exception ex){response=obj("ok","false","error","No se pudo completar la consulta. Revisa la conexión e inténtalo nuevamente.");}JSONObject result=response;
   runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed()||page!=requestedPage)return;status.setText("");try{reply.accept(result);}catch(Exception e){error("No se pudo mostrar la respuesta. Vuelve a consultar.");}});
  });
 }
 JSONObject post(JSONObject req)throws Exception{if(vault.get("mode").equals("p2p")){PeerEngine current=PeerService.engine;if(current!=null)return current.request(req);PeerConfig config=new PeerConfig(vault.get("peerConfig"));try(PeerDatabase db=new PeerDatabase(this,config)){if(req.optString("action").equals("scan")){if(config.shared){if(!db.meta("remoteAttempt:"+req.optString("requestId"),"").isEmpty())return SharedNetwork.uncertain();return new SharedLedger(db,config).scan(req,config.operator(-1),System.currentTimeMillis());}return new OfflineLedger(db,config).scan(req,System.currentTimeMillis());}return PeerCards.request(db,config,req,System.currentTimeMillis());}}HttpURLConnection c=(HttpURLConnection)new URL(vault.get("url")).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(45000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=UTF-8");c.setInstanceFollowRedirects(false);try{try(OutputStream out=c.getOutputStream()){out.write(req.toString().getBytes(StandardCharsets.UTF_8));}int code=c.getResponseCode();if(code==301||code==302||code==303){URL target=new URL(c.getHeaderField("Location"));if(!target.getProtocol().equals("https")||!target.getHost().equals("script.googleusercontent.com"))throw new IOException("Redirect inválido");c.disconnect();c=(HttpURLConnection)target.openConnection();c.setConnectTimeout(15000);c.setReadTimeout(45000);}if(c.getResponseCode()!=200)throw new IOException("HTTP");String data;try(InputStream in=c.getInputStream()){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);data=new String(bytes.toByteArray(),StandardCharsets.UTF_8);}JSONObject r=new JSONObject(data);if(!r.optBoolean("ok")){return r;}return r;}finally{c.disconnect();}}
 boolean success(JSONObject r){if(r.has("error")){error(r.optString("error"));return false;}return true;}
 void home(){
  if(session==null){connect();return;}if(!success(session)){settings();return;}screen("Control de entregas",session.optString("operator")+" · "+MealLedger.day(System.currentTimeMillis()));
  JSONArray allowed=session.optJSONArray("plants");List<String> plants=new ArrayList<>();if(allowed!=null)for(int i=0;i<allowed.length();i++)plants.add(allowed.optString(i));if(plants.isEmpty()){error("Tu cuenta no tiene plantas activas.");return;}
  LinearLayout location=Design.card(this,Color.WHITE,12);location.addView(Design.text(this,"PLANTA DE ATENCIÓN",10,Design.MUTED,true));plantBox=new Spinner(this);plantBox.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,plants));location.addView(plantBox);page.addView(location);if(plants.contains(plant))plantBox.setSelection(plants.indexOf(plant));else plant=plants.get(0);plantBox.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long id){plant=plants.get(position);}public void onNothingSelected(AdapterView<?> p){}});
  Design.space(page,20);page.addView(Design.text(this,"¿Qué vas a entregar?",19,navy,true));Design.space(page,12);
  LinearLayout choices=Design.column(this);page.addView(choices);Runnable[] draw=new Runnable[1];draw[0]=()->{choices.removeAllViews();View[] cards=new View[4];int i=0;for(String m:Meals.ALL)cards[i++]=Design.mealCard(this,m,m.equals(meal),()->{meal=m;draw[0].run();});Design.grid(choices,cards);};draw[0].run();
  Button scan=button("INICIAR ESCANEO  →",this::openScanner);scan.setMinHeight(dp(60));
  if(!vault.get("pending").isEmpty()){label("Una entrega espera confirmación.",15,Design.RED);button("Comprobar pendiente",this::retry);}
  label("Escaneo continuo · resultado verde o alerta roja",12,Design.MUTED);Design.space(page,12);
  menuTile("Entregas de hoy","Consulta lo registrado en todas las plantas",this::today);menuTile("Reportes","Por día o mes · exporta a Excel",this::report);
  if(session.optBoolean("p2p")||session.optString("role").equals("admin"))menuTile("Credenciales QR","Crea y comparte tarjetas de trabajadores",this::people);menuTile("Visitantes","Autoriza una visita con vencimiento",this::visitor);
  if(session.optBoolean("p2p")){menuTile("Estado de la red","Sincronización entre teléfonos",this::network);menuTile("Revisar duplicados","Entregas que requieren revisión",this::conflicts);}
  else if(session.optString("role").equals("admin"))menuTile("Nuevo encargado","Crear un acceso para esta planta",this::operator);
  menuTile("Configuración","Encargado, planta y conexión",()->{if(!vault.get("pending").isEmpty())error("Comprueba la entrega pendiente antes de cambiar la configuración.");else settings();});
 }
 void menuTile(String title,String detail,Runnable action){LinearLayout tile=Design.card(this,Color.WHITE,17);TextView name=Design.text(this,title+"  ›",17,navy,true);tile.addView(name);Design.space(tile,5);tile.addView(Design.text(this,detail,12,Design.MUTED,false));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(10);page.addView(tile,lp);tile.setFocusable(true);tile.setOnClickListener(v->{if(!busy)action.run();});}
 void openScanner(){
  if(checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.CAMERA},719);return;}
  stopScanner();page=null;String currentPlant=plant,currentMeal=meal;
  try{if(!vault.get("pending").isEmpty()){JSONObject pending=new JSONObject(vault.get("pending"));currentPlant=pending.getString("plant");currentMeal=pending.getString("meal");}}catch(Exception e){error("No se pudo leer la entrega pendiente.");home();return;}
  scanner=new ScanScreen(this,currentPlant,currentMeal);scanner.resume();
 }
 void stopScanner(){if(scanner!=null){scanner.close();scanner=null;}}
 void submitScan(JSONObject request,Reply callback){worker.execute(()->{JSONObject result;try{if(!vault.get("mode").equals("p2p"))request.put("token",vault.get("token"));result=post(request);}catch(Exception e){result=obj("ok","false","error","No llegó la confirmación. Comprueba la operación pendiente antes de servir.");}JSONObject reply=result;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;try{callback.accept(reply);}catch(Exception e){error("Comprueba la entrega pendiente antes de continuar.");}});});}
 @Override protected void onResume(){super.onResume();if(scanner!=null)scanner.resume();}
 @Override protected void onPause(){if(scanner!=null)scanner.pause();super.onPause();}
 @Override protected void onDestroy(){stopScanner();worker.shutdown();super.onDestroy();}
 @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==719){if(grants.length>0&&grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)openScanner();else error("Permite el acceso a la cámara para leer los QR.");}}
 @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==717&&result==RESULT_OK&&data!=null)importPeer(data);}
 void retry(){openScanner();}
 boolean sharedMode(){try{return new JSONObject(vault.get("peerConfig")).optString("format").equals("SOBOCE-SHARED-1");}catch(Exception e){return false;}}
 void profile(){
  try{
   PeerConfig config=new PeerConfig(vault.get("peerConfig"));screen("Tu teléfono","La red se configura automáticamente. Indica quién registra y en qué planta.");
   EditText name=field("Nombre del encargado",config.operator(-1).getString("name"));List<String> plants=new ArrayList<>();JSONArray ps=config.seed.getJSONArray("plants");for(int i=0;i<ps.length();i++)plants.add(ps.getString(i));
   Spinner select=new Spinner(this);select.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,plants));select.setSelection(plants.indexOf(config.operator(-1).getString("plant")));page.addView(select);
   button("Guardar y entrar",()->{try{
    if(!vault.get("pending").isEmpty()){error("Comprueba primero la entrega pendiente.");return;}
    String n=name.getText().toString().trim();if(n.length()<3||n.length()>100){error("Escribe el nombre del encargado.");return;}
    JSONObject raw=new JSONObject(vault.get("peerConfig"));raw.getJSONObject("operator").put("name",n).put("plant",select.getSelectedItem().toString());new PeerConfig(raw.toString());
    stopService(new Intent(this,PeerService.class));vault.put("peerConfig",raw.toString());vault.remove("needsProfile");plant=select.getSelectedItem().toString();session=null;
    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::connect,600);
   }catch(Exception e){error("No se pudo guardar la configuración.");}});
   if(session!=null)button("Volver",this::home);
  }catch(Exception e){error("No se pudo leer la configuración de esta instalación.");}
 }
 void today(){new ReportsScreen(this,plant,sharedMode());}
 void people(){api(obj("action","people","plant",plant),r->{if(!success(r))return;screen("Credenciales",plant+(session.optBoolean("p2p")?" · Puedes crear y compartir tarjetas con este teléfono solamente.":" · Emitir un QR invalida el anterior."));EditText query=field("Buscar nombre o SAP","");LinearLayout results=new LinearLayout(this);results.setOrientation(LinearLayout.VERTICAL);page.addView(results);JSONArray people=r.getJSONArray("people");Runnable render=()->{results.removeAllViews();String term=query.getText().toString().toLowerCase(Locale.ROOT);for(int i=0;i<people.length();i++){JSONObject p=people.optJSONObject(i);if(!p.optString("tipo").equals("VISITA")&&(p.optString("nombre")+p.optString("sap")).toLowerCase(Locale.ROOT).contains(term)){Button b=new Button(this);b.setAllCaps(false);b.setText(p.optString("nombre")+" · "+p.optString("sap"));results.addView(b);b.setOnClickListener(v->{if(busy)return;new AlertDialog.Builder(this).setTitle("Emitir credencial").setMessage(p.optString("nombre")+(session.optBoolean("p2p")?"\nLas tarjetas anteriores siguen vigentes. Todas identifican a la misma persona; cada teléfono bloquea los consumos que ya conoce.":"\nEl QR anterior dejará de funcionar.")).setNegativeButton("Cancelar",null).setPositiveButton("Emitir",(d,w)->api(obj("action","issue","plant",plant,"personId",p.optString("id")),x->{if(success(x))card(x);})).show();});}}};query.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){render.run();}public void afterTextChanged(android.text.Editable s){}});render.run();button("Volver",this::home);});}
 void visitor(){screen("Autorizar visitante",plant+" · Un QR por persona, con vencimiento.");EditText name=field("Nombre completo",""),doc=field("Documento (tipo, país y número)",""),cost=field("Centro de costo responsable",""),expires=field("Vence: AAAA-MM-DD",session.optString("today"));List<CheckBox> checks=new ArrayList<>();for(String m:new String[]{"desayuno","almuerzo","cena","dieta"}){CheckBox c=new CheckBox(this);c.setText(m);c.setChecked(m.equals(meal));page.addView(c);checks.add(c);}button("Crear QR temporal",()->{List<String> meals=new ArrayList<>();for(CheckBox c:checks)if(c.isChecked())meals.add(c.getText().toString());api(obj("action","visitor","plant",plant,"name",name.getText().toString(),"document",doc.getText().toString(),"costCenter",cost.getText().toString(),"expires",expires.getText().toString(),"meals",String.join(",",meals)),r->{if(success(r))card(r);});});button("Volver",this::home);}
 void card(JSONObject r)throws Exception{screen("Credencial QR",r.optString("person"));String qr=r.getString("qr");BitMatrix matrix=new MultiFormatWriter().encode(qr,BarcodeFormat.QR_CODE,700,700);Bitmap bitmap=Bitmap.createBitmap(700,900,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);for(int y=0;y<700;y++)for(int x=0;x<700;x++)bitmap.setPixel(x,y,matrix.get(x,y)?Color.BLACK:Color.WHITE);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(navy);paint.setTextSize(23);canvas.drawText("SOBOCE · COMEDOR",32,735,paint);paint.setTextSize(20);canvas.drawText(r.optString("person"),32,778,paint);paint.setTextSize(16);canvas.drawText(r.optString("detail"),32,820,paint);ImageView img=new ImageView(this);img.setImageBitmap(bitmap);img.setAdjustViewBounds(true);page.addView(img,new LinearLayout.LayoutParams(-1,dp(420)));label(r.optString("detail"),15,navy);if(r.has("notice"))label(r.optString("notice"),14,Color.DKGRAY);button("Compartir o guardar tarjeta",()->{try{File dir=new File(getCacheDir(),"cards");dir.mkdirs();File file=new File(dir,"credencial.png");try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}Intent share=new Intent(Intent.ACTION_SEND);share.setType("image/png");share.putExtra(Intent.EXTRA_STREAM,FileProvider.getUriForFile(this,getPackageName()+".files",file));share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(share,"Compartir credencial"));}catch(Exception e){error("No se pudo compartir la tarjeta.");}});button("Volver",this::home);}
 void operator(){screen("Nuevo encargado",plant+" · Acceso exclusivo para esta planta.");EditText name=field("Nombre del encargado","");button("Crear acceso",()->api(obj("action","operator","plant",plant,"name",name.getText().toString()),r->{if(!success(r))return;screen("Acceso creado",r.optString("person")+" · "+r.optString("plant"));label("Configura el teléfono del encargado con esta URL y su clave personal.",16,navy);TextView u=label(vault.get("url"),14,navy);u.setTextIsSelectable(true);TextView t=label(r.optString("access"),16,navy);t.setTextIsSelectable(true);label("Guarda la clave ahora. No volverá a mostrarse. Para revocarla, desactiva al encargado en la hoja Operadores.",14,Color.DKGRAY);button("Volver",this::home);}));button("Volver",this::home);}
 void report(){new ReportsScreen(this,plant,sharedMode());}

 void importPeer(Intent data){
  try(InputStream in=getContentResolver().openInputStream(data.getData())){
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>2097152)throw new IOException("Archivo demasiado grande");bytes.write(buffer,0,n);}
   String content=bytes.toString("UTF-8");PeerConfig config=new PeerConfig(content);
   if(!vault.get("peerConfig").isEmpty()){PeerConfig old=new PeerConfig(vault.get("peerConfig"));if(old.node!=config.node||!old.cluster.equals(config.cluster)){error("Este teléfono ya tiene identidad e historial. No se puede reemplazar por otro miembro.");return;}}
   if(!vault.get("pending").isEmpty()){error("Resuelve la entrega pendiente antes de cambiar de conexión.");return;}
   vault.put("peerConfig",content);vault.put("mode","p2p");connect();
  }catch(Exception e){error("No se pudo importar. Usa el archivo privado asignado exclusivamente a este teléfono.");}
 }
 void conflicts(){api(obj("action","conflicts","plant",plant,"allPlants",Boolean.toString(sharedMode())),r->{if(!success(r))return;screen("Revisar duplicados",(sharedMode()?"Todas las plantas":plant)+" · "+r.optInt("count")+" registros para revisar");label("Se conservan todas las entregas. Dos teléfonos sin conexión pueden registrar a la misma persona; compara estos registros antes de cerrar el reporte. El archivo mensual incluye la columna revisión.",15,Color.DKGRAY);JSONArray rows=r.optJSONArray("items");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject x=rows.getJSONObject(i);label(x.optString("fecha")+" "+x.optString("hora")+" · "+x.optString("nombre")+" · "+x.optString("comida")+" · "+x.optString("origen"),16,navy);}button("Volver",this::home);});}
 void network(){
  if(sharedMode()){sharedNetwork();return;}
  screen("Red de teléfonos","Puedes registrar comidas con este teléfono solamente. La red comparte los registros cuando hay conexión.");PeerEngine e=PeerService.engine;
  label(e==null?"Sincronización detenida; registro local disponible":e.state,20,navy);if(e!=null){label("Este equipo: teléfono "+(e.config.node+1),18,navy);try{label(e.config.nodes.getJSONObject(e.config.node).getString("url"),14,Color.DKGRAY);}catch(Exception ignored){}label(PeerService.health.message(),16,teal);if(!e.identityWarning.isEmpty())label(e.identityWarning,16,Color.rgb(160,50,30));for(int i=0;i<4;i++)if(i!=e.config.node)label("Teléfono "+(i+1)+": "+e.peerStates[i],14,Color.DKGRAY);}if(!PeerService.failure.isEmpty())label(PeerService.failure,16,Color.rgb(160,50,30));
  label("Para sincronizar deben coincidir al menos dos teléfonos con internet y la app activa. Cada uno puede retransmitir registros de los demás. Usa fecha automática; no borres datos ni copies una identidad a dos equipos.",15,Color.DKGRAY);
  button("Sincronizar ahora",()->{PeerEngine current=PeerService.engine;if(current==null){connect();return;}status.setText("Sincronización iniciada. Puedes volver y seguir registrando.");new Thread(current::sync,"manual-peer-sync").start();});
  button("Actualizar estado",this::network);button("Iniciar sincronización",this::connect);button("Ajustes de batería",()->{try{startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception e2){error("Abre los ajustes de batería del teléfono y revisa SOBOCE Comedor.");}});label("Si Android pausa la app, abre SOBOCE Comedor al recuperar internet para reanudar la sincronización.",14,Color.DKGRAY);
  button("Detener sincronización",()->new AlertDialog.Builder(this).setTitle("Detener sincronización").setMessage("Podrás seguir registrando comidas. Los registros quedarán en este teléfono hasta iniciar la sincronización otra vez.").setNegativeButton("Cancelar",null).setPositiveButton("Detener",(d,w)->{stopService(new Intent(this,PeerService.class));network();}).show());button("Volver",this::home);
 }
 void sharedNetwork(){
  screen("Entregas compartidas","Una misma app para todos. El servidor se elige automáticamente y los demás teléfonos intercambian las entregas con él.");
  TextView info=label("",18,navy);LinearLayout currentPage=page;
  try{PeerConfig c=new PeerConfig(vault.get("peerConfig"));label(c.raw.getString("url"),14,Color.DKGRAY);label("Este equipo: "+c.operator(-1).getString("name")+" · "+c.operator(-1).getString("plant"),15,navy);}catch(Exception ignored){}
  button("Ver entregas de todos",this::today);
  button("Sincronizar ahora",()->{PeerEngine e=PeerService.engine;if(e==null){connect();return;}new Thread(e::sync,"manual-shared-sync").start();});
  label("Los teléfonos sin conexión guardan las entregas y las comparten al volver. Si dos equipos desconectados sirven la misma comida, aparecerá marcada para revisión. Abre la app en cada teléfono para mantener la sincronización activa.",14,Color.DKGRAY);
  button("Volver",this::home);
  Runnable refresh=new Runnable(){public void run(){if(page!=currentPage||isFinishing()||isDestroyed())return;PeerEngine e=PeerService.engine;info.setText(e==null?"Sincronización detenida":e.state+(e.shared!=null&&e.shared.leading()?"\n"+PeerService.health.message():""));new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this,2000);}};refresh.run();
 }
 @Override public void onBackPressed(){if(scanner!=null){home();return;}if(busy)return;if(session!=null)home();else super.onBackPressed();}
}
