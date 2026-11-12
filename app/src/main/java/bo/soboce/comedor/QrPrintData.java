package bo.soboce.comedor;

import bo.colbert.licensing.LicenseClient;
import org.json.*;
import java.text.Collator;
import java.util.*;

final class QrPrintData {
    static void requirePermission(LicenseClient client){PeerConfig.require(client.allowed(),"El permiso de esta empresa está pausado. Abre Licencia y pagos para generar las credenciales.");}
    static List<String> plants(PeerConfig config)throws Exception {
        JSONObject op=config.operator(-1);List<String> result=new ArrayList<>();JSONArray all=config.seed.getJSONArray("plants");
        for(int i=0;i<all.length();i++)if(op.getString("role").equals("admin")||op.getString("plant").equals(all.getString(i)))result.add(all.getString(i));
        return result;
    }
    static List<QrPrintDocument.Group> snapshot(PeerDatabase db,PeerConfig config,LicenseClient client,String selected,long now)throws Exception {
        requirePermission(client);PeerConfig.require(config.shared,"La impresión por lotes requiere la red de teléfonos actualizada.");
        List<String> allowed=plants(config);PeerConfig.require(selected.isEmpty()||allowed.contains(selected),"Planta no autorizada.");
        Collator col=Collator.getInstance(new Locale("es","BO"));col.setStrength(Collator.PRIMARY);
        List<JSONObject> people=new ArrayList<>(db.rows("people"));people.sort((a,b)->{int c=col.compare(a.optString("nombre"),b.optString("nombre"));return c!=0?c:a.optString("id").compareTo(b.optString("id"));});
        List<QrPrintDocument.Group> groups=new ArrayList<>();
        for(String plant:allowed){
            if(!selected.isEmpty()&&!selected.equals(plant))continue;List<QrPrintDocument.Card> cards=new ArrayList<>();
            for(JSONObject person:people){
                if(!person.optBoolean("activo")||person.optString("tipo").equals("VISITA")||!plant.equals(person.optString("planta")))continue;
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();requirePermission(client);
                JSONObject reply=PeerCards.request(db,config,new JSONObject().put("action","issue").put("plant",plant).put("personId",person.getString("id")),now);
                PeerConfig.require(reply.optBoolean("ok"),reply.optString("error","No se pudo generar una credencial. No se exportó un lote incompleto."));
                cards.add(new QrPrintDocument.Card(person.getString("id"),person.optString("nombre"),PersonIdentity.label(person),reply.getString("qr")));
            }
            if(!cards.isEmpty())groups.add(new QrPrintDocument.Group(plant,cards));
        }
        requirePermission(client);return groups;
    }
}
