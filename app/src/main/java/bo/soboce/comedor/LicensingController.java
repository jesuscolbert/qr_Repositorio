package bo.soboce.comedor;
import bo.colbert.licensing.*;
import android.os.*;
import org.json.*;
import java.util.concurrent.*;

/** Commercial access never deletes records or prevents history replication/export. */
final class LicensingController {
 final MainActivity activity;final LicenseClient client;private final Handler ui=new Handler(Looper.getMainLooper());private final ExecutorService worker=Executors.newSingleThreadExecutor();private boolean resumed,busy,lastAllowed;private long attempted;
 LicensingController(MainActivity a){activity=a;client=LicenseClient.get(a);lastAllowed=client.allowed();}
 private final Runnable tick=new Runnable(){public void run(){if(!resumed)return;boolean now=client.allowed();if(now!=lastAllowed){lastAllowed=now;activity.licenseChanged();}if(client.configured()&&!busy&&client.refreshDue()&&SystemClock.elapsedRealtime()-attempted>15000){busy=true;attempted=SystemClock.elapsedRealtime();worker.execute(()->{try{client.refresh();}catch(Exception ignored){}ui.post(()->{busy=false;if(resumed){boolean allowed=client.allowed();if(allowed!=lastAllowed){lastAllowed=allowed;activity.licenseChanged();}}});});}ui.postDelayed(this,1000);}};
 void resume(){resumed=true;ui.removeCallbacks(tick);ui.post(tick);}
 void pause(){resumed=false;ui.removeCallbacks(tick);}
 void close(){pause();worker.shutdown();}
 static boolean mutation(JSONObject request){String action=request.optString("action");return action.equals("scan")||action.equals("issue")||action.equals("visitor")||action.equals("operator");}
 static JSONObject denied()throws JSONException{return new JSONObject().put("ok",false).put("definitive",true).put("error","Licencia pausada. Abre Licencia y pagos para renovar el permiso.");}
}
