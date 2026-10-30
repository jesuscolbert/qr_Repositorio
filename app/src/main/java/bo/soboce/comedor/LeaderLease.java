package bo.soboce.comedor;

import org.json.*;

/** A conservative monotonic deadline fences an old server even if the wall clock changes. */
final class LeaderLease {
    final String device,boot; private long until; volatile long epoch; volatile String owner="",ownerBoot="";
    LeaderLease(String device){this.device=device;boot=PeerConfig.random();}
    synchronized void update(JSONObject response,long sent,long received) throws Exception {
        long next=response.getLong("epoch"),remaining=response.getLong("remaining");
        PeerConfig.require(next>=epoch&&remaining>=0&&remaining<=30000,"Respuesta de coordinación inválida.");
        epoch=next;owner=response.getString("owner");ownerBoot=response.getString("boot");
        until=sent+Math.max(0,remaining-3000); // Includes network time; never extend from receipt time.
        if(until<=received)until=0;
    }
    synchronized boolean leading(long now){return now<until&&owner.equals(device)&&ownerBoot.equals(boot);}
    synchronized boolean known(long now){return now<until&&!owner.isEmpty();}
    synchronized JSONObject request(String action) throws Exception{return new JSONObject().put("action",action).put("device",device).put("boot",boot).put("epoch",epoch);}
}
