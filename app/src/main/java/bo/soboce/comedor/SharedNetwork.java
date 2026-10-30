package bo.soboce.comedor;

import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One public URL, one fenced tunnel owner, bidirectional replication to every client. */
class SharedNetwork {
    final PeerEngine engine; final PeerConfig config; final SharedLedger ledger; final LeaderLease lease;
    private final Map<String,Long> nonces=new LinkedHashMap<>();
    volatile String detail="Buscando servidor…"; volatile long lastExchange; volatile int clients;
    private volatile long leadershipSince,originSeen,cooldownUntil;
    private volatile boolean yieldRequested;
    private final Map<String,Long> seen=new HashMap<>();
    SharedNetwork(PeerEngine e){engine=e;config=e.config;ledger=new SharedLedger(e.db,config);lease=new LeaderLease(config.device);}
    boolean leading(){return lease.leading(SystemClock.elapsedRealtime());}
    void coordinate(){
        try{
            long sent=SystemClock.elapsedRealtime();
            boolean wasLeading=leading();long previousEpoch=lease.epoch;
            String action=yieldRequested&&wasLeading?"release":wasLeading?"renew":sent<cooldownUntil?"status":"acquire";
            JSONObject reply=http(config.raw.getString("coordinator")+"/lease",lease.request(action),"Bearer "+config.raw.getString("networkKey"));
            lease.update(reply,sent,SystemClock.elapsedRealtime());
            if(action.equals("release")){cooldownUntil=sent+60000;yieldRequested=false;}
            if(leading()&&(!wasLeading||previousEpoch!=lease.epoch)){leadershipSince=sent;originSeen=sent;}
            if(leading())detail="Este teléfono es el servidor";
            else if(lease.known(SystemClock.elapsedRealtime()))detail="Conectando al servidor";
            else detail="Esperando relevo del servidor";
        }catch(Exception error){detail="Sin coordinación por internet · registro local disponible";}
        updateState();
    }
    void updateState(){
        try{
            String recent=lastExchange==0?"aún sin intercambio":("último intercambio "+MealLedger.time(lastExchange));
            engine.state=detail+" · "+recent+" · "+ledger.pending()+(leading()?" entregas de este servidor sin copia confirmada en otro teléfono":" entregas locales pendientes de llegar al servidor");
        }catch(Exception ignored){engine.state=detail;}
    }
    JSONObject status() throws Exception {return new JSONObject().put("protocol",5).put("device",config.device).put("epoch",lease.epoch).put("vector",ledger.vector()).put("oldVector",engine.offline.vector()).put("applied",engine.db.applied());}
    JSONObject handle(JSONObject envelope) throws Exception {
        PeerConfig.require(leading(),"Servidor relevado.");
        String nonce=envelope.getString("nonce"),payload=envelope.getString("payload");long time=envelope.getLong("time"),epoch=envelope.getLong("epoch");
        PeerConfig.require(nonce.matches("[a-f0-9]{64}")&&Math.abs(System.currentTimeMillis()-time)<=120000&&epoch==lease.epoch,"Revisa fecha y servidor.");
        PeerConfig.require(PeerConfig.equal(config.mac("rpc|"+epoch+"|"+time+"|"+nonce+"|"+payload),envelope.getString("signature")),"Acceso denegado.");
        synchronized(nonces){PeerConfig.require(!nonces.containsKey(nonce),"Solicitud repetida.");nonces.put(nonce,time);if(nonces.size()>2048)nonces.remove(nonces.keySet().iterator().next());}
        JSONObject request=new JSONObject(payload),reply=dispatch(request);
        PeerConfig.require(leading()&&epoch==lease.epoch,"Servidor relevado durante la operación.");
        String data=reply.toString();return new JSONObject().put("payload",data).put("signature",config.mac("reply|"+epoch+"|"+nonce+"|"+data));
    }
    JSONObject dispatch(JSONObject r) throws Exception {
        PeerConfig.require(leading(),"Servidor relevado.");String method=r.getString("method");
        if(method.equals("status"))return status();
        PeerConfig.require(r.optInt("protocol")==5,"Actualiza todos los teléfonos a 1.6 para sincronizar Dieta.");
        if(method.equals("scan"))return ledger.scan(r.getJSONObject("request"),r.getJSONObject("operator"),System.currentTimeMillis());
        PeerConfig.require(method.equals("exchange"),"Método inválido.");
        String source=r.getString("device");PeerConfig.require(source.matches("[a-f0-9]{64}")&&!source.equals(config.device),"Instalación repetida.");
        synchronized(engine.db){
            mergeHistory(r.getJSONArray("history"));engine.offline.merge(r.getJSONArray("oldEvents"));ledger.merge(r.getJSONArray("events"));
            JSONObject reply=status().put("events",ledger.events(r.getJSONObject("after"),20)).put("oldEvents",engine.offline.events(r.getJSONArray("oldAfter"),20)).put("history",engine.db.history(r.getLong("applied"),20));
            // A client has durably stored these heads before advertising them.
            long copied=r.getJSONObject("after").optLong(config.device);
            long previous=Long.parseLong(engine.db.meta("sharedAck","0"));if(copied>previous)engine.db.putMeta("sharedAck",Long.toString(Math.min(copied,ledger.vector().optLong(config.device))));
            lastExchange=System.currentTimeMillis();originSeen=SystemClock.elapsedRealtime();synchronized(seen){seen.put(source,lastExchange);seen.entrySet().removeIf(x->x.getValue()<lastExchange-45000);clients=seen.size();}
            detail="Servidor activo · "+clients+" teléfonos sincronizando";updateState();return reply;
        }
    }
    private void mergeHistory(JSONArray history) throws Exception {PeerConfig.require(history.length()<=20,"Página demasiado grande.");for(int i=0;i<history.length();i++)engine.db.learn(PeerDatabase.decode(history.getJSONObject(i)));}
    JSONObject rpc(JSONObject request) throws Exception {
        PeerConfig.require(lease.known(SystemClock.elapsedRealtime()),"Servidor sin confirmar.");
        long epoch=lease.epoch,now=System.currentTimeMillis();String nonce=PeerConfig.random(),payload=request.put("protocol",5).toString();
        JSONObject env=new JSONObject().put("epoch",epoch).put("time",now).put("nonce",nonce).put("payload",payload).put("signature",config.mac("rpc|"+epoch+"|"+now+"|"+nonce+"|"+payload));
        JSONObject response=http(config.raw.getString("url")+"/rpc",env,null);String data=response.getString("payload");
        PeerConfig.require(epoch==lease.epoch&&PeerConfig.equal(config.mac("reply|"+epoch+"|"+nonce+"|"+data),response.getString("signature")),"Respuesta de servidor anterior o no autenticada.");return new JSONObject(data);
    }
    void sync(){
        synchronized(this){if(engine.syncing||engine.closed)return;engine.syncing=true;}
        try{
            if(leading()){
                try{JSONObject self=rpc(new JSONObject().put("method","status"));PeerConfig.require(self.getString("device").equals(config.device),"Otro conector respondió.");originSeen=SystemClock.elapsedRealtime();}
                catch(Exception unreachable){long now=SystemClock.elapsedRealtime();if(now-leadershipSince>60000&&now-originSeen>45000){yieldRequested=true;detail="Túnel sin respuesta; preparando relevo";}}
                updateState();return;
            }
            if(!lease.known(SystemClock.elapsedRealtime())){updateState();return;}
            exchange();
        }catch(Exception e){detail=e instanceof IllegalArgumentException&&e.getMessage()!=null&&e.getMessage().startsWith("Actualiza")?"Actualiza todos los teléfonos a 1.6 para compartir Dieta":"Sin intercambio confirmado · se reintentará automáticamente";}
        finally{engine.syncing=false;updateState();}
    }
    synchronized void exchange() throws Exception {
        JSONObject remote=rpc(new JSONObject().put("method","status"));
        PeerConfig.require(remote.getInt("protocol")==5&&!remote.getString("device").equals(config.device),"Actualiza los teléfonos o revisa la instalación.");
        boolean complete=false;
        for(int page=0;page<64&&!engine.closed;page++){
            JSONObject request;
            synchronized(engine.db){request=new JSONObject().put("method","exchange").put("protocol",5).put("device",config.device).put("after",ledger.vector()).put("oldAfter",engine.offline.vector()).put("applied",engine.db.applied()).put("events",ledger.events(remote.getJSONObject("vector"),20)).put("oldEvents",engine.offline.events(remote.getJSONArray("oldVector"),20)).put("history",engine.db.history(remote.getLong("applied"),20));}
            remote=rpc(request);
            synchronized(engine.db){mergeHistory(remote.getJSONArray("history"));engine.offline.merge(remote.getJSONArray("oldEvents"));ledger.merge(remote.getJSONArray("events"));ledger.acknowledge(remote.getJSONObject("vector"));}
            lastExchange=System.currentTimeMillis();
            complete=request.getJSONArray("events").length()+request.getJSONArray("oldEvents").length()+request.getJSONArray("history").length()+remote.getJSONArray("events").length()+remote.getJSONArray("oldEvents").length()+remote.getJSONArray("history").length()==0;
            if(complete)break;
        }
        detail=complete?"Conectado · entregas compartidas":"Compartiendo historial; continuará automáticamente";
    }
    JSONObject scan(JSONObject request) throws Exception {
        String id=request.getString("requestId"),marker="remoteAttempt:"+id;
        JSONObject saved=engine.db.get("shared_results",id);
        if(saved!=null){PeerConfig.require(OfflineLedger.sameRequest(request,saved.getJSONObject("request")),"Solicitud incompatible.");return SharedLedger.recovered(saved.getJSONObject("reply"));}
        String pending=engine.db.meta(marker,"");
        if(leading()){
            if(!pending.isEmpty())return uncertain(); // A previous server may have committed; never blindly re-authorize.
            return ledger.scan(request,config.operator(-1),System.currentTimeMillis());
        }
        try {exchange();}catch(Exception offline){
            if(!pending.isEmpty())return uncertain();
            return ledger.scan(request,config.operator(-1),System.currentTimeMillis());
        }
        saved=engine.db.get("shared_results",id);
        if(saved!=null)return SharedLedger.recovered(saved.getJSONObject("reply"));
        if(!pending.isEmpty()&&!pending.equals(Long.toString(lease.epoch)))return uncertain();
        engine.db.putMeta(marker,Long.toString(lease.epoch));
        try{
            JSONObject reply=rpc(new JSONObject().put("method","scan").put("protocol",5).put("request",request).put("operator",config.operator(-1)));
            if(reply.optBoolean("ok")||reply.optBoolean("definitive")){
                synchronized(engine.db){engine.db.save("shared_results",id,new JSONObject().put("request",request).put("reply",reply));engine.db.putMeta(marker,"");}
                try{exchange();}catch(Exception ignored){/* The receipt is confirmed; replication resumes automatically. */}
            }
            return reply;
        }catch(Exception unknown){return uncertain();}
    }
    static JSONObject uncertain() throws JSONException {return new JSONObject().put("ok",false).put("definitive",false).put("error","El servidor pudo guardar esta entrega, pero falta confirmación. No entregues otra ración. Mantén la operación pendiente y vuelve a comprobar cuando los teléfonos se reconecten.");}
    static JSONObject http(String url,JSONObject value,String authorization) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setRequestMethod("POST");c.setInstanceFollowRedirects(false);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");if(authorization!=null)c.setRequestProperty("Authorization",authorization);
        c.setRequestProperty("User-Agent","SOBOCE-Comedor/1.5");byte[] body=value.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(body.length);
        try{try(OutputStream out=c.getOutputStream()){out.write(body);}if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>1048576)throw new IOException("Respuesta demasiado grande.");bytes.write(buffer,0,n);}}return new JSONObject(bytes.toString("UTF-8"));
        }finally{c.disconnect();}
    }
}
