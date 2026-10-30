package bo.soboce.comedor;

import android.graphics.Color;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.journeyapps.barcodescanner.*;
import org.json.*;
import java.util.*;

/** Camera remains open; a receipt is never shown green before durable confirmation. */
final class ScanScreen {
    final MainActivity activity;final String plant,meal;final Vault vault;final ScanGate gate=new ScanGate();
    final Handler ui=new Handler(Looper.getMainLooper());final DecoratedBarcodeView camera;final FrameLayout frame;final TextView hint;
    LinearLayout resultBox;View overlay;boolean closed,resumed;JSONObject deferred,lastReply;ToneGenerator tone;
    final Runnable next=()->{if(closed||!resumed)return;ready();};
    ScanScreen(MainActivity a,String plant,String meal){
        activity=a;this.plant=plant;this.meal=meal;vault=new Vault(a);
        LinearLayout root=Design.column(a);root.setBackgroundColor(Design.INK);root.setPadding(d(20),d(14),d(20),d(18));root.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(d(20),in.getSystemWindowInsetTop()+d(14),d(20),in.getSystemWindowInsetBottom()+d(18));return in;});
        LinearLayout nav=Design.row(a);Button back=Design.button(a,"‹  Salir",0xFF263650,Color.WHITE,a::home);nav.addView(back);TextView mode=Design.text(a,"LECTOR CONTINUO",11,Design.LIME,true);mode.setGravity(Gravity.END);nav.addView(mode,new LinearLayout.LayoutParams(0,-2,1));root.addView(nav);Design.space(root,16);
        root.addView(Design.text(a,Meals.title(meal),30,Color.WHITE,true));Design.space(root,4);root.addView(Design.text(a,plant,13,0xFFB9C7DF,false));Design.space(root,18);
        frame=new FrameLayout(a);frame.setBackground(Design.shape(a,0xFF263650,22));frame.setClipToOutline(true);root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        camera=new DecoratedBarcodeView(a);camera.setStatusText("");camera.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));frame.addView(camera,new FrameLayout.LayoutParams(-1,-1));
        camera.getBarcodeView().addStateListener(new CameraPreview.StateListener(){public void previewSized(){}public void previewStarted(){}public void previewStopped(){}public void cameraClosed(){}public void cameraError(Exception e){if(!closed){gate.hold();hint.setText("No se pudo abrir la cámara. Sal y vuelve a iniciar el lector.");}}});
        Design.space(root,18);hint=Design.text(a,"Acerca un QR al recuadro",17,Color.WHITE,true);hint.setGravity(Gravity.CENTER);root.addView(hint);Design.space(root,8);TextView instruction=Design.text(a,"Cada lectura registra una entrega de "+meal+".\nRetira la tarjeta para pasar a la siguiente persona.",12,0xFFB9C7DF,false);instruction.setGravity(Gravity.CENTER);root.addView(instruction);
        a.setContentView(root);a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        camera.decodeContinuous(new BarcodeCallback(){public void barcodeResult(BarcodeResult r){onCode(r.getText());}public void possibleResultPoints(List<ResultPoint> points){}});
        if(!vault.get("pending").isEmpty()){gate.hold();show(a.obj("ok","false","error","Hay una entrega pendiente. Comprueba su resultado antes de continuar."));}
    }
    int d(int x){return Design.dp(activity,x);}
    void onCode(String qr){
        if(closed||!resumed||qr==null||!gate.read(qr,SystemClock.elapsedRealtime()))return;
        if(!qr.matches("SB1:[a-f0-9]{64}")&&!(vault.get("mode").equals("p2p")&&PeerCards.format(qr))){show(activity.obj("ok","false","definitive","true","error","Este QR no pertenece al comedor."));return;}
        try{JSONObject request=activity.obj("action","scan","plant",plant,"meal",meal,"qr",qr,"requestId",UUID.randomUUID().toString());vault.put("pending",request.toString());send(request);}catch(Exception e){show(activity.obj("ok","false","error","No se pudo guardar la solicitud. Comprueba el registro antes de continuar."));}
    }
    void send(JSONObject request){
        gate.hold();ui.removeCallbacks(next);waiting();
        activity.submitScan(request,r->{if(closed)return;if(!resumed){deferred=r;return;}complete(r);});
    }
    void complete(JSONObject reply){
        ScanFeedback.Kind kind=ScanFeedback.kind(reply);
        if(kind!=ScanFeedback.Kind.PENDING)vault.remove("pending");
        show(reply);signal(kind);
    }
    void waiting(){
        clearOverlay();LinearLayout box=Design.card(activity,Design.INK,24);box.setGravity(Gravity.CENTER);ProgressBar p=new ProgressBar(activity);box.addView(p,new LinearLayout.LayoutParams(d(52),d(52)));Design.space(box,20);box.addView(Design.text(activity,"COMPROBANDO…",20,Color.WHITE,true));TextView text=Design.text(activity,"Espera la confirmación antes de servir.",14,0xFFCBD5E5,false);text.setGravity(Gravity.CENTER);Design.space(box,12);box.addView(text);overlay=box;frame.addView(box,new FrameLayout.LayoutParams(-1,-1));hint.setText("Guardando la entrega");
    }
    void show(JSONObject reply){
        if(closed)return;lastReply=reply;ui.removeCallbacks(next);gate.hold();clearOverlay();ScanFeedback.Kind kind=ScanFeedback.kind(reply);
        int bg=kind==ScanFeedback.Kind.ALLOWED?Design.GREEN:kind==ScanFeedback.Kind.PENDING?Design.AMBER:Design.RED,fg=kind==ScanFeedback.Kind.PENDING?Design.INK:Color.WHITE;
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setBackground(Design.shape(activity,bg,22));resultBox=Design.column(activity);resultBox.setPadding(d(22),d(22),d(22),d(22));resultBox.setGravity(Gravity.CENTER);scroll.addView(resultBox);overlay=scroll;frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        resultBox.addView(new Design.Icon(activity,kind==ScanFeedback.Kind.ALLOWED?"check":"stop",fg),new LinearLayout.LayoutParams(d(76),d(76)));Design.space(resultBox,20);
        TextView title=Design.text(activity,ScanFeedback.title(kind),24,fg,true);title.setGravity(Gravity.CENTER);resultBox.addView(title);Design.space(resultBox,14);
        if(!reply.optString("person").isEmpty()){TextView name=Design.text(activity,reply.optString("person"),20,fg,true);name.setGravity(Gravity.CENTER);resultBox.addView(name);Design.space(resultBox,10);}
        String description=kind==ScanFeedback.Kind.ALLOWED?"Entrega de "+meal+" autorizada · puedes servir":kind==ScanFeedback.Kind.DUPLICATE?"Esta comida ya está registrada.\nNO ENTREGAR OTRA RACIÓN.":reply.optString("error","Comprueba la operación pendiente.");
        TextView detail=Design.text(activity,description,15,fg,false);detail.setGravity(Gravity.CENTER);resultBox.addView(detail);
        if(!reply.optString("time").isEmpty()){Design.space(resultBox,12);TextView time=Design.text(activity,reply.optString("plant")+" · "+reply.optString("time"),12,fg,false);time.setGravity(Gravity.CENTER);resultBox.addView(time);}
        Design.space(resultBox,20);
        if(kind==ScanFeedback.Kind.PENDING){resultBox.addView(Design.button(activity,"Comprobar pendiente",Design.INK,Color.WHITE,()->{try{send(new JSONObject(vault.get("pending")));}catch(Exception e){activity.home();}}));hint.setText("No autorices otra entrega todavía");}
        else {TextView onward=Design.text(activity,"SIGUIENTE QR  →",13,fg,true);onward.setGravity(Gravity.CENTER);resultBox.addView(onward);hint.setText("El lector continúa automáticamente");if(resumed)ui.postDelayed(next,ScanFeedback.delay(kind));}
        resultBox.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
    }
    private void clearOverlay(){if(overlay!=null){frame.removeView(overlay);overlay=null;}}
    void ready(){lastReply=null;clearOverlay();gate.ready();hint.setText("Acerca el siguiente QR");}
    void signal(ScanFeedback.Kind kind){
        if(!resumed||kind==ScanFeedback.Kind.PENDING)return;
        try{if(tone==null)tone=new ToneGenerator(AudioManager.STREAM_MUSIC,85);tone.startTone(kind==ScanFeedback.Kind.ALLOWED?ToneGenerator.TONE_PROP_ACK:ToneGenerator.TONE_PROP_NACK,kind==ScanFeedback.Kind.ALLOWED?150:300);if(kind!=ScanFeedback.Kind.ALLOWED)ui.postDelayed(()->{if(!closed&&resumed&&tone!=null)tone.startTone(ToneGenerator.TONE_PROP_NACK,250);},420);
            Vibrator vibrator=(Vibrator)activity.getSystemService(android.content.Context.VIBRATOR_SERVICE);if(vibrator!=null&&vibrator.hasVibrator())vibrator.vibrate(VibrationEffect.createWaveform(kind==ScanFeedback.Kind.ALLOWED?new long[]{0,90}:new long[]{0,220,140,220},-1));
        }catch(RuntimeException ignored){/* The visual result remains definitive even if sound/haptics are unavailable. */}
    }
    void resume(){if(closed)return;resumed=true;camera.resume();if(deferred!=null){JSONObject r=deferred;deferred=null;complete(r);}else if(lastReply!=null)show(lastReply);}
    void pause(){resumed=false;ui.removeCallbacks(next);camera.pause();}
    void close(){closed=true;pause();ui.removeCallbacksAndMessages(null);camera.getBarcodeView().stopDecoding();if(tone!=null){tone.release();tone=null;}activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
}
