package bo.soboce.comedor;

import android.content.Context;
import bo.colbert.licensing.LicenseClient;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Company selection never rewrites another company's database, credentials or permission. */
final class Companies {
    static JSONObject catalog;static boolean loaded;
    private static final Map<String,LicenseClient> clients=new HashMap<>();
    static synchronized JSONObject catalog(Context c)throws Exception {
        if(!loaded){
            try(InputStream in=c.getAssets().open("companies.json")){
                JSONObject candidate=new JSONObject(new String(LicenseClient.read(in,2097152),StandardCharsets.UTF_8));validate(candidate);catalog=candidate;
            }catch(FileNotFoundException absent){catalog=null;}
            loaded=true;
        }
        return catalog;
    }
    static void validate(JSONObject value)throws Exception {
        PeerConfig.require(value.getString("format").equals("COLBERT-COMPANIES-1"),"Catálogo de empresas incompatible.");
        JSONArray list=value.getJSONArray("companies");Set<String> ids=new HashSet<>(),clusters=new HashSet<>(),keys=new HashSet<>();
        PeerConfig.require(list.length()==2,"Falta una empresa en este instalador.");
        for(int i=0;i<list.length();i++){
            JSONObject item=list.getJSONObject(i);String id=item.getString("id");
            PeerConfig.require((id.equals("soboce")||id.equals("frigor"))&&ids.add(id),"Empresa inválida o repetida.");
            JSONObject network=item.getJSONObject("network"),license=item.getJSONObject("license");LicenseClient.validateConfig(license);
            PeerConfig.require(license.getString("productId").equals(id+"-comedor")&&license.getString("contractId").equals(id+"-comedor"),"El permiso no corresponde a la empresa.");
            PeerConfig.require(network.getString("format").equals("SOBOCE-SHARED-BUNDLE-1")&&clusters.add(PeerConfig.hash(network.getString("manifest")))&&keys.add(network.getString("networkKey")),"Las empresas deben tener redes independientes.");
        }
    }
    static boolean enabled(Context c){try{return catalog(c)!=null;}catch(Exception e){throw new IllegalStateException("No se pudo validar el catálogo de empresas.",e);}}
    static String primary(Context c){return c.getString(R.string.organization_name).equals("FRIGOR")?"frigor":"soboce";}
    static String current(Context c){if(!enabled(c))return primary(c);String id=c.getSharedPreferences("company-selection",0).getString("selected",primary(c));if(!id.equals("soboce")&&!id.equals("frigor"))throw new IllegalStateException("Empresa guardada inválida.");return id;}
    static String name(Context c){return current(c).equals("frigor")?"FRIGOR":"SOBOCE";}
    static String suffix(Context c,String id){PeerConfig.require(id.equals("soboce")||id.equals("frigor"),"Empresa inválida.");return id.equals(primary(c))?"":"-"+id;}
    static JSONObject item(Context c,String id)throws Exception {JSONArray a=catalog(c).getJSONArray("companies");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(id))return a.getJSONObject(i);throw new IllegalArgumentException("Empresa no disponible.");}
    static String company(Context c,PeerConfig config)throws Exception {
        if(!enabled(c))return primary(c);
        for(String id:new String[]{"soboce","frigor"})if(PeerConfig.hash(item(c,id).getJSONObject("network").getString("manifest")).equals(config.cluster))return id;
        throw new IllegalArgumentException("La red no pertenece a una empresa del instalador.");
    }
    static String database(Context c,PeerConfig config)throws Exception {return "comedor-peers"+suffix(c,company(c,config))+".db";}
    static synchronized LicenseClient license(Context c,String id)throws Exception {
        String key=c.getPackageName()+":"+id;LicenseClient client=clients.get(key);
        if(client==null){client=new LicenseClient(c,true,id+"-comedor",item(c,id).getJSONObject("license"),suffix(c,id));clients.put(key,client);}
        return client;
    }
    static LicenseClient license(Context c,PeerConfig config)throws Exception {return enabled(c)?license(c,company(c,config)):LicenseClient.get(c);}
    static void install(Context c)throws Exception {
        if(!enabled(c)){BundledSetup.install(c,new Vault(c));return;}
        for(String id:new String[]{"soboce","frigor"}){
            Vault vault=new Vault(c,id);SharedSetup.apply(vault,item(c,id).getJSONObject("network").toString());
            // This private multi-company installer explicitly authorizes all plants of each company.
            JSONObject config=new JSONObject(vault.get("peerConfig"));
            if(!config.getJSONObject("operator").getString("role").equals("admin")){
                PeerConfig.require(!PeerService.running,"Cierra y vuelve a abrir la app para habilitar todas las plantas.");
                config.getJSONObject("operator").put("role","admin");new PeerConfig(config.toString());vault.put("peerConfig",config.toString());
            }
        }
    }
    static void select(Context c,String id)throws Exception {
        item(c,id);PeerConfig.require(!PeerService.running&&PeerService.engine==null,"Espera a que termine la conexión anterior.");
        if(!c.getSharedPreferences("company-selection",0).edit().putString("selected",id).commit())throw new IOException("No se pudo guardar la empresa.");
    }
    static synchronized void clearClients(){clients.clear();}
}
