package bo.soboce.comedor;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

class PeerEngine implements QuorumLog.Network, AutoCloseable {
    static final int PROTOCOL=3;
    final PeerConfig config;
    final bo.colbert.licensing.LicenseClient license;
    final PeerDatabase db;
    final OfflineLedger offline;
    SharedNetwork shared;
    final String instance;
    final int localPort;
    final String serviceName;
    volatile String identityWarning="";
    final String[] peerStates={"Sin comprobar","Sin comprobar","Sin comprobar","Sin comprobar"};
    private final ExecutorService clients=new ThreadPoolExecutor(4,4,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(12),new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,Long> nonces=new LinkedHashMap<>();
    private ServerSocket server;
    volatile boolean closed;
    volatile int connected=1;
    volatile boolean syncing;
    volatile String state="Registro local disponible. Buscando teléfonos para sincronizar…";
    PeerEngine(Context context,PeerConfig config) throws Exception {this(context,config,"comedor-peers.db");}
    PeerEngine(Context context,PeerConfig config,String databaseName) throws Exception {this.config=config;localPort=context.getResources().getInteger(R.integer.peer_port);serviceName=context.getString(R.string.app_name);license=bo.colbert.licensing.LicenseClient.get(context);db=new PeerDatabase(context,config,databaseName);offline=new OfflineLedger(db,config);shared=config.shared?new SharedNetwork(this):null;synchronized(db){String saved=db.meta("instance","");if(saved.isEmpty()){saved=UUID.randomUUID().toString();db.putMeta("instance",saved);}instance=saved;}}
    void start() throws IOException {
        server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),localPort),16);
        Thread acceptor=new Thread(()->{
            while(!closed)try{Socket s=server.accept();try{clients.execute(()->serve(s));}catch(RejectedExecutionException e){s.close();}}catch(IOException e){if(!closed)state="No se pudo abrir el receptor local.";}
        },"comedor-peer-listener");acceptor.setDaemon(true);acceptor.start();
    }
    private void serve(Socket socket){
        try(Socket s=socket){
          try {
            s.setSoTimeout(5000);InputStream in=s.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();int b,tail=0;
            while(header.size()<8192&&(b=in.read())!=-1){header.write(b);tail=(tail<<8)|b;if(tail==0x0d0a0d0a)break;}
            PeerConfig.require(tail==0x0d0a0d0a,"Cabecera inválida.");
            String[] lines=header.toString("US-ASCII").split("\r\n");if(lines[0].equals("GET / HTTP/1.1")||lines[0].equals("GET /health HTTP/1.1")){send(s,200,new JSONObject().put("ok",true).put("service",serviceName).toString());return;}PeerConfig.require(lines[0].equals("POST /rpc HTTP/1.1"),"Ruta inválida.");int length=-1;
            for(String line:lines){String lower=line.toLowerCase(Locale.ROOT);PeerConfig.require(!lower.startsWith("transfer-encoding:"),"Codificación inválida.");if(lower.startsWith("content-length:")){PeerConfig.require(length==-1,"Longitud repetida.");length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());}}
            PeerConfig.require(length>0&&length<=1048576,"Solicitud demasiado grande.");byte[] bytes=new byte[length];int offset=0;
            while(offset<length){int n=in.read(bytes,offset,length-offset);if(n<0)throw new EOFException();offset+=n;}
            JSONObject envelope=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            if(shared!=null){send(s,200,shared.handle(envelope).toString());return;}
            String payload=envelope.getString("payload"),nonce=envelope.getString("nonce");int source=envelope.getInt("source");long time=envelope.getLong("time");
            PeerConfig.require(source>=0&&source<4&&nonce.matches("[a-f0-9]{64}")&&Math.abs(System.currentTimeMillis()-time)<=120000,"Identidad o reloj inválido.");
            PeerConfig.require(config.verify(source,"request|"+config.cluster+"|"+source+"|"+time+"|"+nonce+"|"+payload,envelope.getString("signature")),"Acceso denegado.");
            synchronized(nonces){PeerConfig.require(!nonces.containsKey(nonce),"Solicitud repetida.");nonces.put(nonce,time);if(nonces.size()>2048)nonces.remove(nonces.keySet().iterator().next());}
            JSONObject request=new JSONObject(payload);String method=request.getString("method");
            if(method.equals("prepare")||method.equals("accept"))PeerConfig.require(request.getLong("ballot")%4==source,"Proponente inválido.");
            JSONObject reply;try{reply=dispatch(request);}catch(Exception e){reply=new JSONObject().put("error","Operación no confirmada.");}
            String result=reply.toString();byte[] body=new JSONObject().put("payload",result).put("signature",config.sign("reply|"+nonce+"|"+result)).toString().getBytes(StandardCharsets.UTF_8);
            OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
          }catch(Exception rejected){try{send(s,400,"{\"error\":\"Solicitud inválida o no autorizada; comprueba fecha y configuración.\"}");}catch(IOException ignored){}}
        }catch(IOException ignored){/* No personal data or connector secrets in logs. */}
    }
    private static void send(Socket socket,int code,String data) throws IOException {byte[] body=data.getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+code+(code==200?" OK":" Bad Request")+"\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}
    JSONObject dispatch(JSONObject r) throws Exception {
        String method=r.getString("method");
        if(method.equals("status"))return new JSONObject().put("time",System.currentTimeMillis()).put("applied",db.applied()).put("protocol",PROTOCOL).put("vector",offline.vector()).put("instance",instance);
        // Historical decisions remain readable during migration; no new quorum votes are issued.
        if(method.equals("history"))return new JSONObject().put("items",db.history(r.getLong("after"),20));
        PeerConfig.require(r.optInt("protocol",1)==PROTOCOL,"Actualiza este teléfono para sincronizar consumos sin conexión.");
        if(method.equals("events"))return new JSONObject().put("items",offline.events(r.getJSONArray("after"),20));
        if(method.equals("merge")){offline.merge(r.getJSONArray("items"));return new JSONObject().put("vector",offline.vector());}
        throw new IOException("Método inválido.");
    }
    JSONObject rpc(int peer,JSONObject request) throws Exception {
        if(closed)throw new IOException("Red detenida.");if(!request.has("protocol"))request.put("protocol",PROTOCOL);if(peer==config.node)return dispatch(request);return remoteRpc(peer,request);
    }
    JSONObject remoteRpc(int peer,JSONObject request) throws Exception {
        String nonce=PeerConfig.random(),payload=request.toString();long now=System.currentTimeMillis();
        JSONObject envelope=new JSONObject().put("source",config.node).put("time",now).put("nonce",nonce).put("payload",payload).put("signature",config.sign("request|"+config.cluster+"|"+config.node+"|"+now+"|"+nonce+"|"+payload));
        HttpURLConnection c=(HttpURLConnection)new URL(config.nodes.getJSONObject(peer).getString("url")+"/rpc").openConnection();
        c.setConnectTimeout(2500);c.setReadTimeout(4000);c.setRequestMethod("POST");c.setInstanceFollowRedirects(false);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[] body=envelope.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(body.length);
        try{
            try(OutputStream out=c.getOutputStream()){out.write(body);}if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode()+(c.getResponseCode()==530?" · túnel sin conexión":c.getResponseCode()==502?" · conector activo, app no responde":c.getResponseCode()==400?" · comprueba fecha e identidad":""));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>1048576)throw new IOException("Respuesta demasiado grande.");bytes.write(buffer,0,n);}}
            JSONObject reply=new JSONObject(bytes.toString("UTF-8"));String data=reply.getString("payload");PeerConfig.require(config.verify(peer,"reply|"+nonce+"|"+data,reply.getString("signature")),"Respuesta no autenticada.");JSONObject result=new JSONObject(data);if(result.has("error"))throw new IOException("App conectada, pero no confirmó la operación.");return result;
        }finally{c.disconnect();}
    }
    void checkOwnIdentity(){try{JSONObject status=remoteRpc(config.node,new JSONObject().put("method","status").put("protocol",PROTOCOL));String warning=duplicateIdentity(instance,status);if(!warning.isEmpty())identityWarning=warning;}catch(Exception ignored){/* A disconnected tunnel is shown by TunnelHealth. */}}
    static String duplicateIdentity(String own,JSONObject status){return !status.optString("instance").isEmpty()&&!own.equals(status.optString("instance"))?"Identidad repetida: otro equipo usa el mismo número. Instala una APK distinta para cada encargado.":"";}
    @Override public QuorumLog.Promise prepare(int peer,long slot,long ballot) throws Exception {JSONObject p=rpc(peer,new JSONObject().put("method","prepare").put("slot",slot).put("ballot",ballot));return new QuorumLog.Promise(p.getBoolean("granted"),p.getLong("promised"),p.getLong("accepted"),p.isNull("value")?null:p.getString("value"));}
    @Override public QuorumLog.Vote accept(int peer,long slot,long ballot,String value) throws Exception {
        JSONObject r=rpc(peer,new JSONObject().put("method","accept").put("slot",slot).put("ballot",ballot).put("value",value));
        if(!r.getBoolean("accepted"))return null;PeerConfig.require(r.getInt("node")==peer&&config.validVote(peer,slot,ballot,value,r.getString("signature")),"Voto inválido.");return new QuorumLog.Vote(peer,r.getString("signature"));
    }
    @Override public void learn(int peer,QuorumLog.Decision d) throws Exception {rpc(peer,new JSONObject().put("method","learn").put("decision",PeerDatabase.encode(d)));}
    void sync(){
        if(shared!=null){shared.sync();return;}
        synchronized(this){if(syncing)return;syncing=true;}
        try {
        int online=1,old=0;String warning="";
        for(int peer=0;peer<4&&!closed;peer++)if(peer!=config.node)try{
            peerStates[peer]="Comprobando…";JSONObject status=rpc(peer,new JSONObject().put("method","status"));long remote=status.getLong("applied");
            for(int page=0;page<32&&db.applied()<remote;page++){
                long before=db.applied();JSONArray items=rpc(peer,new JSONObject().put("method","history").put("protocol",2).put("after",before)).getJSONArray("items");
                for(int i=0;i<items.length();i++)db.learn(PeerDatabase.decode(items.getJSONObject(i)));if(db.applied()==before)break;
            }
            if(status.optInt("protocol",1)!=PROTOCOL){old++;peerStates[peer]="Actualizar APK";continue;}
            online++;
            for(int page=0;page<32&&!closed;page++){
                JSONArray items=rpc(peer,new JSONObject().put("method","events").put("after",offline.vector())).getJSONArray("items");
                if(items.length()==0)break;offline.merge(items);
            }
            JSONArray remoteVector=status.getJSONArray("vector");
            for(int page=0;page<32&&!closed;page++){
                JSONArray items=offline.events(remoteVector,20);if(items.length()==0)break;
                remoteVector=rpc(peer,new JSONObject().put("method","merge").put("items",items)).getJSONArray("vector");
            }
            offline.acknowledge(peer,remoteVector);peerStates[peer]="Conectado · intercambio completado";
        }catch(IllegalArgumentException e){peerStates[peer]="Configuración o historial incompatible";warning=" · Hay registros incompatibles; revisa las identidades de los equipos.";}
         catch(Exception error){peerStates[peer]=error instanceof java.net.SocketTimeoutException?"Sin respuesta; revisa internet o la app":error instanceof java.net.UnknownHostException?"No se pudo resolver la dirección":error instanceof IOException&&error.getMessage()!=null&&error.getMessage().startsWith("HTTP ")?error.getMessage():"Sin conexión o acceso rechazado";}
        connected=online;
        try{state=online+"/4 teléfonos disponibles · "+offline.pending()+" entregas locales pendientes de llegar a todos"+(old>0?" · "+old+" requieren actualizar APK":"")+warning;}
        catch(Exception e){state="Registro local disponible. No se pudo consultar la sincronización.";}
        }finally{syncing=false;}
    }
    JSONObject request(JSONObject original) throws Exception {
        if(LicensingController.mutation(original)&&!license.allowed())return LicensingController.denied();
        String action=original.getString("action");
        if(PeerCards.local(action))return PeerCards.request(db,config,original,System.currentTimeMillis());
        if(!action.equals("scan"))throw new IllegalArgumentException("Los encargados se configuran al preparar la red.");
        JSONObject request=new JSONObject(original.toString());request.remove("token");
        if(!request.has("requestId"))request.put("requestId",UUID.randomUUID().toString());
        return shared!=null?shared.scan(request):offline.scan(request,System.currentTimeMillis());
    }
    static JSONObject replyForAttempt(JSONObject saved,String command) throws Exception {
        JSONObject reply=new JSONObject(saved.getJSONObject("reply").toString());
        if((command==null||!saved.getString("commandHash").equals(PeerConfig.hash(command)))&&reply.optString("status").equals("accepted"))reply.put("status","recovered").put("message","Registro anterior confirmado. No entregar otra ración.");
        return reply;
    }
    @Override public void close(){closed=true;try{if(server!=null)server.close();}catch(IOException ignored){}clients.shutdownNow();/* SQLite is kept open while already running requests unwind. */}
}
