package bo.soboce.comedor;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;
import java.util.List;

/** A fixed top trigger opens navigation, keeping the serving screen focused. */
final class NavigationMenu {
    static final class Entry { final String title,detail; final Runnable action; Entry(String t,String d,Runnable a){title=t;detail=d;action=a;} }
    static Dialog show(MainActivity a,String brand,String operator,List<Entry> entries){
        Dialog dialog=new Dialog(a);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=Design.column(a);root.setBackgroundColor(Color.WHITE);
        LinearLayout header=Design.column(a);header.setPadding(a.dp(20),a.dp(20),a.dp(16),a.dp(16));header.setBackgroundColor(Design.INK);
        LinearLayout title=Design.row(a);TextView company=Design.text(a,brand+"\nCOMEDOR",19,Color.WHITE,true);title.addView(company,new LinearLayout.LayoutParams(0,-2,1));
        Button close=Design.button(a,"×",Design.INK,Color.WHITE,dialog::dismiss);close.setContentDescription("Cerrar menú");title.addView(close,new LinearLayout.LayoutParams(a.dp(48),a.dp(48)));header.addView(title);Design.space(header,8);header.addView(Design.text(a,operator,13,0xFFB9C7DF,false));root.addView(header);
        ScrollView scroll=new ScrollView(a);LinearLayout list=Design.column(a);list.setPadding(a.dp(12),a.dp(12),a.dp(12),a.dp(18));scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        for(Entry entry:entries){LinearLayout row=Design.card(a,Design.BG,13);row.setContentDescription(entry.title);row.setFocusable(true);row.setClickable(true);row.setMinimumHeight(a.dp(60));row.addView(Design.text(a,entry.title,16,Design.INK,true));Design.space(row,3);row.addView(Design.text(a,entry.detail,12,Design.MUTED,false));row.setOnClickListener(v->{if(a.isBusy())return;dialog.dismiss();entry.action.run();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=a.dp(8);list.addView(row,lp);}
        dialog.setContentView(root);dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.setGravity(Gravity.START|Gravity.TOP);window.setLayout(Math.min(a.dp(350),a.getResources().getDisplayMetrics().widthPixels-a.dp(32)),ViewGroup.LayoutParams.MATCH_PARENT);window.setDimAmount(.45f);}
        return dialog;
    }
}
