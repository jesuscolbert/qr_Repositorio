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
    JSONObject workerRequest(String plant,String id) throws Exception {return new JSONObject().put("action","issue").put("plant",plant).put("personId",id);}
    JSONObject visitorRequest(String plant,long time) throws Exception {return new JSONObject().put("action","visitor").put("plant",plant).put("name","Visita sin conexión").put("document","CI BO 1234567").put("costCenter","TEST").put("expires",MealLedger.day(time)).put("meals","almuerzo");}
    String consumeCard(int actor,String plant,String meal,String qr,long when) throws Exception {
        String id=UUID.randomUUID().toString();JSONObject command=new JSONObject(scan(actor,plant,meal,id)).put("time",when);command.getJSONObject("request").put("qr",qr);commit(db.applied()+1,command.toString());return id;
    }
    @Test public void supervisorIssuesOwnPlantOfflineWithoutChangingOldQrOrLog() throws Exception {
        JSONObject person=new JSONObject(db.get("people","SAP-12345").toString()).put("id","SAP-67890").put("sap","67890").put("planta","Planta 1");db.save("people","SAP-67890",person);
        JSONObject card=PeerCards.request(db,identities[1],workerRequest("Planta 1","SAP-67890"),1793361600000L);
        assertTrue(card.getBoolean("ok"));assertTrue(PeerCards.format(card.getString("qr")));
        assertEquals("SAP-67890",PeerCards.resolve(db,config,card.getString("qr"),1793361600000L).getString("id"));
        assertEquals(0,db.applied());assertEquals(0,db.history(0,20).length());
        assertEquals(person.getString("qrHash"),db.get("people","SAP-67890").getString("qrHash"));
        assertFalse(PeerCards.request(db,identities[1],workerRequest("Planta 0","SAP-12345"),1793361600000L).getBoolean("ok"));
    }
    @Test public void cardsWorkWithNetworkEngineStopped() throws Exception {
        PeerEngine offline=new PeerEngine(context,config);offline.close();
        try {
            assertTrue(offline.request(workerRequest("Planta 0","SAP-12345")).getBoolean("ok"));
            assertTrue(offline.request(visitorRequest("Planta 0",System.currentTimeMillis())).getBoolean("ok"));
            assertEquals(0,offline.db.applied());
        } finally {offline.db.close();}
    }
    @Test public void newCardsCopiesAndLegacyQrShareTheSameMealLimit() throws Exception {
        long now=1793361600000L;
        String first=PeerCards.request(db,config,workerRequest("Planta 0","SAP-12345"),now).getString("qr");
        String copy=PeerCards.request(db,config,workerRequest("Planta 0","SAP-12345"),now).getString("qr");
        String a=consumeCard(1,"Planta 1","almuerzo",first,now),b=consumeCard(0,"Planta 0","almuerzo",copy,now),c=consumeCard(2,"Planta 2","almuerzo","SB1:"+"a".repeat(64),now);
        assertEquals("accepted",db.get("results",a).getJSONObject("reply").getString("status"));
        for(String id:new String[]{b,c})assertEquals("duplicate",db.get("results",id).getJSONObject("reply").getString("status"));
        assertEquals(1,db.rows("meals").size());
    }
    @Test public void offlineVisitorCardsHaveGlobalIdentityAndEnforceHostExpiryAndMeals() throws Exception {
        long now=1793361600000L;
        String atOne=PeerCards.request(db,identities[1],visitorRequest("Planta 1",now),now).getString("qr");
        String atZero=PeerCards.request(db,config,visitorRequest("Planta 0",now).put("document","ci-bo-1234567"),now).getString("qr");
        assertEquals(1,db.rows("people").size()); // Issuing alone does not change the shared projection.
        String accepted=consumeCard(1,"Planta 1","almuerzo",atOne,now),duplicate=consumeCard(0,"Planta 0","almuerzo",atZero,now);
        assertEquals("accepted",db.get("results",accepted).getJSONObject("reply").getString("status"));
        assertEquals("duplicate",db.get("results",duplicate).getJSONObject("reply").getString("status"));
        for(String id:new String[]{consumeCard(0,"Planta 0","almuerzo",atOne,now),consumeCard(1,"Planta 1","cena",atOne,now),consumeCard(1,"Planta 1","almuerzo",atOne,now+86400000L)})assertFalse(db.get("results",id).getJSONObject("reply").getBoolean("ok"));
        assertEquals(1,db.rows("meals").size());
    }
    @Test public void tamperedForeignAndOutOfScopeSignedCardsAreRejected() throws Exception {
        long now=1793361600000L;String qr=PeerCards.request(db,config,workerRequest("Planta 0","SAP-12345"),now).getString("qr");
        String[] parts=qr.substring(4).split("\\.");JSONObject body=new JSONObject(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8));body.put("id","SAP-99999");
        String tampered="SB2:"+Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().getBytes(StandardCharsets.UTF_8))+"."+parts[1];
        assertFalse(db.get("results",consumeCard(0,"Planta 0","almuerzo",tampered,now)).getJSONObject("reply").getBoolean("ok"));
        body.put("id","SAP-12345").put("issuer",1);String text=body.toString();String outOfScope="SB2:"+Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8))+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getDecoder().decode(identities[1].sign("card|"+config.cluster+"|"+text)));
        assertFalse(db.get("results",consumeCard(0,"Planta 0","almuerzo",outOfScope,now)).getJSONObject("reply").getBoolean("ok"));
        JSONObject manifest=new JSONObject(config.raw.getString("manifest")).put("otherNetwork",true);
        PeerConfig foreign=new PeerConfig(new JSONObject(config.raw.toString()).put("manifest",manifest.toString()).toString());
        String other=PeerCards.request(db,foreign,workerRequest("Planta 0","SAP-12345"),now).getString("qr");
        assertFalse(db.get("results",consumeCard(0,"Planta 0","almuerzo",other,now)).getJSONObject("reply").getBoolean("ok"));assertEquals(0,db.rows("meals").size());
    }
    @Test public void updateReplaysHistoryAndRetainsAcceptedVotes() throws Exception {
        long now=1793361600000L;consumeCard(0,"Planta 0","almuerzo","SB1:"+"a".repeat(64),now);
        String qr=PeerCards.request(db,config,workerRequest("Planta 0","SAP-12345"),now).getString("qr");String id=consumeCard(0,"Planta 0","cena",qr,now);
        String pending=config.seal(new JSONObject(scan(0,"Planta 0","desayuno",UUID.randomUUID().toString())));db.prepare(3,100);db.accept(3,100,pending);long oldBallot=db.nextBallot(0,100);
        db.getWritableDatabase().delete("meals",null,null);db.getWritableDatabase().delete("results",null,null);db.getWritableDatabase().setVersion(1);db.close();
        db=new PeerDatabase(context,config);
        assertEquals(2,db.applied());assertEquals(2,db.rows("meals").size());assertEquals("accepted",db.get("results",id).getJSONObject("reply").getString("status"));
        assertFalse(db.prepare(3,96).granted());assertEquals(pending,db.prepare(3,104).value());assertTrue(db.nextBallot(0,oldBallot)>oldBallot);
    }
    @Test public void olderProposersCannotFormAMixedVersionQuorum() throws Exception {
        PeerEngine engine=new PeerEngine(context,config);
        try{
            assertEquals(3,engine.dispatch(new JSONObject().put("method","status")).getInt("protocol"));
            try{engine.dispatch(new JSONObject().put("method","prepare").put("slot",1).put("ballot",4));fail("Old proposer received a promise");}catch(IllegalArgumentException expected){}
            try{engine.dispatch(new JSONObject().put("method","prepare").put("protocol",2).put("slot",1).put("ballot",4));fail("Legacy vote accepted");}catch(IllegalArgumentException expected){}
            assertEquals(0,engine.dispatch(new JSONObject().put("method","events").put("protocol",3).put("after",new JSONArray("[0,0,0,0]"))).getJSONArray("items").length());
        }finally{engine.close();engine.db.close();}
    }
    @Test public void offlineVisitorQrCanBeReadByTheScanner() throws Exception {
        String qr=PeerCards.request(db,identities[1],visitorRequest("Planta 1",1793361600000L).put("name","Visitante de prueba con nombre completo"),1793361600000L).getString("qr");
        com.google.zxing.common.BitMatrix matrix=new com.google.zxing.MultiFormatWriter().encode(qr,com.google.zxing.BarcodeFormat.QR_CODE,700,700);int[] pixels=new int[700*700];
        for(int y=0;y<700;y++)for(int x=0;x<700;x++)pixels[y*700+x]=matrix.get(x,y)?0xff000000:0xffffffff;
        com.google.zxing.BinaryBitmap image=new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(700,700,pixels)));
        assertEquals(qr,new com.google.zxing.qrcode.QRCodeReader().decode(image).getText());
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

    JSONObject offlineRequest(int actor,String meal) throws Exception {return new JSONObject(scan(actor,"Planta "+actor,meal,UUID.randomUUID().toString())).getJSONObject("request");}
    @Test public void singlePhoneRecordsAllMealsOfflineAndRecoversAfterRestart() throws Exception {
        long now=1793361600000L;OfflineLedger ledger=new OfflineLedger(db,config);JSONObject lunch=offlineRequest(0,"almuerzo");
        for(String meal:new String[]{"desayuno","almuerzo","cena"})assertEquals("accepted",ledger.scan(meal.equals("almuerzo")?lunch:offlineRequest(0,meal),now).getString("status"));
        assertEquals(3,ledger.records().size());assertEquals(0,db.applied());assertEquals(3,ledger.pending());
        db.close();db=new PeerDatabase(context,config);ledger=new OfflineLedger(db,config);
        assertEquals("recovered",ledger.scan(lunch,now).getString("status"));
        assertEquals("duplicate",ledger.scan(offlineRequest(0,"almuerzo"),now).getString("status"));
        assertEquals(3,ledger.records().size());
        JSONObject future=ledger.scan(offlineRequest(0,"almuerzo"),now+86400000L);assertEquals("accepted",future.getString("status"));
        assertFalse(ledger.scan(offlineRequest(0,"cena"),now).getBoolean("ok"));
        try{ledger.scan(new JSONObject(lunch.toString()).put("meal","cena"),now);fail("Request ID reused");}catch(IllegalArgumentException expected){}
    }
    @Test public void engineCanRegisterWithNetworkStopped() throws Exception {
        PeerEngine engine=new PeerEngine(context,config);engine.close();
        try{assertEquals("accepted",engine.request(offlineRequest(0,"almuerzo")).getString("status"));assertEquals(1,engine.offline.records().size());}
        finally{engine.db.close();}
    }
    @Test public void knownHistoryAndVisitorRulesStillBlockOfflineDuplicates() throws Exception {
        long now=1793361600000L;String id=UUID.randomUUID().toString();commit(1,scan(0,"Planta 0","almuerzo",id));
        OfflineLedger ledger=new OfflineLedger(db,config);
        assertEquals("duplicate",ledger.scan(offlineRequest(0,"almuerzo"),now).getString("status"));
        JSONObject recovered=new JSONObject(scan(0,"Planta 0","almuerzo",id)).getJSONObject("request");
        assertEquals("recovered",ledger.scan(recovered,now).getString("status"));
        String qr=PeerCards.request(db,config,visitorRequest("Planta 0",now),now).getString("qr");
        assertFalse(ledger.scan(offlineRequest(0,"cena").put("qr",qr),now).getBoolean("ok"));
        assertFalse(ledger.scan(offlineRequest(0,"almuerzo").put("plant","Planta 1").put("qr",qr),now).getBoolean("ok"));
        assertEquals("accepted",ledger.scan(offlineRequest(0,"almuerzo").put("qr",qr),now).getString("status"));
        assertFalse(ledger.scan(offlineRequest(0,"almuerzo").put("qr",qr),now+86400000L).getBoolean("ok"));
    }
    @Test public void simultaneousScansInTwoDatabaseConnectionsAuthorizeOnlyOnce() throws Exception {
        PeerDatabase second=new PeerDatabase(context,config);
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try{
            java.util.concurrent.Future<String> a=pool.submit(()->{start.await();return new OfflineLedger(db,config).scan(offlineRequest(0,"almuerzo"),1793361600000L).getString("status");});
            java.util.concurrent.Future<String> b=pool.submit(()->{start.await();return new OfflineLedger(second,config).scan(offlineRequest(0,"almuerzo"),1793361600000L).getString("status");});
            start.countDown();List<String> replies=Arrays.asList(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertTrue(replies.contains("accepted"));assertTrue(replies.contains("duplicate"));assertEquals(1,new OfflineLedger(db,config).records().size());
        }finally{pool.shutdownNow();second.close();}
    }
    @Test public void signedReplicationIsIdempotentAtomicAndRejectsAlteredReceipts() throws Exception {
        long now=1793361600000L;OfflineLedger origin=new OfflineLedger(db,config);
        origin.scan(offlineRequest(0,"almuerzo"),now);origin.scan(offlineRequest(0,"cena"),now);
        JSONArray events=origin.events(new JSONArray("[0,0,0,0]"),20);
        context.deleteDatabase("offline-test.db");PeerDatabase other=new PeerDatabase(context,identities[1],"offline-test.db");
        try{
            OfflineLedger remote=new OfflineLedger(other,identities[1]);
            try{remote.merge(new JSONArray().put(events.getString(1)));fail("Missing predecessor accepted");}catch(IllegalArgumentException expected){}
            JSONObject envelope=new JSONObject(events.getString(1));JSONObject body=new JSONObject(envelope.getString("body"));body.getJSONObject("record").put("precio",0);envelope.put("body",body.toString());
            try{remote.merge(new JSONArray().put(events.getString(0)).put(envelope.toString()));fail("Altered receipt accepted");}catch(IllegalArgumentException expected){}
            assertEquals(0,remote.records().size());assertEquals(0,remote.vector().getLong(0));
            remote.merge(events);remote.merge(events);assertEquals(2,remote.records().size());
            assertEquals("duplicate",remote.scan(offlineRequest(1,"almuerzo"),now).getString("status"));
            JSONObject divergent=config.command(events.getString(0));divergent.getJSONObject("record").put("nombre","Nombre cambiado");
            try{remote.merge(new JSONArray().put(config.seal(divergent)));fail("Forked stream accepted");}catch(IllegalArgumentException expected){}
            assertEquals(2,remote.records().size());
        }finally{other.close();context.deleteDatabase("offline-test.db");}
    }
    @Test public void disconnectedPhonesConvergeAndPreserveBothConflictingDeliveries() throws Exception {
        PeerEngine[] engines=new PeerEngine[4];boolean[] available={true,true,false,false};
        for(int node=0;node<4;node++){
            context.deleteDatabase("sync-test-"+node+".db");
            engines[node]=new PeerEngine(context,identities[node],"sync-test-"+node+".db"){
                @Override JSONObject rpc(int peer,JSONObject request) throws Exception {
                    if(!available[config.node]||!available[peer])throw new IOException("Offline simulation");
                    JSONObject wire=new JSONObject(request.toString());if(!wire.has("protocol"))wire.put("protocol",PROTOCOL);
                    return new JSONObject(engines[peer].dispatch(wire).toString());
                }
            };
        }
        try{
            long now=1793361600000L;
            assertEquals("accepted",engines[0].offline.scan(offlineRequest(0,"almuerzo"),now).getString("status"));
            assertEquals("accepted",engines[1].offline.scan(offlineRequest(1,"almuerzo"),now).getString("status"));
            engines[1].sync();assertEquals(2,engines[0].offline.records().size());assertEquals(2,engines[1].offline.records().size());
            available[0]=false;available[2]=true;available[3]=true;engines[2].sync();engines[3].sync();
            assertEquals(2,engines[3].offline.records().size()); // Node 0's receipt was relayed while node 0 was off.
            for(PeerEngine e:engines){
                assertEquals(2,e.offline.records().size());for(JSONObject r:e.offline.records())assertEquals("POSIBLE DUPLICADO",r.getString("revision"));
                assertEquals("duplicate",e.offline.scan(offlineRequest(e.config.node,"almuerzo"),now).getString("status"));
            }
            JSONObject report=new MealLedger(engines[0].db,identities[0]).read(new JSONObject().put("action","report").put("plant","Planta 0").put("month","2026-10"));
            assertEquals(1,report.getInt("count"));assertTrue(report.getString("csv").contains("POSIBLE DUPLICADO"));
            assertEquals(1,engines[0].offline.pending());available[0]=true;engines[0].sync();assertEquals(0,engines[0].offline.pending());
            String canonical=engines[0].offline.records().toString();for(PeerEngine e:engines)assertEquals(canonical,e.offline.records().toString());
        }finally{for(int i=0;i<4;i++){engines[i].close();engines[i].db.close();context.deleteDatabase("sync-test-"+i+".db");}}
    }
    @Test public void paginatedCatchupResumesAndNewPhoneReceivesOlderDays() throws Exception {
        long now=1793361600000L;OfflineLedger origin=new OfflineLedger(db,config);
        for(int day=0;day<45;day++)assertEquals("accepted",origin.scan(offlineRequest(0,"almuerzo"),now+day*86400000L).getString("status"));
        context.deleteDatabase("page-test.db");PeerDatabase other=new PeerDatabase(context,identities[2],"page-test.db");
        try{
            OfflineLedger remote=new OfflineLedger(other,identities[2]);JSONArray first=origin.events(remote.vector(),20);assertEquals(20,first.length());remote.merge(first);
            other.close();other=new PeerDatabase(context,identities[2],"page-test.db");remote=new OfflineLedger(other,identities[2]);remote.merge(first);
            for(int i=0;i<3;i++){JSONArray page=origin.events(remote.vector(),20);remote.merge(page);}
            assertEquals(45,remote.records().size());assertEquals(45,remote.vector().getLong(0));assertEquals(0,origin.events(remote.vector(),20).length());
        }finally{other.close();context.deleteDatabase("page-test.db");}
    }
    @Test public void upgradeFromVersionTwoKeepsHistoryAndOfflineReceipts() throws Exception {
        long now=1793361600000L;commit(1,scan(0,"Planta 0","desayuno",UUID.randomUUID().toString()));
        String pending=config.seal(new JSONObject(scan(0,"Planta 0","cena",UUID.randomUUID().toString())));db.prepare(2,100);db.accept(2,100,pending);
        db.getWritableDatabase().setVersion(2);db.close();db=new PeerDatabase(context,config);
        assertEquals(1,db.applied());assertEquals(pending,db.prepare(2,104).value());
        OfflineLedger ledger=new OfflineLedger(db,config);assertEquals("duplicate",ledger.scan(offlineRequest(0,"desayuno"),now).getString("status"));
        assertEquals("accepted",ledger.scan(offlineRequest(0,"almuerzo"),now).getString("status"));
        db.close();db=new PeerDatabase(context,config);assertEquals(2,new OfflineLedger(db,config).records().size());
    }


    void clearSetupVault(Vault vault){for(String key:new String[]{"peerConfig","mode","setupPending","pending"})vault.remove(key);}
    @Test public void bundledInstallerConfiguresAnEmptyPhoneWithoutImportingAFile() throws Exception {
        Vault vault=new Vault(context);clearSetupVault(vault);db.close();context.deleteDatabase("comedor-peers.db");
        try{
            BundledSetup.apply(context,vault,identities[1].raw.toString());
            assertEquals(1,new PeerConfig(vault.get("peerConfig")).node);assertEquals("p2p",vault.get("mode"));assertEquals("",vault.get("setupPending"));
            db=new PeerDatabase(context,identities[1]);assertEquals("1",db.meta("node",""));assertEquals(1,db.rows("people").size());
        }finally{clearSetupVault(vault);}
    }
    @Test public void bundledInstallerRepairsDuplicatedIdentityOnlyWhenNoHistoryExists() throws Exception {
        Vault vault=new Vault(context);clearSetupVault(vault);vault.put("peerConfig",config.raw.toString());db.close();
        try{
            BundledSetup.apply(context,vault,identities[1].raw.toString());db=new PeerDatabase(context,identities[1]);
            assertEquals("1",db.meta("node",""));assertEquals(0,db.applied());
            new OfflineLedger(db,identities[1]).scan(offlineRequest(1,"almuerzo"),1793361600000L);db.close();
            try{BundledSetup.apply(context,vault,identities[2].raw.toString());fail("Historical identity changed");}catch(IllegalArgumentException expected){}
            assertEquals(1,new PeerConfig(vault.get("peerConfig")).node);db=new PeerDatabase(context,identities[1]);assertEquals(1,new OfflineLedger(db,identities[1]).records().size());
        }finally{clearSetupVault(vault);}
    }
    @Test public void interruptedBundledSetupResumesWithoutDiscardingData() throws Exception {
        Vault vault=new Vault(context);clearSetupVault(vault);vault.put("peerConfig",config.raw.toString());vault.put("setupPending",identities[1].raw.toString());
        db.putMeta("node","1");db.close();
        try{
            BundledSetup.install(context,vault);assertEquals(1,new PeerConfig(vault.get("peerConfig")).node);assertEquals("p2p",vault.get("mode"));
            vault.put("setupPending",identities[1].raw.toString());vault.remove("mode");BundledSetup.install(context,vault);assertEquals("p2p",vault.get("mode"));
            db=new PeerDatabase(context,identities[1]);assertEquals(1,db.rows("people").size());
        }finally{clearSetupVault(vault);}
    }
    @Test public void tunnelDiagnosticsDoNotExposeCredentialLines() throws Exception {
        TunnelHealth health=new TunnelHealth();health.line("ERR Unauthorized secret=DO-NOT-DISPLAY");assertFalse(health.message().contains("DO-NOT-DISPLAY"));
        health.line("INF Registered tunnel connection connIndex=0 connection=private-value");assertTrue(health.message().contains("1 conexiones"));assertFalse(health.message().contains("private-value"));
        health.line("ERR Connection terminated connIndex=0");assertFalse(health.message().contains("Túnel conectado"));health.exited(1);assertTrue(health.message().contains("código 1"));
        assertFalse(PeerEngine.duplicateIdentity("one",new JSONObject().put("instance","two")).isEmpty());assertTrue(PeerEngine.duplicateIdentity("one",new JSONObject().put("instance","one")).isEmpty());
    }
    @Test public void publicHealthEndpointAndSignedRpcReachTheActualServer() throws Exception {
        PeerEngine engine=new PeerEngine(context,config);engine.start();
        try{
            try(Socket socket=new Socket("127.0.0.1",8787)){
                socket.setSoTimeout(5000);socket.getOutputStream().write("GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(response.startsWith("HTTP/1.1 200"));assertTrue(response.contains("SOBOCE Comedor"));assertFalse(response.contains("identityPrivate"));
            }
            String nonce=PeerConfig.random(),payload=new JSONObject().put("method","status").put("protocol",3).toString();long now=System.currentTimeMillis();
            JSONObject envelope=new JSONObject().put("source",1).put("time",now).put("nonce",nonce).put("payload",payload).put("signature",identities[1].sign("request|"+config.cluster+"|1|"+now+"|"+nonce+"|"+payload));byte[] body=envelope.toString().getBytes(StandardCharsets.UTF_8);
            try(Socket socket=new Socket("127.0.0.1",8787)){
                socket.setSoTimeout(5000);OutputStream out=socket.getOutputStream();out.write(("POST /rpc HTTP/1.1\r\nHost: localhost\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
                String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(response.startsWith("HTTP/1.1 200"));JSONObject reply=new JSONObject(response.substring(response.indexOf("\r\n\r\n")+4));assertTrue(config.verify(0,"reply|"+nonce+"|"+reply.getString("payload"),reply.getString("signature")));assertEquals(engine.instance,new JSONObject(reply.getString("payload")).getString("instance"));
            }
        }finally{engine.close();engine.db.close();}
    }

}
