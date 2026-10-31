package bo.soboce.comedor;

import org.json.*;
import java.time.*;
import java.util.*;

final class MealReports {
    static JSONObject build(List<JSONObject> records,JSONObject request,String today,boolean allPlants) throws Exception {
        String action=request.getString("action"),period=action.equals("today")?"day":request.optString("period","month");
        String date=action.equals("today")?today:request.optString("date"),month=request.optString("month");
        if(!action.equals("conflicts")){
            PeerConfig.require(period.equals("day")||period.equals("month"),"Período inválido.");
            try{
                if(period.equals("day"))PeerConfig.require(date.matches("\\d{4}-\\d{2}-\\d{2}")&&LocalDate.parse(date).toString().equals(date),"Fecha inválida.");
                else PeerConfig.require(month.matches("\\d{4}-(0[1-9]|1[0-2])")&&YearMonth.parse(month).toString().equals(month),"Mes inválido.");
            }catch(java.time.DateTimeException e){throw new IllegalArgumentException("La fecha seleccionada no existe.");}
        }
        List<JSONObject> selected=new ArrayList<>();JSONObject counts=new JSONObject(),amounts=new JSONObject();for(String meal:Meals.ALL){counts.put(meal,0);amounts.put(meal,0d);}
        double total=0;int conflicts=0,unpriced=0;
        for(JSONObject r:records){
            if(!allPlants&&!r.getString("planta").equals(request.optString("plant")))continue;
            boolean conflict=!r.optString("revision").isEmpty();
            if(action.equals("conflicts")?!conflict:period.equals("day")?!r.getString("fecha").equals(date):!r.getString("fecha").startsWith(month+"-"))continue;
            selected.add(r);boolean pendingPrice=r.optBoolean("precioPendiente");double price=pendingPrice?0:r.getDouble("precio");if(pendingPrice)unpriced++;total+=price;if(conflict)conflicts++;
            String meal=r.getString("comida");counts.put(meal,counts.optInt(meal)+1);amounts.put(meal,amounts.optDouble(meal,0)+price);
        }
        JSONArray items=new JSONArray();int limit=action.equals("conflicts")?500:period.equals("day")?selected.size():100;
        for(int i=selected.size()-1;i>=Math.max(0,selected.size()-limit);i--)items.put(selected.get(i));
        JSONObject result=MealLedger.ok().put("count",selected.size()).put("total",total).put("unpriced",unpriced).put("conflicts",conflicts).put("byMeal",counts).put("amounts",amounts).put("items",items).put("period",period).put("date",date).put("month",month);
        if(action.equals("report")&&!request.optBoolean("preview")){
            String[] cols={"fecha","hora","persona","nombre","comida","planta","operador","precio","centroCosto","registro","origen","revision","precioPendiente"};StringBuilder csv=new StringBuilder("\uFEFF"+String.join(",",cols));
            for(JSONObject r:selected){csv.append("\r\n");for(int i=0;i<cols.length;i++){if(i>0)csv.append(',');String v=cols[i].equals("precio")&&r.optBoolean("precioPendiente")?"":r.optString(cols[i]);if(v.matches("^[=+@-].*"))v="'"+v;csv.append('"').append(v.replace("\"","\"\"")).append('"');}}
            result.put("csv",csv.toString());
        }
        return result;
    }
}
