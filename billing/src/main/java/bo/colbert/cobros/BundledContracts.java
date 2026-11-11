package bo.colbert.cobros;

import bo.colbert.licensing.*;
import org.json.*;
import java.util.*;

/** An owner APK update adds contracts without resetting prices, paid periods or signing keys. */
final class BundledContracts {
    static JSONObject apply(BillingDb db,SecretStore vault,JSONObject current,JSONObject bundled,long now)throws Exception{
        LicenseProtocol.require(bundled.getInt("version")==1&&current.getInt("version")==1,"La configuración incluida pertenece a otro servidor. Se conserva el servidor actual.");
        for(String field:new String[]{"TunnelID","AccountTag","TunnelSecret"})LicenseProtocol.require(current.getJSONObject("tunnel").getString(field).equals(bundled.getJSONObject("tunnel").getString(field)),"No se cambiará el túnel del servidor existente.");
        JSONArray seeds=bundled.getJSONArray("contracts");Set<String> ids=new HashSet<>();
        // Validate the whole update before writing any contract or encrypted token.
        for(int i=0;i<seeds.length();i++){
            JSONObject seed=seeds.getJSONObject(i);String id=seed.getString("id");LicenseProtocol.require(ids.add(id),"Contrato incluido repetido.");
            LicenseClient.validateConfig(new JSONObject().put("version",1).put("url",bundled.getString("url")).put("contractId",id).put("productId",seed.getString("productId")).put("token",seed.getString("token")));
            LicenseProtocol.require(seed.getString("customer").trim().length()>=2&&seed.getString("customer").length()<=120&&seed.getString("productName").length()<=120,"Cliente incluido inválido.");
            JSONObject old=db.get("contract",id);if(old!=null)LicenseProtocol.require(old.getString("productId").equals(seed.getString("productId"))&&old.getString("customer").equals(seed.getString("customer").trim())&&old.getString("tokenHash").equals(LicenseProtocol.hash(seed.getString("token"))),"El contrato existente tiene otra identidad. No se cambió su permiso ni su clave.");
            String token=vault.get("token:"+id);LicenseProtocol.require(token.isEmpty()||token.equals(seed.getString("token")),"La clave del contrato existente no coincide. Conserva tu respaldo.");
        }
        JSONObject updated=EndpointMigration.merge(current,bundled);JSONArray saved=updated.getJSONArray("contracts");Set<String> savedIds=new HashSet<>();for(int i=0;i<saved.length();i++)savedIds.add(saved.getJSONObject(i).getString("id"));
        db.tx(()->{for(int i=0;i<seeds.length();i++){
            JSONObject seed=seeds.getJSONObject(i);String id=seed.getString("id");vault.put("token:"+id,seed.getString("token"));
            if(db.get("contract",id)==null)db.create(id,seed.getString("customer"),seed.getString("productId"),seed.getString("productName"),seed.getString("token"),now);
            if(savedIds.add(id))saved.put(new JSONObject(seed.toString()));
        }return null;});return updated;
    }
}
