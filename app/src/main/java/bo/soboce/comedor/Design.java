package bo.soboce.comedor;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.content.res.ColorStateList;
import android.view.*;
import android.widget.*;

/** Native drawing keeps the UI sharp and the APK small. */
final class Design {
    static final int INK=Color.rgb(17,29,52),MUTED=Color.rgb(91,107,130),BG=Color.rgb(241,245,250),BLUE=Color.rgb(34,87,224),GREEN=Color.rgb(5,122,73),RED=Color.rgb(205,36,55),AMBER=Color.rgb(255,199,65),LIME=Color.rgb(193,244,93);
    static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable shape(Context c,int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    static TextView text(Context c,String value,int size,int color,boolean bold){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("tnum");t.setTypeface(android.graphics.Typeface.create(bold?"sans-serif-medium":"sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));t.setIncludeFontPadding(false);t.setLineSpacing(dp(c,3),1);return t;}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    static void space(LinearLayout parent,int size){View v=new View(parent.getContext());parent.addView(v,new LinearLayout.LayoutParams(1,dp(parent.getContext(),size)));}
    static LinearLayout card(Context c,int color,int padding){LinearLayout l=column(c);l.setPadding(dp(c,padding),dp(c,padding),dp(c,padding),dp(c,padding));l.setBackground(shape(c,color,20));return l;}
    static Button button(Context c,String title,int bg,int fg,Runnable action){Button b=new Button(c);b.setText(title);b.setTextSize(16);b.setAllCaps(false);b.setTextColor(fg);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));b.setMinHeight(dp(c,52));b.setPadding(dp(c,16),dp(c,10),dp(c,16),dp(c,10));b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF),shape(c,bg,16),null));b.setOnClickListener(v->action.run());return b;}
    static int mealColor(String m){switch(m){case "desayuno":return Color.rgb(161,90,0);case "cena":return Color.rgb(110,57,198);case "dieta":return GREEN;default:return BLUE;}}
    static int mealTint(String m){switch(m){case "desayuno":return 0xFFFFF1D3;case "cena":return 0xFFF1E9FF;case "dieta":return 0xFFDAF5E9;default:return 0xFFE5EDFF;}}
    static View mealCard(Context c,String meal,boolean chosen,Runnable action){
        LinearLayout box=card(c,chosen?mealColor(meal):Color.WHITE,16);box.setMinimumHeight(dp(c,112));box.setElevation(dp(c,chosen?3:1));int fg=chosen?Color.WHITE:mealColor(meal);
        LinearLayout line=row(c);Icon icon=new Icon(c,meal,fg);line.addView(icon,new LinearLayout.LayoutParams(dp(c,28),dp(c,28)));TextView tick=text(c,chosen?"✓":"",18,fg,true);tick.setGravity(Gravity.END);line.addView(tick,new LinearLayout.LayoutParams(0,-2,1));box.addView(line);space(box,14);box.addView(text(c,Meals.title(meal),17,chosen?Color.WHITE:INK,true));
        if(meal.equals("dieta")){space(box,4);box.addView(text(c,"En lugar de almuerzo",11,chosen?Color.WHITE:MUTED,false));}
        box.setContentDescription(Meals.title(meal)+(chosen?", seleccionado":""));box.setFocusable(true);box.setOnClickListener(v->action.run());return box;
    }
    static View reportMeal(Context c,String meal,int count,double amount){
        LinearLayout box=card(c,mealTint(meal),14);LinearLayout top=row(c);top.addView(new Icon(c,meal,mealColor(meal)),new LinearLayout.LayoutParams(dp(c,22),dp(c,22)));TextView name=text(c,Meals.title(meal),13,mealColor(meal),true);name.setPadding(dp(c,7),0,0,0);top.addView(name);box.addView(top);space(box,10);box.addView(text(c,Integer.toString(count),28,INK,true));space(box,5);box.addView(text(c,money(amount),12,MUTED,false));return box;
    }
    static String money(double value){return String.format(new java.util.Locale("es","BO"),"Bs %,.2f",value);}
    static void grid(LinearLayout parent,View... views){Context c=parent.getContext();for(int i=0;i<views.length;i+=2){LinearLayout line=row(c);for(int j=i;j<Math.min(i+2,views.length);j++){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.setMargins(j==i?0:dp(c,6),0,j==i?dp(c,6):0,0);line.addView(views[j],lp);}LinearLayout.LayoutParams row=new LinearLayout.LayoutParams(-1,-2);row.bottomMargin=dp(c,12);parent.addView(line,row);}}
    static final class Icon extends View {
        final String name;final int color;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(Context c,String name,int color){super(c);this.name=name;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.save();float size=Math.min(getWidth(),getHeight());canvas.translate((getWidth()-size)/2,(getHeight()-size)/2);canvas.scale(size/48,size/48);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);Path path=new Path();
            switch(name){
                case "check":canvas.drawCircle(24,24,20,p);path.moveTo(13,24);path.lineTo(21,32);path.lineTo(35,17);canvas.drawPath(path,p);break;
                case "stop":path.moveTo(17,4);path.lineTo(31,4);path.lineTo(44,17);path.lineTo(44,31);path.lineTo(31,44);path.lineTo(17,44);path.lineTo(4,31);path.lineTo(4,17);path.close();canvas.drawPath(path,p);canvas.drawLine(24,13,24,26,p);canvas.drawPoint(24,34,p);break;
                case "desayuno":canvas.drawCircle(24,24,9,p);for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(24+(float)Math.cos(a)*16,24+(float)Math.sin(a)*16,24+(float)Math.cos(a)*21,24+(float)Math.sin(a)*21,p);}break;
                case "cena":path.moveTo(31,5);path.cubicTo(0,0,0,47,29,43);path.cubicTo(36,42,41,36,43,30);path.cubicTo(17,39,12,14,31,5);canvas.drawPath(path,p);break;
                case "dieta":path.moveTo(9,37);path.cubicTo(-2,14,20,6,41,7);path.cubicTo(42,36,22,48,9,37);canvas.drawPath(path,p);canvas.drawLine(6,43,31,19,p);break;
                case "almuerzo":canvas.drawArc(new RectF(5,13,43,43),0,180,false,p);canvas.drawLine(5,28,43,28,p);canvas.drawLine(15,44,33,44,p);for(int i=16;i<36;i+=9){path.reset();path.moveTo(i,20);path.cubicTo(i-5,14,i+4,11,i,5);canvas.drawPath(path,p);}break;
                default:for(int x:new int[]{5,30})for(int y:new int[]{5,30})canvas.drawRoundRect(new RectF(x,y,x+13,y+13),2,2,p);canvas.drawLine(4,24,44,24,p);
            }canvas.restore();
        }
    }
}
