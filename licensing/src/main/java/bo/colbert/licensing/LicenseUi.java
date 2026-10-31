package bo.colbert.licensing;
import android.app.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.*;
import android.view.*;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

public final class LicenseUi {
 public static final int INK=Color.rgb(18,28,52),BLUE=Color.rgb(50,87,237),BG=Color.rgb(243,246,252),MUTED=Color.rgb(84,102,130),RED=Color.rgb(202,34,61),GREEN=Color.rgb(3,122,79);
 public final Activity a;public LinearLayout page;
 public LicenseUi(Activity a){this.a=a;}
 public int dp(int n){return (int)(n*a.getResources().getDisplayMetrics().density);}
 public void screen(String title,String subtitle){ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setBackgroundColor(INK);page=new LinearLayout(a);page.setOrientation(1);page.setPadding(dp(22),dp(25),dp(22),dp(30));page.setBackgroundColor(BG);scroll.addView(page);a.setContentView(scroll);scroll.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(0,i.getSystemWindowInsetTop(),0,i.getSystemWindowInsetBottom());return i;});text("COLBERT  /  LICENCIAS",12,BLUE);text(title,30,INK);text(subtitle,15,MUTED);}
 public TextView text(String value,int size,int color){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(size>=24)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));t.setPadding(0,dp(8),0,dp(8));page.addView(t);return t;}
 public static GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
 public Button button(String title,int color,Runnable action){Button b=new Button(a);b.setText(title);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(16);b.setBackground(shape(color,dp(16)));b.setPadding(dp(12),dp(15),dp(12),dp(15));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);p.bottomMargin=dp(6);page.addView(b,p);b.setOnClickListener(v->action.run());return b;}
 public EditText field(String label,String value){text(label,13,MUTED);EditText e=new EditText(a);e.setText(value);e.setSingleLine();e.setTextColor(INK);e.setTextSize(17);e.setPadding(dp(14),dp(12),dp(14),dp(12));e.setBackground(shape(Color.WHITE,dp(12)));page.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
 public void banner(String title,String detail,int color){LinearLayout box=new LinearLayout(a);box.setOrientation(1);box.setPadding(dp(18),dp(18),dp(18),dp(18));box.setBackground(shape(color,dp(22)));for(String s:new String[]{title,detail}){TextView t=new TextView(a);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(s.equals(title)?25:15);t.setPadding(0,dp(6),0,dp(6));box.addView(t);}LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(20);p.bottomMargin=dp(14);page.addView(box,p);}
 public void error(String text){new AlertDialog.Builder(a).setTitle("Revisa la operación").setMessage(text).setPositiveButton("Entendido",null).show();}
 public static String money(JSONObject obj){return String.format(java.util.Locale.ROOT,"Bs %.2f",obj.optLong("amountMinor")/100.0);}
 public static String when(long time){return time==0?"Sin vencimiento de pago":DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/La_Paz")).format(Instant.ofEpochMilli(time));}
}
