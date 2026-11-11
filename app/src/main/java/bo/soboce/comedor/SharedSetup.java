package bo.soboce.comedor;

import android.content.Context;
import org.json.*;
import java.io.*;

/** The same private APK is installed everywhere; identity is generated once on each phone. */
final class SharedSetup {
    static final String ASSET="comedor-shared.json";
    static boolean install(Context context,Vault vault) throws Exception {
        String text;
        try(InputStream in=context.getAssets().open(ASSET)){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1){if(out.size()+n>2097152)throw new IOException("Instalador inválido.");out.write(b,0,n);}text=out.toString("UTF-8");
        }catch(FileNotFoundException absent){return false;}
        apply(vault,text);return true;
    }
    static void apply(Vault vault,String asset) throws Exception {
        JSONObject bundle=new JSONObject(asset);PeerConfig.require(bundle.getString("format").equals("SOBOCE-SHARED-BUNDLE-1"),"Instalador incompatible.");
        PeerConfig old=vault.get("peerConfig").isEmpty()?null:new PeerConfig(vault.get("peerConfig"));
        if(old!=null){
            PeerConfig.require(old.cluster.equals(PeerConfig.hash(bundle.getString("manifest"))),"Conserva tus datos: este instalador pertenece a otra red.");
            if(old.shared){
                for(String field:new String[]{"networkKey","seed","coordinator"})PeerConfig.require(old.raw.getString(field).equals(bundle.getString(field)),"Esta actualización cambia la identidad de la red. Conserva los datos.");
                for(String field:new String[]{"TunnelID","AccountTag","TunnelSecret"})PeerConfig.require(old.raw.getJSONObject("tunnel").getString(field).equals(bundle.getJSONObject("tunnel").getString(field)),"El túnel incluido pertenece a otra red.");
                JSONObject migrated=bo.colbert.licensing.EndpointMigration.merge(old.raw,bundle);new PeerConfig(migrated.toString());
                if(!migrated.getString("url").equals(old.raw.getString("url"))||migrated.optLong("endpointRevision")!=old.raw.optLong("endpointRevision")){
                    PeerConfig.require(PeerService.engine==null,"Cierra completamente la app y vuelve a abrirla para actualizar la dirección de la red.");
                    vault.put("peerConfig",migrated.toString());
                }
                return;
            }
        }
        PeerConfig.require(PeerService.engine==null,"Cierra completamente la app y vuelve a abrirla para actualizar la red.");
        String device=vault.get("sharedDevice");if(device.isEmpty()){device=PeerConfig.random();vault.put("sharedDevice",device);}
        JSONObject op=old==null?new JSONObject().put("name","Encargado "+device.substring(0,6)).put("plant",new JSONObject(bundle.getString("seed")).getJSONArray("plants").getString(0)).put("role","encargado"):old.operator(old.node);
        bundle.put("format","SOBOCE-SHARED-1").put("device",device).put("node",old==null?-1:old.node).put("operator",op);
        new PeerConfig(bundle.toString());
        if(old!=null)vault.put("legacyPeerConfig",old.raw.toString());
        vault.put("peerConfig",bundle.toString());vault.put("mode","p2p");if(old==null)vault.put("needsProfile","yes");
    }
}
