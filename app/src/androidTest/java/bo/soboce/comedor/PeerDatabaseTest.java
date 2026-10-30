package bo.soboce.comedor;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.net.*;
import java.util.regex.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PeerDatabaseTest {
    Context context;PeerDatabase db;PeerConfig config;PeerConfig[] identities;
    @Before public void setup() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();context.deleteDatabase("comedor-peers.db");
        java.security.KeyPairGenerator generator=java.security.KeyPairGenerator.getInstance("EC");generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));java.security.KeyPair[] pairs=new java.security.KeyPair[4];for(int i=0;i<4;i++)pairs[i]=generator.generateKeyPair();
        JSONArray nodes=new JSONArray(),plants=new JSONArray();for(int i=0;i<4;i++){plants.put("Planta "+i);nodes.put(new JSONObject().put("id",i).put("publicKey",Base64.getEncoder().encodeToString(pairs[i].getPublic().getEncoded())).put("name","Encargado "+i).put("plant","Planta "+i).put("role",i==0?"admin":"encargado").put("url","https://node"+i+".example.com"));}
        JSONObject p=new JSONObject().put("id","SAP-12345").put("sap","12345").put("nombre","Persona de prueba").put("planta","Planta 0").put("centroCosto","TEST").put("tipo","EMPLEADO").put("activo",true).put("qrHash",PeerConfig.hash("SB1:"+"a".repeat(64))).put("vence","").put("comidas","desayuno,almuerzo,cena");
        String seed=new JSONObject().put("people",new JSONArray().put(p)).put("plants",plants).put("rates",new JSONArray().put(new JSONObject().put("desde","2020-01-01").put("desayuno",12).put("almuerzo",23).put("cena",19))).toString();
        String manifest=new JSONObject().put("seedHash",PeerConfig.hash(seed)).put("nodes",nodes).toString();
        String raw=new JSONObject().put("format","SOBOCE-P2P-2").put("manifest",manifest).put("seed",seed).put("identityPrivate",Base64.getEncoder().encodeToString(pairs[0].getPrivate().getEncoded())).put("node",0).put("tunnel",new JSONObject().put("TunnelID","00000000-0000-0000-0000-000000000000").put("TunnelSecret","test-only").put("AccountTag","test-only")).toString();identities=new PeerConfig[4];for(int i=0;i<4;i++)identities[i]=new PeerConfig(new JSONObject(raw).put("node",i).put("identityPrivate",Base64.getEncoder().encodeToString(pairs[i].getPrivate().getEncoded())).toString());config=identities[0];db=new PeerDatabase(context,config);
    }
    @After public void cleanup(){db.close();context.deleteDatabase("comedor-peers.db");}
    String scan(int actor,String plant,String meal,String request) throws Exception {return new JSONObject().put("actor",actor).put("time",1793361600000L).put("request",new JSONObject().put("action","scan").put("plant",plant).put("meal",meal).put("qr","SB1:"+"a".repeat(64)).put("requestId",request)).toString();}
    String commit(long slot,String body) throws Exception {JSONObject command=new JSONObject(body);String value=identities[command.getInt("actor")].seal(command);List<QuorumLog.Vote> votes=new ArrayList<>();for(int i=0;i<3;i++)votes.add(new QuorumLog.Vote(i,identities[i].vote(i,slot,4,value)));db.learn(new QuorumLog.Decision(slot,4,value,votes));return value;}
    @Test public void duplicateAcrossPlantsAndRetry() throws Exception {
        String id=UUID.randomUUID().toString(),another=UUID.randomUUID().toString();String first=scan(0,"Planta 0","almuerzo",id);
        commit(1,first);commit(2,scan(1,"Planta 1","almuerzo",another));commit(3,first);
        assertEquals(1,db.rows("meals").size());assertEquals("accepted",db.get("results",id).getJSONObject("reply").getString("status"));assertEquals("duplicate",db.get("results",another).getJSONObject("reply").getString("status"));
        commit(4,scan(0,"Planta 0","cena",UUID.randomUUID().toString()));assertEquals(2,db.rows("meals").size());
    }
    @Test public void persistedPromisesAfterRestart() throws Exception {
        String value=config.seal(new JSONObject(scan(0,"Planta 0","almuerzo",UUID.randomUUID().toString())));db.prepare(1,44);assertNotNull(db.accept(1,44,value));db.close();db=new PeerDatabase(context,config);
        assertFalse(db.prepare(1,40).granted());assertEquals(value,db.prepare(1,48).value());assertNull(db.accept(1,44,value));
    }
    @Test public void aLostResponseRecoveredDuringCatchupDoesNotAuthorizeAgain() throws Exception {
        String id=UUID.randomUUID().toString();String first=scan(0,"Planta 0","almuerzo",id);
        String retry=new JSONObject(first).put("attempt","different-attempt").toString();
        String firstSealed=commit(1,first),retrySealed=commit(2,retry);
        JSONObject saved=db.get("results",id);
        assertEquals("accepted",PeerEngine.replyForAttempt(saved,firstSealed).getString("status"));
        assertEquals("recovered",PeerEngine.replyForAttempt(saved,retrySealed).getString("status"));
        assertEquals("recovered",PeerEngine.replyForAttempt(saved,null).getString("status"));
        assertEquals(1,db.rows("meals").size());
    }
    @Test public void rejectsFakeMajority() throws Exception {
        String value=config.seal(new JSONObject(scan(0,"Planta 0","almuerzo",UUID.randomUUID().toString())));
        try{db.learn(new QuorumLog.Decision(1,4,value,List.of(new QuorumLog.Vote(0,config.vote(0,1,4,value)))));fail("Minority accepted");}catch(IllegalArgumentException expected){}
        assertEquals(0,db.applied());assertEquals(0,db.rows("meals").size());
    }
    @Test public void visitorRenewalPreservesIdentityAndMealLimit() throws Exception {
        String today=MealLedger.day(1793361600000L),firstQr="SB1:"+"c".repeat(64),secondQr="SB1:"+"d".repeat(64);
        JSONObject visitor=new JSONObject().put("action","visitor").put("plant","Planta 1").put("name","Visitante de prueba").put("document","CI BO 1234567").put("costCenter","TEST").put("expires",today).put("meals","almuerzo").put("newQr",firstQr).put("requestId",UUID.randomUUID().toString());
        commit(1,new JSONObject().put("actor",1).put("time",1793361600000L).put("request",visitor).toString());
        JSONObject meal=new JSONObject(scan(1,"Planta 1","almuerzo",UUID.randomUUID().toString()));meal.getJSONObject("request").put("qr",firstQr);commit(2,meal.toString());
        visitor.put("newQr",secondQr).put("requestId",UUID.randomUUID().toString());commit(3,new JSONObject().put("actor",1).put("time",1793361600000L).put("request",visitor).toString());
        String retry=UUID.randomUUID().toString();meal=new JSONObject(scan(1,"Planta 1","almuerzo",retry));meal.getJSONObject("request").put("qr",secondQr);commit(4,meal.toString());
        assertEquals("duplicate",db.get("results",retry).getJSONObject("reply").getString("status"));assertEquals(1,db.rows("meals").size());
        String revoked=UUID.randomUUID().toString();meal=new JSONObject(scan(1,"Planta 1","almuerzo",revoked));meal.getJSONObject("request").put("qr",firstQr);commit(5,meal.toString());assertFalse(db.get("results",revoked).getJSONObject("reply").getBoolean("ok"));
        String wrongPlant=UUID.randomUUID().toString();meal=new JSONObject(scan(2,"Planta 2","almuerzo",wrongPlant));meal.getJSONObject("request").put("qr",secondQr);commit(6,meal.toString());assertFalse(db.get("results",wrongPlant).getJSONObject("reply").getBoolean("ok"));
        assertEquals(2,db.rows("people").size());assertEquals(1,db.rows("meals").size());
    }
    @Test public void cannotImpersonateAnotherSupervisor() throws Exception {
        try{new PeerConfig(new JSONObject(config.raw.toString()).put("node",1).toString());fail("Wrong private identity accepted");}catch(IllegalArgumentException expected){}
        String body=scan(0,"Planta 0","almuerzo",UUID.randomUUID().toString());
        String forged=new JSONObject().put("body",body).put("signature",identities[1].sign("command|"+config.cluster+"|"+body)).toString();
        try{db.accept(1,4,forged);fail("Forged administrator accepted");}catch(IllegalArgumentException expected){}
        assertNull(db.prepare(1,8).value());assertEquals(0,db.applied());
    }
    @Test public void packagedConnectorRunsOnAndroid() throws Exception {
        File binary=new File(context.getApplicationInfo().nativeLibraryDir,"libcloudflared.so");assertTrue(binary.canExecute());
        Process p=new ProcessBuilder(binary.getAbsolutePath(),"--version").redirectErrorStream(true).start();
        try{assertTrue("Connector did not exit",p.waitFor(20,TimeUnit.SECONDS));ByteArrayOutputStream output=new ByteArrayOutputStream();try(InputStream in=p.getInputStream()){byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1)output.write(b,0,n);}assertEquals(output.toString("UTF-8"),0,p.exitValue());assertTrue(output.toString("UTF-8").contains("2026.10.6"));}finally{p.destroy();}
    }
    @Test public void androidConnectorReachesOriginThroughCloudflare() throws Exception {
        // Ephemeral tunnel with synthetic data only; no production credentials in CI.
        File binary=new File(context.getApplicationInfo().nativeLibraryDir,"libcloudflared.so");
        ServerSocket origin=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        Thread responder=new Thread(()->{while(!origin.isClosed())try(Socket s=origin.accept()){
            s.setSoTimeout(3000);InputStream in=s.getInputStream();int tail=0,b,count=0;while((b=in.read())!=-1&&count++<8192){tail=(tail<<8)|b;if(tail==0x0d0a0d0a)break;}
            byte[] body="SOBOCE-CONNECTOR-TEST".getBytes(StandardCharsets.UTF_8);OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
        }catch(IOException ignored){}});responder.setDaemon(true);responder.start();
        ProcessBuilder pb=new ProcessBuilder(binary.getAbsolutePath(),"tunnel","--no-autoupdate","--protocol","http2","--url","http://127.0.0.1:"+origin.getLocalPort());pb.environment().put("HOME",context.getCacheDir().getAbsolutePath());pb.redirectErrorStream(true);Process process=pb.start();
        AtomicReference<String> address=new AtomicReference<>();StringBuffer diagnostic=new StringBuffer();
        Thread logs=new Thread(()->{try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){String line;Pattern pattern=Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");while((line=reader.readLine())!=null){Matcher m=pattern.matcher(line);if(m.find())address.set(m.group());synchronized(diagnostic){if(diagnostic.length()>5000)diagnostic.delete(0,diagnostic.length()-3000);diagnostic.append(line).append('\n');}}}catch(IOException ignored){}});logs.setDaemon(true);logs.start();
        boolean reached=false;long deadline=System.currentTimeMillis()+120000;
        try{
            while(System.currentTimeMillis()<deadline&&process.isAlive()){
                if(address.get()!=null){HttpURLConnection c=(HttpURLConnection)new URL(address.get()+"/probe").openConnection();c.setConnectTimeout(3000);c.setReadTimeout(3000);
                    try{if(c.getResponseCode()==200){ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){int b;while((b=in.read())!=-1&&bytes.size()<100)bytes.write(b);}if(bytes.toString("UTF-8").equals("SOBOCE-CONNECTOR-TEST")){reached=true;break;}}}catch(IOException ignored){}finally{c.disconnect();}}
                Thread.sleep(1500);
            }
            assertTrue("Android tunnel did not reach its origin: "+diagnostic,reached);
        }finally{process.destroy();origin.close();}
    }
}
