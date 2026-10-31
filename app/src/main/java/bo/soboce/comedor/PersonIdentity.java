package bo.soboce.comedor;

import org.json.JSONObject;
import java.util.Locale;

/** Display identity without putting document numbers in the SAP field. */
final class PersonIdentity {
    static String label(JSONObject p) {
        String document=p.optString("documento").trim(),sap=p.optString("sap").trim();
        if(!document.isEmpty())return "CI " + document;
        if(!sap.isEmpty())return "SAP " + sap;
        return p.optString("id");
    }
    static boolean matches(JSONObject p,String query) {
        String term=query.trim().toLowerCase(Locale.ROOT);
        return (p.optString("nombre")+" "+p.optString("documento")+" "+p.optString("sap")+" "+p.optString("id")).toLowerCase(Locale.ROOT).contains(term);
    }
    static void requireNewVisitor(Iterable<JSONObject> people,String normalizedDocument) {
        for(JSONObject p:people){
            String ci=p.optString("documento").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");
            if(ci.isEmpty()||p.optString("tipo").equals("VISITA"))continue;
            PeerConfig.require(!normalizedDocument.equals(ci)&&!normalizedDocument.equals("CI"+ci)&&!normalizedDocument.equals("CIBO"+ci),"Este carnet ya pertenece a un trabajador. Usa su credencial existente.");
        }
    }
}
