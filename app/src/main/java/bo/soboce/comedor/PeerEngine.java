package bo.soboce.comedor;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class PeerEngine implements QuorumLog.Network, AutoCloseable {
    final PeerConfig config;
    final PeerDatabase db;
    final QuorumLog consensus;
    private final ExecutorService clients=new ThreadPoolExecutor(4,4,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(12),new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,Long> nonces=new LinkedHashMap<>();
    private ServerSocket server;
    volatile boolean closed;
    volatile int connected=1;
    volatile String state="Iniciando conexión con los demás teléfonos…";
    PeerEngine(Context context,PeerConfig config) throws Exception {this.config=config;db=new PeerDatabase(context,config);consensus=new QuorumLog(config.node,db,this);}
    void start() throws IOException {
        server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),8787),16);
        Thread acceptor=new Thread(()->{
            while(!closed)try{Socket s=server.accept();try{clients.execute(()->serve(s));}catch(RejectedExecutionException e){s.close();}}catch(IOException e){if(!closed)state="No se pudo abrir el receptor local.";}
        },"comedor-peer-listener");acceptor.setDaemon(true);acceptor.start();
    }
    private void serve(Socket socket){
        try(Socket s=socket){
            s.setSoTimeout(5000);InputStream in=s.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();int b,tail=0;
            while(header.size()<8192&&(b=in.read())!=-1){header.write(b);tail=(tail<<8)|b;if(tail==0x0d0a0d0a)break;}
            PeerConfig.require(tail==0x0d0a0d0a,"Cabecera inválida.");
            String[] lines=header.toString("US-ASCII").split("\r\n");PeerConfig.require(lines[0].equals("POST /rpc HTTP/1.1"),"Ruta inválida.");int length=-1;
            for(String line:lines){String lower=line.toLowerCase(Locale.ROOT);PeerConfig.require(!lower.startsWith("transfer-encoding:"),"Codificación inválida.");if(lower.startsWith("content-length:")){PeerConfig.require(length==-1,"Longitud repetida.");length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());}}
            PeerConfig.require(length>0&&length<=1048576,"Solicitud demasiado grande.");byte[] bytes=new byte[length];int offset=0;
            while(offset<length){int n=in.read(bytes,offset,length-offset);if(n<0)throw new EOFException();offset+=n;}
            JSONObject envelope=new JSONObject(new String(bytes,StandardCharsets.UTF_8));String payload=envelope.getString("payload"),nonce=envelope.getString("nonce");int source=envelope.getInt("source");long time=envelope.getLong("time");
            PeerConfig.require(source>=0&&source<4&&nonce.matches("[a-f0-9]{64}")&&Math.abs(System.currentTimeMillis()-time)<=120000,"Identidad o reloj inválido.");
            PeerConfig.require(config.verify(source,"request|"+config.cluster+"|"+source+"|"+time+"|"+nonce+"|"+payload,envelope.getString("signature")),"Acceso denegado.");
            synchronized(nonces){PeerConfig.require(!nonces.containsKey(nonce),"Solicitud repetida.");nonces.put(nonce,time);if(nonces.size()>2048)nonces.remove(nonces.keySet().iterator().next());}
            JSONObject request=new JSONObject(payload);String method=request.getString("method");
            if(method.equals("prepare")||method.equals("accept"))PeerConfig.require(request.getLong("ballot")%4==source,"Proponente inválido.");
            JSONObject reply;try{reply=dispatch(request);}catch(Exception e){reply=new JSONObject().put("error","Operación no confirmada.");}
            String result=reply.toString();byte[] body=new JSONObject().put("payload",result).put("signature",config.sign("reply|"+nonce+"|"+result)).toString().getBytes(StandardCharsets.UTF_8);
            OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
        }catch(Exception ignored){/* Do not disclose authentication details or personal data in logs. */}
    }
    JSONObject dispatch(JSONObject r) throws Exception {
        String method=r.getString("method");
        if(method.equals("status"))return new JSONObject().put("time",System.currentTimeMillis()).put("applied",db.applied());
        if(method.equals("history"))return new JSONObject().put("items",db.history(r.getLong("after"),20));
        if(method.equals("prepare")){
            var p=db.prepare(r.getLong("slot"),r.getLong("ballot"));return new JSONObject().put("granted",p.granted()).put("promised",p.promised()).put("accepted",p.acceptedBallot()).put("value",p.value()==null?JSONObject.NULL:p.value());
        }
        if(method.equals("accept")){
            var v=db.accept(r.getLong("slot"),r.getLong("ballot"),r.getString("value"));return new JSONObject().put("accepted",v!=null).put("node",config.node).put("signature",v==null?"":v.signature());
        }
        if(method.equals("learn")){db.learn(PeerDatabase.decode(r.getJSONObject("decision")));return MealLedger.ok();}
        throw new IOException("Método inválido.");
    }
    JSONObject rpc(int peer,JSONObject request) throws Exception {
        if(closed)throw new IOException("Red detenida.");if(peer==config.node)return dispatch(request);
        String nonce=PeerConfig.random(),payload=request.toString();long now=System.currentTimeMillis();
        JSONObject envelope=new JSONObject().put("source",config.node).put("time",now).put("nonce",nonce).put("payload",payload).put("signature",config.sign("request|"+config.cluster+"|"+config.node+"|"+now+"|"+nonce+"|"+payload));
        HttpURLConnection c=(HttpURLConnection)new URL(config.nodes.getJSONObject(peer).getString("url")+"/rpc").openConnection();
        c.setConnectTimeout(2500);c.setReadTimeout(4000);c.setRequestMethod("POST");c.setInstanceFollowRedirects(false);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[] body=envelope.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(body.length);
        try{
            try(OutputStream out=c.getOutputStream()){out.write(body);}if(c.getResponseCode()!=200)throw new IOException("Teléfono no disponible.");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>1048576)throw new IOException("Respuesta demasiado grande.");bytes.write(buffer,0,n);}}
            JSONObject reply=new JSONObject(bytes.toString("UTF-8"));String data=reply.getString("payload");PeerConfig.require(config.verify(peer,"reply|"+nonce+"|"+data,reply.getString("signature")),"Respuesta no autenticada.");JSONObject result=new JSONObject(data);if(result.has("error"))throw new IOException("Teléfono sin confirmación.");return result;
        }finally{c.disconnect();}
    }
    @Override public QuorumLog.Promise prepare(int peer,long slot,long ballot) throws Exception {JSONObject p=rpc(peer,new JSONObject().put("method","prepare").put("slot",slot).put("ballot",ballot));return new QuorumLog.Promise(p.getBoolean("granted"),p.getLong("promised"),p.getLong("accepted"),p.isNull("value")?null:p.getString("value"));}
    @Override public QuorumLog.Vote accept(int peer,long slot,long ballot,String value) throws Exception {
        JSONObject r=rpc(peer,new JSONObject().put("method","accept").put("slot",slot).put("ballot",ballot).put("value",value));
        if(!r.getBoolean("accepted"))return null;PeerConfig.require(r.getInt("node")==peer&&config.validVote(peer,slot,ballot,value,r.getString("signature")),"Voto inválido.");return new QuorumLog.Vote(peer,r.getString("signature"));
    }
    @Override public void learn(int peer,QuorumLog.Decision d) throws Exception {rpc(peer,new JSONObject().put("method","learn").put("decision",PeerDatabase.encode(d)));}
    synchronized void sync(){
        int online=1;
        for(int peer=0;peer<4&&!closed;peer++)if(peer!=config.node)try{
            JSONObject status=rpc(peer,new JSONObject().put("method","status"));long remote=status.getLong("applied");
            if(Math.abs(System.currentTimeMillis()-status.getLong("time"))>60000)continue;
            online++;
            for(int page=0;page<32&&db.applied()<remote;page++){
                long before=db.applied();JSONArray items=rpc(peer,new JSONObject().put("method","history").put("after",before)).getJSONArray("items");
                for(int i=0;i<items.length();i++)db.learn(PeerDatabase.decode(items.getJSONObject(i)));if(db.applied()==before)break;
            }
        }catch(Exception ignored){/* Next cycle resumes at the last durable applied slot. */}
        connected=online;state=online+"/4 teléfonos · "+db.applied()+" operaciones sincronizadas"+(online<3?" · Entregas bloqueadas":"");
    }
    JSONObject request(JSONObject original) throws Exception {
        String action=original.getString("action");
        if(Arrays.asList("session","today","report","people").contains(action))return new MealLedger(db,config).read(original);
        if(!Arrays.asList("scan","visitor","issue").contains(action))throw new IllegalArgumentException("Los encargados se configuran al preparar la red.");
        JSONObject r=new JSONObject(original.toString());r.remove("token");
        if(!r.has("requestId"))r.put("requestId",UUID.randomUUID().toString());
        PeerConfig.require(r.getString("requestId").matches("[a-f0-9-]{36}"),"Solicitud inválida.");
        JSONObject existing=db.get("results",r.getString("requestId"));
        if(existing!=null){
            JSONObject old=existing.getJSONObject("request");
            PeerConfig.require(existing.getInt("actor")==config.node&&old.getString("action").equals(action)&&old.optString("qr").equals(r.optString("qr"))&&old.optString("meal").equals(r.optString("meal"))&&old.optString("plant").equals(r.optString("plant")),"Solicitud incompatible.");
            return replyForAttempt(existing,null);
        }
        // A wrong date on one phone must not create a different daily meal key.
        int clocks=0;long now=System.currentTimeMillis();String today=MealLedger.day(now);
        for(int peer=0;peer<4;peer++)try{long time=rpc(peer,new JSONObject().put("method","status")).getLong("time");if(Math.abs(now-time)<=60000&&MealLedger.day(time).equals(today))clocks++;}catch(Exception ignored){}
        if(clocks<3)throw new IOException("Se requieren 3 teléfonos conectados y con fecha automática correcta.");
        if(action.equals("issue")||action.equals("visitor"))r.put("newQr","SB1:"+PeerConfig.random());
        String command=config.seal(new JSONObject().put("request",r).put("actor",config.node).put("time",now).put("attempt",UUID.randomUUID().toString()));consensus.append(command);
        JSONObject result=db.get("results",r.getString("requestId"));if(result==null)throw new IOException("Sin confirmación.");
        return replyForAttempt(result,command);
    }
    static JSONObject replyForAttempt(JSONObject saved,String command) throws Exception {
        JSONObject reply=new JSONObject(saved.getJSONObject("reply").toString());
        if((command==null||!saved.getString("commandHash").equals(PeerConfig.hash(command)))&&reply.optString("status").equals("accepted"))reply.put("status","recovered").put("message","Registro anterior confirmado. No entregar otra ración.");
        return reply;
    }
    @Override public void close(){closed=true;try{if(server!=null)server.close();}catch(IOException ignored){}clients.shutdownNow();/* SQLite is kept open while already running requests unwind. */}
}
