package bo.colbert.licensing;

import android.app.Activity;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Payment remains available when product operations are locked. */
public final class LicenseActivity extends Activity {
 private LicenseClient client;private LicenseUi ui;private boolean busy;private String image="",note="";private final ExecutorService worker=Executors.newSingleThreadExecutor();private final Handler timer=new Handler(Looper.getMainLooper());private String invoiceId="",shownState="";
 private final Runnable poll=()->refresh(false);
 @Override public void onCreate(Bundle b){super.onCreate(b);client=LicenseClient.get(this);ui=new LicenseUi(this);if(b!=null){image=b.getString("image","");note=b.getString("note","");}render();refresh(false);}
 @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);/* Large image stays in an encrypted pending request, never in a Binder bundle. */b.putString("note",note);}
 @Override protected void onResume(){super.onResume();timer.removeCallbacks(poll);timer.postDelayed(poll,15000);}
 @Override protected void onPause(){timer.removeCallbacks(poll);super.onPause();}
 @Override protected void onDestroy(){timer.removeCallbacks(poll);worker.shutdown();super.onDestroy();}
 interface Work{void run()throws Exception;}
 private void execute(Work work,Runnable done,boolean loud){if(busy||isFinishing()||isDestroyed())return;busy=true;worker.execute(()->{String error=null;try{work.run();}catch(Exception e){error=e.getMessage()==null?"No se pudo contactar al servidor.":e.getMessage();}String message=error;runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed())return;if(message==null)done.run();else if(loud){if(client.pending()!=null)render();ui.error(message);}timer.removeCallbacks(poll);if(hasWindowFocus())timer.postDelayed(poll,15000);});});}
 private void refresh(boolean loud){execute(()->client.refresh(),()->{if(client.allowed()){setResult(RESULT_OK);finish();}else if(!state(client.details()).equals(shownState))render();},loud);}
 String state(JSONObject d){return d.optString("reason")+d.optString("invoice")+d.optString("receipts");}
 void render(){
  JSONObject d=client.details();shownState=state(d);boolean usable=client.allowed();ui.screen(client.productName(),"Acceso por cliente y aplicación · todos sus equipos");
  if(usable){ui.banner("Acceso activo","Período pagado: "+LicenseUi.when(d.optLong("paidUntil")),LicenseUi.GREEN);ui.button("Volver a la aplicación",LicenseUi.BLUE,this::finish);}
  else ui.banner("Acceso pausado",d.optString("reason").equals("payment_due")?"Renueva tu licencia para continuar trabajando.":d.optString("reason").equals("suspended")?"El administrador suspendió el acceso. Contacta con él.":"Conecta con el servidor para validar tu permiso.",LicenseUi.RED);
  ui.text("Tus registros se conservan. El pago habilita a todos los equipos de este cliente para esta aplicación.",14,LicenseUi.MUTED);
  JSONObject inv=d.optJSONObject("invoice");invoiceId=inv==null?"":inv.optString("id");
  if(inv!=null){ui.text(LicenseUi.money(inv)+(inv.optString("interval").equals("day")?" / día":" / mes"),30,LicenseUi.INK);ui.text("Cobro: "+invoiceId,12,LicenseUi.MUTED);ui.text(inv.optString("note"),16,LicenseUi.INK);String qr=inv.optString("paymentQr");if(!qr.isEmpty())showImage(qr);JSONArray receipts=d.optJSONArray("receipts");if(receipts!=null)for(int i=0;i<receipts.length();i++){JSONObject r=receipts.optJSONObject(i);ui.text(r.optString("state").equals("pending")?"Comprobante enviado · esperando aprobación":r.optString("state").equals("rejected")?"Comprobante rechazado: "+r.optString("note"):"Comprobante aprobado",15,LicenseUi.MUTED);}
   EditText text=ui.field("Referencia o nota (opcional)",note);text.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){note=s.toString();}public void afterTextChanged(android.text.Editable e){}});
   ui.button(image.isEmpty()?"Adjuntar comprobante":"Cambiar comprobante seleccionado",LicenseUi.BLUE,()->{if(busy)return;Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,905);});if(!image.isEmpty()){showImage(image);ui.button("Enviar solicitud de habilitación",LicenseUi.GREEN,this::send);}
  }
  if(client.pending()!=null){ui.text("Hay un comprobante guardado en este teléfono que todavía espera confirmación de envío.",14,LicenseUi.MUTED);ui.button("Reintentar envío pendiente",LicenseUi.BLUE,this::retry);ui.button("Cancelar este reintento",LicenseUi.INK,()->new android.app.AlertDialog.Builder(this).setTitle("Cancelar envío pendiente").setMessage("Se quitará el reintento guardado en este teléfono. Si el servidor recibió el comprobante, seguirá allí para revisión. La imagen quedará seleccionada para poder enviarla al cobro vigente.").setNegativeButton("Volver",null).setPositiveButton("Cancelar reintento",(dialog,which)->execute(()->{JSONObject saved=client.pending();if(saved!=null){image=saved.getString("image");note=saved.optString("note");client.clearPending();}},this::render,true)).show());}
  ui.button("Comprobar acceso",LicenseUi.BLUE,()->refresh(true));ui.button("Volver",LicenseUi.INK,this::finish);
  ui.text("Necesitas internet para enviar el comprobante. Adjuntarlo no habilita el acceso: el administrador debe verificar el pago y aprobarlo. Un permiso vigente funciona hasta 24 horas desde su validación, sin superar su vencimiento. Después de reiniciar el equipo se valida nuevamente.",13,LicenseUi.MUTED);
 }
 void showImage(String b64){try{byte[] bytes=Base64.getDecoder().decode(b64);Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(bitmap!=null){ImageView view=new ImageView(this);view.setImageBitmap(bitmap);view.setAdjustViewBounds(true);view.setMaxHeight(ui.dp(300));ui.page.addView(view,new LinearLayout.LayoutParams(-1,-2));}}catch(Exception ignored){}}
 public static String compress(InputStream input)throws Exception{byte[] bytes=LicenseClient.read(input,12*1024*1024);BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);LicenseProtocol.require(o.outWidth>0&&o.outHeight>0&&o.outWidth<=30000&&o.outHeight<=30000,"Imagen inválida.");o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>1600)o.inSampleSize*=2;o.inJustDecodeBounds=false;Bitmap b=BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);LicenseProtocol.require(b!=null,"No se pudo leer la imagen.");ByteArrayOutputStream out=new ByteArrayOutputStream();for(int quality:new int[]{85,65,45}){out.reset();b.compress(Bitmap.CompressFormat.JPEG,quality,out);if(out.size()<=512*1024)break;}b.recycle();LicenseProtocol.require(out.size()<=512*1024,"Imagen demasiado grande. Usa una foto recortada del comprobante.");return Base64.getEncoder().encodeToString(out.toByteArray());}
 @Override public void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(code==905&&result==RESULT_OK&&data!=null){execute(()->{try(InputStream in=getContentResolver().openInputStream(data.getData())){image=compress(in);}},this::render,true);}}
 void send(){if(client.pending()!=null){ui.error("Confirma primero el comprobante pendiente.");return;}if(invoiceId.isEmpty()||image.isEmpty())return;String selected=invoiceId;execute(()->{client.savePending(new JSONObject().put("invoiceId",selected).put("requestId",UUID.randomUUID().toString()).put("image",image).put("note",note));sendPending();},()->{image="";note="";ui.error("Solicitud recibida. Espera la aprobación del administrador.");refresh(false);},true);}
 void sendPending()throws Exception{JSONObject p=client.pending();if(p==null)return;JSONObject r=client.submitReceipt(p.getString("invoiceId"),p.getString("requestId"),p.getString("image"),p.getString("note"));LicenseProtocol.require(r.optBoolean("ok"),"Envío sin confirmar.");client.clearPending();}
 void retry(){execute(this::sendPending,()->{image="";refresh(false);},true);}
}
