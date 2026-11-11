package bo.colbert.licensing;

import java.net.URL;
import org.json.*;

/** A signed installer can advance an endpoint while preserving its existing identity. */
public final class EndpointMigration {
 private EndpointMigration(){}
 public static void validate(String value)throws Exception{
  URL url=new URL(value);
  LicenseProtocol.require(url.getProtocol().equals("https")&&!url.getHost().isEmpty()&&url.getUserInfo()==null&&url.getQuery()==null&&url.getRef()==null&&(url.getPath().isEmpty()||url.getPath().equals("/")),"Dirección de conexión inválida.");
 }
 public static JSONObject merge(JSONObject current,JSONObject bundled)throws Exception{
  String old=current.getString("url"),target=bundled.getString("url");validate(old);validate(target);
  long previous=current.optLong("endpointRevision",0),next=bundled.optLong("endpointRevision",0);
  LicenseProtocol.require(previous>=0&&next>=0,"Revisión de conexión inválida.");
  JSONObject result=new JSONObject(current.toString());
  if(next<previous)return result; // A stale installer cannot restore an expired endpoint.
  if(!old.equals(target)){
   boolean authorized=false;JSONArray from=bundled.optJSONArray("previousUrls");
   if(from!=null)for(int i=0;i<from.length();i++)authorized|=old.equals(from.getString(i));
   LicenseProtocol.require(next>previous&&authorized,"Esta actualización no autoriza cambiar la dirección guardada. Conserva los datos.");
   result.put("url",target);
  }
  if(bundled.has("endpointRevision"))result.put("endpointRevision",next);
  return result;
 }
 public static JSONObject license(JSONObject current,JSONObject bundled)throws Exception{
  LicenseClient.validateConfig(current);LicenseClient.validateConfig(bundled);
  for(String field:new String[]{"version","contractId","productId","token"})
   LicenseProtocol.require(current.get(field).equals(bundled.get(field)),"La licencia incluida pertenece a otro contrato. Se conserva la identidad guardada.");
  return merge(current,bundled);
 }
}
