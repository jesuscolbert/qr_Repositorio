package bo.colbert.cobros;

import bo.colbert.licensing.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Loopback origin, bounded input/concurrency. Only customer status and receipts are public. */
final class BillingHttp implements AutoCloseable {
 interface Signer{JSONObject sign(JSONObject claims)throws Exception;}
 private final BillingDb db;private final Signer signer;private Runnable receiptNotice=()->{};private ServerSocket server;private volatile boolean stopped;
 private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(12));
 private final Map<String,Long> last=new HashMap<>();
 BillingHttp(BillingRuntime runtime){this(runtime.db,runtime::sign);receiptNotice=()->BillingService.notifyReceipt(runtime.context);}
 BillingHttp(BillingDb db,Signer signer){this.db=db;this.signer=signer;}
 void start(int port)throws Exception{server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),16);Thread accept=new Thread(()->{while(!stopped){try{Socket socket=server.accept();try{workers.execute(()->handle(socket));}catch(RejectedExecutionException full){socket.close();}}catch(IOException e){if(!stopped)BillingService.status="Reinicia el servidor de permisos.";}}},"billing-http");accept.setDaemon(true);accept.start();}
 int port(){return server.getLocalPort();}
 static String line(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;while((c=in.read())!=-1){if(c==10)break;if(c!=13)b.write(c);if(b.size()>4096)throw new IOException("Header too long");}if(c==-1&&b.size()==0)throw new EOFException();return b.toString("US-ASCII");}
 private void handle(Socket socket){try(Socket s=socket){s.setSoTimeout(15000);InputStream in=s.getInputStream();String first=line(in);Map<String,String> headers=new HashMap<>();for(int i=0;i<32;i++){String h=line(in);if(h.isEmpty())break;int colon=h.indexOf(':');if(colon<=0||i==31)throw new IOException("Bad headers");String key=h.substring(0,colon).toLowerCase(Locale.ROOT);if(headers.put(key,h.substring(colon+1).trim())!=null)throw new IOException("Duplicate header");}
   String[] request=first.split(" ");if(request.length!=3)throw new IOException("Bad request");String path=request[1];if(request[0].equals("GET")&&path.equals("/health")){reply(s,200,new JSONObject().put("service","colbert-licenses").put("version",1));return;}
   if(!request[0].equals("POST")||!(path.equals("/v1/status")||path.equals("/v1/receipt"))){reply(s,404,new JSONObject().put("error","Ruta no disponible."));return;}
   if(headers.containsKey("transfer-encoding"))throw new IOException("Unsupported transfer encoding");int length=Integer.parseInt(headers.getOrDefault("content-length","-1"));if(length<0||length>3*1024*1024)throw new IOException("Body limit");
   String auth=headers.getOrDefault("authorization","");JSONObject contract;try{contract=db.authenticate(auth.startsWith("Bearer ")?auth.substring(7):"");}catch(Exception unauthorized){reply(s,403,new JSONObject().put("error","No autorizado."));return;}
   String id=contract.getString("id");long now=System.currentTimeMillis();synchronized(last){long before=last.getOrDefault(id,0L);if(now-before<150){reply(s,429,new JSONObject().put("error","Espera un momento."));return;}last.put(id,now);}
   byte[] body=new byte[length];int offset=0,n;while(offset<length&&(n=in.read(body,offset,length-offset))>0)offset+=n;if(offset!=length)throw new EOFException();JSONObject data=new JSONObject(new String(body,StandardCharsets.UTF_8));
   try{JSONObject result=path.equals("/v1/status")?signer.sign(db.status(id,data.getString("nonce"),now)):db.receipt(id,data,now);if(result.optBoolean("newSubmission"))receiptNotice.run();reply(s,200,result);}catch(IllegalArgumentException problem){reply(s,400,new JSONObject().put("error",problem.getMessage()));}
  }catch(Exception ignored){/* No secrets, request bodies or credentials enter logs. */}}
 private void reply(Socket s,int code,JSONObject data)throws IOException{byte[] body=data.toString().getBytes(StandardCharsets.UTF_8);OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 "+code+" Result\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}
 @Override public void close(){stopped=true;try{if(server!=null)server.close();}catch(IOException ignored){}workers.shutdownNow();}
}
