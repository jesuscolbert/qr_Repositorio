package bo.colbert.obrero;
import org.json.*;
import java.time.*;
import java.util.*;

/** Counts delivered records, not invented points or a nutritional score. */
final class MealSummary {
 static final ZoneId ZONE=ZoneId.of("America/La_Paz");
 final YearMonth month;final LinkedHashMap<String,Integer> meals=new LinkedHashMap<>();final Set<LocalDate> days=new HashSet<>();final JSONArray rows=new JSONArray();int review;
 MealSummary(JSONArray items,long now)throws Exception {
  month=YearMonth.from(Instant.ofEpochMilli(now).atZone(ZONE));for(String meal:new String[]{"desayuno","almuerzo","cena","dieta"})meals.put(meal,0);
  List<JSONObject> valid=new ArrayList<>();for(int i=0;i<items.length();i++){
   JSONObject r=items.getJSONObject(i);LocalDate day=LocalDate.parse(r.getString("date"));
   if(!YearMonth.from(day).equals(month)||day.isAfter(Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate()))continue;
   String meal=r.getString("meal");if(!meals.containsKey(meal))continue;valid.add(r);days.add(day);meals.put(meal,meals.get(meal)+1);if(!r.optString("review").isEmpty())review++;
  }
  valid.sort((a,b)->(b.optString("date")+b.optString("time")).compareTo(a.optString("date")+a.optString("time")));for(JSONObject r:valid)rows.put(r);
 }
}
