package bo.soboce.comedor;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import bo.colbert.licensing.LicenseClient;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Local batch printing. The immutable company binding also survives a file-picker round trip. */
public final class QrPrintActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LinearLayout content;private TextView summary;private Spinner plant,paper,size;
    private Button generate;private boolean busy;private String company;private PeerConfig config;private LicenseClient license;
    private List<String> plants;volatile File generated;private String generatedName="",savedFile="",generatedOptions="";
    @Override public void onCreate(Bundle state){super.onCreate(state);
        try{
            company=state==null?getIntent().getStringExtra("company"):state.getString("company");
            if(company==null)company=Companies.current(this);
            config=new PeerConfig(new Vault(this,company).get("peerConfig"));
            PeerConfig.require(Companies.company(this,config).equals(company),"La red pertenece a otra empresa.");
            license=Companies.enabled(this)?Companies.license(this,company):LicenseClient.get(this);plants=QrPrintData.plants(config);
            render(state);
        }catch(Exception e){new AlertDialog.Builder(this).setTitle("Impresión no disponible").setMessage(e.getMessage()).setPositiveButton("Volver",(d,w)->finish()).setOnCancelListener(d->finish()).show();}
    }
    private TextView label(String value,int sp,int color){TextView t=Design.text(this,value,sp,color,false);t.setPadding(0,Design.dp(this,8),0,Design.dp(this,6));content.addView(t);return t;}
    private Spinner select(String title,Object[] choices){label(title,12,Design.MUTED);Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices));s.setContentDescription(title);content.addView(s,new LinearLayout.LayoutParams(-1,Design.dp(this,54)));return s;}
    private Button button(String title,Runnable action){Button b=Design.button(this,title,Design.BLUE,Color.WHITE,()->{if(!busy)action.run();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=Design.dp(this,12);content.addView(b,p);return b;}
    private void render(Bundle state){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Design.INK);content=Design.column(this);content.setBackgroundColor(Design.BG);content.setPadding(Design.dp(this,22),Design.dp(this,22),Design.dp(this,22),Design.dp(this,26));scroll.addView(content);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(0,in.getSystemWindowInsetTop(),0,in.getSystemWindowInsetBottom());return in;});
        label(company.toUpperCase(Locale.ROOT)+" / CREDENCIALES",12,Design.BLUE);label("Imprimir QR en PDF",29,Design.INK);label("Para entregar tarjetas físicas a los trabajadores.",15,Design.MUTED);
        List<String> options=new ArrayList<>();options.add("Todas las plantas (separadas)");options.addAll(plants);plant=select("PLANTA O BLOQUE",options.toArray());
        int initial=plants.indexOf(getIntent().getStringExtra("plant"));plant.setSelection(state==null?Math.max(0,initial+1):state.getInt("plant",0));
        paper=select("TAMAÑO DE HOJA",QrPrintDocument.Paper.values());size=select("TAMAÑO DEL QR",new String[]{"7 x 7 cm","8 x 8 cm"});
        if(state!=null){paper.setSelection(state.getInt("paper",0));size.setSelection(state.getInt("size",0));savedFile=state.getString("file","");generatedName=state.getString("name","");generatedOptions=state.getString("options","");if(savedFile.startsWith(company+"-")&&savedFile.matches("[a-z0-9-]+\\.pdf")){File restored=new File(directory(),savedFile);if(restored.isFile())generated=restored;}}
        summary=label("",15,Design.INK);AdapterView.OnItemSelectedListener selected=new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int i,long id){updateSummary();}public void onNothingSelected(AdapterView<?> p){}};paper.setOnItemSelectedListener(selected);size.setOnItemSelectedListener(selected);updateSummary();
        label("El cuadrado QR incluye el borde blanco. Nombre, carnet o SAP y planta van debajo. Las líneas punteadas sirven para recortar.",14,Design.MUTED);
        label("Imprime al 100% o Tamaño real; desactiva Ajustar a página. Se incluyen trabajadores activos guardados en este teléfono. Sincroniza antes para incorporar altas de otros equipos.",14,Design.MUTED);
        generate=button("Generar PDF",this::generate);
        button("Ver último PDF",()->open(false));button("Compartir último PDF",()->open(true));button("Guardar último PDF",this::save);
        button("Volver",this::finish);
    }
    private File directory(){return new File(getCacheDir(),"qr-print");}
    private String options(){return plant.getSelectedItemPosition()+":"+paper.getSelectedItemPosition()+":"+size.getSelectedItemPosition();}
    private void updateSummary(){QrPrintDocument.Layout layout=new QrPrintDocument.Layout((QrPrintDocument.Paper)paper.getSelectedItem(),size.getSelectedItemPosition()==1?8:7);summary.setText(layout.perPage+" QR por hoja · "+layout.columns+" columnas × "+layout.rows+" filas\nCada planta comienza en una página nueva.");}
    private boolean allowed(){if(license.allowed())return true;error("El permiso de "+company.toUpperCase(Locale.ROOT)+" está pausado. Vuelve a Licencia y pagos para renovarlo.");return false;}
    private void error(String message){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("Imprimir credenciales").setMessage(message).setPositiveButton("Entendido",null).show();}
    private void generate(){
        if(!allowed())return;String selected=plant.getSelectedItemPosition()==0?"":plants.get(plant.getSelectedItemPosition()-1);
        QrPrintDocument.Layout layout=new QrPrintDocument.Layout((QrPrintDocument.Paper)paper.getSelectedItem(),size.getSelectedItemPosition()==1?8:7);
        String chosenOptions=options();busy=true;generate.setEnabled(false);plant.setEnabled(false);paper.setEnabled(false);size.setEnabled(false);summary.setText("Preparando credenciales…");
        worker.execute(()->{
            File output=null;int count=0,pages=0;String problem=null;
            try{
                File dir=directory();if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("No se pudo preparar el archivo.");
                output=new File(dir,company+"-"+UUID.randomUUID()+".pdf");
                List<QrPrintDocument.Group> groups;try(PeerDatabase db=new PeerDatabase(this,config)){groups=QrPrintData.snapshot(db,config,license,selected,System.currentTimeMillis());}
                for(QrPrintDocument.Group g:groups)count+=g.cards.size();pages=QrPrintDocument.pages(groups,layout);
                QrPrintDocument.write(output,company.toUpperCase(Locale.ROOT),groups,layout,()->QrPrintData.requirePermission(license));
            }catch(Exception e){problem=e.getMessage()==null?"No se pudo generar el PDF.":e.getMessage();if(output!=null&&output.exists())output.delete();}
            File ready=output;int total=count,sheets=pages;String message=problem;
            runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed()){if(ready!=null)ready.delete();return;}generate.setEnabled(true);plant.setEnabled(true);paper.setEnabled(true);size.setEnabled(true);
                if(message!=null){updateSummary();error(message);return;}generated=ready;generatedOptions=chosenOptions;savedFile=ready.getName();generatedName="QR-"+company.toUpperCase(Locale.ROOT)+"-"+(selected.isEmpty()?"todas-las-plantas":selected.replaceAll("[^\\p{L}\\p{N}]+","-"))+"-"+layout.cm+"cm-"+layout.paper.name().toLowerCase(Locale.ROOT)+".pdf";summary.setText("PDF listo · "+total+" trabajadores · "+sheets+" hojas\n"+generatedName);
            });
        });
    }
    private boolean ready(){if(!allowed())return false;if(generated==null||!generated.isFile()){error("Genera primero un PDF.");return false;}if(!options().equals(generatedOptions)){error("Las opciones cambiaron. Genera un nuevo PDF para aplicar el tamaño y la planta elegidos.");return false;}return true;}
    private void open(boolean share){
        if(!ready())return;android.net.Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",generated);
        Intent intent=share?new Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,uri):new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/pdf");
        intent.setClipData(ClipData.newRawUri("Credenciales PDF",uri));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(share?Intent.createChooser(intent,"Compartir PDF de credenciales"):intent);}catch(ActivityNotFoundException e){error("No hay un visor PDF disponible. Puedes guardar o compartir el archivo.");}
    }
    private void save(){if(ready())startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,generatedName),721);}
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=721||result!=RESULT_OK||data==null||data.getData()==null||!ready())return;
        android.net.Uri uri=data.getData();File source=generated;busy=true;worker.execute(()->{String problem=null;try{QrPrintData.requirePermission(license);try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Destino no disponible.");byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}catch(Exception e){problem="No se pudo guardar el PDF. El original sigue disponible para reintentar.";}String message=problem;runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed())return;if(message==null)summary.setText("PDF guardado. Imprime al 100% / Tamaño real.");else error(message);});});
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("company",company);if(plant!=null){state.putInt("plant",plant.getSelectedItemPosition());state.putInt("paper",paper.getSelectedItemPosition());state.putInt("size",size.getSelectedItemPosition());}state.putString("file",savedFile);state.putString("name",generatedName);state.putString("options",generatedOptions);}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
