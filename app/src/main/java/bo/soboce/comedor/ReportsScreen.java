package bo.soboce.comedor;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class ReportsScreen {
    final MainActivity a;final String plant;final boolean all;String period="day",date=MealLedger.day(System.currentTimeMillis());
    LinearLayout page,content;Button calendar;boolean loading;final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());final Runnable refresh=this::load;
    ReportsScreen(MainActivity a,String plant,boolean all){this.a=a;this.plant=plant;this.all=all;render();}
    String month(){return date.substring(0,7);}
    String heading(){return LocalDate.parse(date).format(DateTimeFormatter.ofPattern(period.equals("day")?"dd MMM yyyy":"MMMM yyyy",new Locale("es","BO")));}
    void render(){
        loading=false;handler.removeCallbacks(refresh);
        a.screen("Reportes",all?"Todas las plantas · entregas compartidas":plant);page=a.page;
        LinearLayout segment=Design.row(a);for(String p:new String[]{"day","month"}){Button b=Design.button(a,p.equals("day")?"Diario":"Mensual",period.equals(p)?Design.BLUE:Color.WHITE,period.equals(p)?Color.WHITE:Design.MUTED,()->{if(loading)return;period=p;render();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(0,0,Design.dp(a,8),0);segment.addView(b,lp);}page.addView(segment);Design.space(page,14);
        calendar=Design.button(a,heading()+"  ▾",Color.WHITE,Design.INK,()->{if(loading)return;LocalDate day=LocalDate.parse(date);new DatePickerDialog(a,(view,y,m,d)->{date=LocalDate.of(y,m+1,d).toString();render();},day.getYear(),day.getMonthValue()-1,day.getDayOfMonth()).show();});page.addView(calendar,new LinearLayout.LayoutParams(-1,-2));Design.space(page,16);
        content=Design.column(a);page.addView(content);a.button("Exportar "+(period.equals("day")?"día":"mes")+" a Excel",this::export);a.button("Actualizar reporte",this::load);a.button("Volver",a::home);load();
    }
    JSONObject request(boolean preview){return a.obj("action","report","plant",plant,"allPlants",Boolean.toString(all),"period",period,"date",date,"month",month(),"preview",Boolean.toString(preview));}
    void load(){if(loading||a.page!=page||a.isFinishing()||a.isDestroyed())return;handler.removeCallbacks(refresh);if(a.isBusy()){handler.postDelayed(refresh,150);return;}loading=true;a.api(request(true),r->{loading=false;if(a.page!=page||!a.success(r))return;draw(r);if(period.equals("day")&&date.equals(MealLedger.day(System.currentTimeMillis())))handler.postDelayed(refresh,5000);});}
    void draw(JSONObject r){
        content.removeAllViews();LinearLayout hero=Design.card(a,Design.INK,22);hero.addView(Design.text(a,period.equals("day")?"ENTREGAS DEL DÍA":"ENTREGAS DEL MES",11,Design.LIME,true));Design.space(hero,12);
        LinearLayout amount=Design.row(a);amount.addView(Design.text(a,Integer.toString(r.optInt("count")),48,Color.WHITE,true));TextView caption=Design.text(a,"raciones",15,0xFFB9C7DF,false);caption.setPadding(Design.dp(a,14),Design.dp(a,18),0,0);amount.addView(caption);hero.addView(amount);Design.space(hero,12);hero.addView(Design.text(a,Design.money(r.optDouble("total",0))+"  ·  importe registrado",15,Color.WHITE,false));content.addView(hero);Design.space(content,18);
        JSONObject counts=r.optJSONObject("byMeal"),costs=r.optJSONObject("amounts");View[] tiles=new View[4];int i=0;for(String meal:Meals.ALL)tiles[i++]=Design.reportMeal(a,meal,counts==null?0:counts.optInt(meal),costs==null?0:costs.optDouble(meal,0));Design.grid(content,tiles);
        if(r.optInt("conflicts")>0){LinearLayout warning=Design.card(a,0xFFFFE6E8,15);warning.addView(Design.text(a,r.optInt("conflicts")+" registros por revisar",16,Design.RED,true));Design.space(warning,5);warning.addView(Design.text(a,"Los posibles duplicados están incluidos en el total y en la exportación.",13,Design.RED,false));content.addView(warning);Design.space(content,16);}
        LinearLayout title=Design.row(a);title.addView(Design.text(a,"Movimiento de entregas",18,Design.INK,true));content.addView(title);Design.space(content,8);
        JSONArray rows=r.optJSONArray("items");if(rows==null||rows.length()==0){LinearLayout empty=Design.card(a,Color.WHITE,22);empty.addView(Design.text(a,"Todavía no hay entregas",18,Design.INK,true));Design.space(empty,8);empty.addView(Design.text(a,"Los registros de la fecha elegida aparecerán aquí al registrarse o sincronizarse.",14,Design.MUTED,false));content.addView(empty);}
        else for(int n=0;n<rows.length();n++){
            JSONObject row=rows.optJSONObject(n);String meal=row.optString("comida");LinearLayout tile=Design.card(a,Color.WHITE,16);LinearLayout top=Design.row(a);TextView badge=Design.text(a,Meals.title(meal),12,Design.mealColor(meal),true);badge.setPadding(Design.dp(a,9),Design.dp(a,5),Design.dp(a,9),Design.dp(a,5));badge.setBackground(Design.shape(a,Design.mealTint(meal),8));top.addView(badge);TextView time=Design.text(a,row.optString("hora"),12,Design.MUTED,true);time.setGravity(Gravity.END);top.addView(time,new LinearLayout.LayoutParams(0,-2,1));tile.addView(top);Design.space(tile,10);tile.addView(Design.text(a,row.optString("nombre"),16,Design.INK,true));Design.space(tile,6);tile.addView(Design.text(a,row.optString("planta")+" · "+row.optString("operador"),12,Design.MUTED,false));if(period.equals("month")){Design.space(tile,4);tile.addView(Design.text(a,row.optString("fecha"),12,Design.MUTED,false));}
            if(!row.optString("revision").isEmpty()){Design.space(tile,7);tile.addView(Design.text(a,"POSIBLE DUPLICADO",12,Design.RED,true));}content.addView(tile);Design.space(content,9);
        }
        if(rows!=null&&rows.length()<r.optInt("count")){Design.space(content,5);content.addView(Design.text(a,"Mostrando "+rows.length()+" de "+r.optInt("count")+". La exportación incluye todos.",12,Design.MUTED,false));}
        Design.space(content,12);content.addView(Design.text(a,"Copia local · se actualiza al sincronizar los teléfonos.",12,Design.MUTED,false));
    }
    void export(){if(loading)return;loading=true;a.api(request(false),r->{loading=false;if(a.page!=page||!a.success(r))return;File dir=new File(a.getCacheDir(),"cards");dir.mkdirs();File file=new File(dir,"consumos-"+(period.equals("day")?date:month())+".csv");try(FileOutputStream out=new FileOutputStream(file)){out.write(r.getString("csv").getBytes(StandardCharsets.UTF_8));}Intent share=new Intent(Intent.ACTION_SEND);share.setType("text/csv");share.putExtra(Intent.EXTRA_STREAM,FileProvider.getUriForFile(a,a.getPackageName()+".files",file));share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);a.startActivity(Intent.createChooser(share,"Guardar reporte para Excel"));});}
}
