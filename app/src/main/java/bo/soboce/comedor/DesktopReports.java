package bo.soboce.comedor;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** A derived read-only credential cannot authorize RPCs, enroll workers, or record meals. */
final class DesktopReports {
    final PeerEngine engine;final Map<String,Long> nonces=new LinkedHashMap<>();
    DesktopReports(PeerEngine engine){this.engine=engine;}
    static String mac(String key,String message)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return PeerConfig.hex(m.doFinal(message.getBytes(StandardCharsets.UTF_8)));}
    String key()throws Exception{return engine.config.mac("desktop-reports-v1");}
    JSONObject handle(JSONObject envelope)throws Exception {
        long now=System.currentTimeMillis(),time=envelope.getLong("time");String nonce=envelope.getString("nonce"),payload=envelope.getString("payload");
        PeerConfig.require(nonce.matches("[a-f0-9]{64}")&&Math.abs(now-time)<=120000,"Reloj o solicitud inválida.");
        PeerConfig.require(PeerConfig.equal(mac(key(),"request|"+time+"|"+nonce+"|"+payload),envelope.getString("signature")),"Consulta no autorizada.");
        synchronized(nonces){nonces.entrySet().removeIf(x->x.getValue()<now-120000);PeerConfig.require(nonces.size()<512&&!nonces.containsKey(nonce),"Espera antes de consultar otra vez.");nonces.put(nonce,now);}
        JSONObject request=new JSONObject(payload);String action=request.getString("action");PeerConfig.require(action.equals("report")||action.equals("export"),"Operación de consulta inválida.");
        JSONObject result;
        if(!engine.license.allowed())result=new JSONObject().put("ok",false).put("error","license");
        else if(engine.shared==null||!engine.shared.leading())result=new JSONObject().put("ok",false).put("error","leader");
        else {
            long epoch=engine.shared.lease.epoch;String plant=request.optString("plant");JSONArray plants=engine.config.seed.getJSONArray("plants");boolean known=plant.isEmpty();for(int i=0;i<plants.length();i++)known|=plants.getString(i).equals(plant);PeerConfig.require(known,"Planta inválida.");
            JSONObject query=new JSONObject(request.toString()).put("action","report").put("preview",!action.equals("export")).put("format","xlsx");
            synchronized(engine.db){result=MealReports.build(new OfflineLedger(engine.db,engine.config).records(),query,MealLedger.day(now),plant.isEmpty());}
            // Only the fields needed for reports leave the phone. QR hashes and signatures never do.
            String[] fields={"fecha","hora","persona","nombre","comida","planta","operador","precio","centroCosto","registro","origen","revision","precioPendiente"};
            for(String list:new String[]{"items","exportItems"}){JSONArray source=result.optJSONArray(list);if(source==null)continue;JSONArray clean=new JSONArray();int end=list.equals("items")?Math.min(200,source.length()):source.length();for(int i=0;i<end;i++){JSONObject row=source.getJSONObject(i),item=new JSONObject();for(String field:fields)if(row.has(field))item.put(field,row.get(field));clean.put(item);}result.put(list,clean);}
            result.put("plants",plants).put("serverTime",now).put("ratesConfigured",engine.config.seed.optBoolean("ratesConfigured",true));
            if(action.equals("export")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();ExcelReport.write(bytes,result,engine.serviceName,plant.isEmpty()?"Todas las plantas":plant);PeerConfig.require(bytes.size()<=20*1024*1024,"Exporta un período más pequeño.");byte[] data=bytes.toByteArray();result.remove("exportItems");result.remove("items");result.put("file",Base64.getEncoder().encodeToString(data)).put("sha256",PeerConfig.hex(java.security.MessageDigest.getInstance("SHA-256").digest(data))).put("filename",ExcelReport.filename(result));}
            if(!engine.shared.leading()||engine.shared.lease.epoch!=epoch)result=new JSONObject().put("ok",false).put("error","leader");
        }
        String body=result.toString();return new JSONObject().put("payload",body).put("signature",mac(key(),"reply|"+nonce+"|"+body));
    }
}
