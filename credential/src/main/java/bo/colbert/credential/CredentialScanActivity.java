package bo.colbert.credential;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import com.journeyapps.barcodescanner.*;

/** Portrait camera shared by the worker and the supervisor during enrollment. */
public final class CredentialScanActivity extends CaptureActivity {
 private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 private TextView label(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);t.setPadding(0,dp(7),0,dp(7));return t;}
 @Override protected DecoratedBarcodeView initializeContent(){
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  getWindow().setStatusBarColor(0xff102039);getWindow().setNavigationBarColor(0xff102039);
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(22),dp(16),dp(22),dp(20));root.setBackgroundColor(0xff102039);
  root.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(dp(22),in.getSystemWindowInsetTop()+dp(16),dp(22),in.getSystemWindowInsetBottom()+dp(20));return in;});
  root.addView(label("MI COMEDOR  /  VINCULACIÓN",12,0xff7de5cd));
  TextView title=label("Escanea la credencial",28,Color.WHITE);title.setTypeface(null,Typeface.BOLD);root.addView(title);
  String prompt=getIntent().getStringExtra("PROMPT_MESSAGE");root.addView(label(prompt==null?"Centra el QR dentro del recuadro.":prompt,15,0xffc3d2e7));
  DecoratedBarcodeView scanner=new DecoratedBarcodeView(this);scanner.setStatusText("");scanner.getBarcodeView().setFramingRectSize(new Size(dp(245),dp(245)));
  GradientDrawable shape=new GradientDrawable();shape.setColor(0xff20334b);shape.setCornerRadius(dp(24));scanner.setBackground(shape);scanner.setClipToOutline(true);
  LinearLayout.LayoutParams camera=new LinearLayout.LayoutParams(-1,0,1);camera.setMargins(0,dp(20),0,dp(12));root.addView(scanner,camera);
  TextView tip=label("Mantén el teléfono vertical y acerca el código.\nLa lectura se realiza automáticamente.",14,0xffc3d2e7);tip.setGravity(Gravity.CENTER);root.addView(tip);
  Button cancel=new Button(this);cancel.setText("Volver sin vincular");cancel.setAllCaps(false);cancel.setTextColor(Color.WHITE);GradientDrawable button=new GradientDrawable();button.setColor(0xff26435f);button.setCornerRadius(dp(16));cancel.setBackground(button);root.addView(cancel,new LinearLayout.LayoutParams(-1,dp(52)));cancel.setOnClickListener(v->finish());setContentView(root);return scanner;
 }
}
