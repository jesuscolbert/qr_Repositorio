package bo.soboce.comedor;

import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import bo.colbert.licensing.LicenseClient;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RunWith(AndroidJUnit4.class)
public class QrPrintTest {
    CompaniesTest fixture;PeerDatabase db;PeerConfig config;LicenseClient license;File folder;
    @Before public void setup()throws Exception {
        fixture=new CompaniesTest();fixture.setup();Companies.install(fixture.context);fixture.permission("soboce",true);license=Companies.license(fixture.context,"soboce");config=fixture.config("soboce");db=new PeerDatabase(fixture.context,config);
        for(int i=0;i<10;i++){JSONObject req=fixture.worker(i<7?"Planta Norte":"Planta Sur").put("document",String.format(Locale.ROOT,"%08d",10000+i)).put("name",i==0?"María José Álvarez Fernández de la Cruz - Nombre largo para verificar la tarjeta":"Trabajador de prueba "+i);PeerCards.request(db,config,req,fixture.now).getString("qr");}
        db.save("people","SAP-000123",new JSONObject().put("id","SAP-000123").put("sap","000123").put("nombre","Álvarez López - personal SAP").put("planta","Planta Norte").put("activo",true).put("tipo","EMPLEADO").put("vence","").put("comidas","desayuno,almuerzo,cena"));
        db.save("people","inactive",new JSONObject().put("id","inactive").put("nombre","Inactivo excluido").put("planta","Planta Norte").put("activo",false));db.save("people","visitor",new JSONObject().put("id","visitor").put("nombre","Visitante excluido").put("planta","Planta Norte").put("activo",true).put("tipo","VISITA"));
        folder=new File(fixture.context.getExternalFilesDir(null),"pdf-tests");assertTrue(folder.isDirectory()||folder.mkdirs());
    }
    @After public void cleanup()throws Exception {
        try{if(folder!=null){
            // UiAutomation tokenizes commands directly; it does not interpret shell quoting/redirection.
            assertTrue(folder.getAbsolutePath().matches("[A-Za-z0-9/._-]+"));PeerDatabaseTest ui=new PeerDatabaseTest();ui.shellUi("mkdir -p /sdcard/Download/soboce-pdf");
            ui.shellUi("cp -r "+folder.getAbsolutePath()+"/. /sdcard/Download/soboce-pdf/");
            try(ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("ls /sdcard/Download/soboce-pdf");InputStream result=new ParcelFileDescriptor.AutoCloseInputStream(fd)){
                String saved=new String(result.readAllBytes(),StandardCharsets.UTF_8);for(File file:Objects.requireNonNull(folder.listFiles()))assertTrue("Missing PDF artifact: "+file.getName(),saved.contains(file.getName()));
            }
        }}
        finally{if(db!=null)db.close();if(fixture!=null)fixture.cleanup();}
    }
    @Test public void allPaperAndQrSizesRenderAtPhysicalSizeWithoutRecordingMeals()throws Exception {
        List<QrPrintDocument.Group> groups=QrPrintData.snapshot(db,config,license,"",fixture.now);assertEquals(2,groups.size());assertEquals(8,groups.get(0).cards.size());assertEquals(3,groups.get(1).cards.size());assertEquals(0,new OfflineLedger(db,config).records().size());
        JSONArray manifest=new JSONArray();List<String> unreadable=new ArrayList<>();
        for(QrPrintDocument.Paper paper:QrPrintDocument.Paper.values())for(int cm:new int[]{7,8}){
            QrPrintDocument.Layout layout=new QrPrintDocument.Layout(paper,cm);assertEquals(cm*72/2.54,layout.qr,.001);assertEquals(paper==QrPrintDocument.Paper.CARTA?4:paper==QrPrintDocument.Paper.OFICIO&&cm==8?4:6,layout.perPage);
            File file=new File(folder,"QR-DEMO-"+paper.name()+"-"+cm+"cm.pdf");QrPrintDocument.write(file,"EMPRESA DE PRUEBA",groups,layout,()->QrPrintData.requirePermission(license));
            JSONArray pages=new JSONArray();
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){
                assertEquals(QrPrintDocument.pages(groups,layout),renderer.getPageCount());int index=0;
                for(QrPrintDocument.Group group:groups)for(int offset=0;offset<group.cards.size();offset+=layout.perPage){
                    JSONArray expected=new JSONArray();try(PdfRenderer.Page page=renderer.openPage(index++)){
                        assertEquals(paper.width,page.getWidth());assertEquals(paper.height,page.getHeight());float scale=300f/72; // Standard print rasterization; PDF paths retain arbitrary resolution.
                        Bitmap bitmap=Bitmap.createBitmap(Math.round(paper.width*scale),Math.round(paper.height*scale),Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);Matrix transform=new Matrix();transform.setScale(scale,scale);page.render(bitmap,new Rect(0,0,bitmap.getWidth(),bitmap.getHeight()),transform,PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        try{for(int i=0;i<layout.perPage&&offset+i<group.cards.size();i++){
                            RectF r=layout.qrBox(i);assertTrue(r.bottom+QrPrintDocument.CAPTION<paper.height-QrPrintDocument.MARGIN-QrPrintDocument.FOOTER);
                            int x=Math.round(r.left*scale),y=Math.round(r.top*scale),w=Math.round(r.width()*scale);int[] pixels=new int[w*w];bitmap.getPixels(pixels,0,w,x,y,w,w);
                            QrPrintDocument.Card card=group.cards.get(offset+i);
                            assertEquals(card.id,PeerCards.resolve(db,config,card.qr,fixture.now).getString("id"));
                            try{String qr=new com.google.zxing.qrcode.QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w,w,pixels))),Collections.singletonMap(DecodeHintType.TRY_HARDER,Boolean.TRUE)).getText();assertEquals(card.qr,qr);assertEquals(card.id,PeerCards.resolve(db,config,qr,fixture.now).getString("id"));}
                            catch(ReaderException failure){unreadable.add(file.getName()+" page="+index+" card="+card.id);}
                            expected.put(new JSONObject().put("id",card.id).put("identity",card.identity).put("qr",card.qr));
                        }
                        // CI decodes every printed card with an independent reader. Java ZXing's
                        // finder can miss valid dense symbols at particular raster offsets.
                        try(OutputStream raster=new FileOutputStream(new File(folder,file.getName()+"-page-"+index+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,raster));}
                        }finally{bitmap.recycle();}
                    }pages.put(new JSONObject().put("plant",group.plant).put("cards",expected));
                }
            }
            manifest.put(new JSONObject().put("file",file.getName()).put("cm",cm).put("width",paper.width).put("height",paper.height).put("perPage",layout.perPage).put("pages",pages));
        }
        try(OutputStream out=new FileOutputStream(new File(folder,"expected.json"))){out.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));}
        try(OutputStream out=new FileOutputStream(new File(folder,"java-reader-diagnostics.json"))){out.write(new JSONArray(unreadable).toString().getBytes(StandardCharsets.UTF_8));}
        assertEquals(0,new OfflineLedger(db,config).records().size());
    }
    @Test public void exportHonorsCompanyPlantAndPaymentAndRemovesCancelledOutput()throws Exception {
        List<QrPrintDocument.Group> groups=QrPrintData.snapshot(db,config,license,"Planta Sur",fixture.now);assertEquals(1,groups.size());assertEquals(3,groups.get(0).cards.size());
        try{QrPrintData.snapshot(db,config,license,"Otra planta",fixture.now);fail("Unknown plant exported");}catch(IllegalArgumentException expected){}
        try(PeerDatabase other=new PeerDatabase(fixture.context,fixture.config("frigor"))){fixture.permission("frigor",true);assertTrue(QrPrintData.snapshot(other,fixture.config("frigor"),Companies.license(fixture.context,"frigor"),"",fixture.now).isEmpty());}
        fixture.permission("soboce",false);try{QrPrintData.snapshot(db,config,license,"",fixture.now);fail("Unpaid export allowed");}catch(IllegalArgumentException expected){}
        File cancelled=new File(folder,"cancelled.pdf");int[] calls={0};try{QrPrintDocument.write(cancelled,"DEMO",groups,new QrPrintDocument.Layout(QrPrintDocument.Paper.CARTA,7),()->{if(++calls[0]>2)throw new InterruptedException();});fail("Cancelled export completed");}catch(InterruptedException expected){}assertFalse(cancelled.exists());
    }
    @Test public void fullRosterSizedBatchFitsThePdfWriter()throws Exception {
        QrPrintDocument.Card example=QrPrintData.snapshot(db,config,license,"Planta Norte",fixture.now).get(0).cards.get(0);List<QrPrintDocument.Card> cards=new ArrayList<>();for(int i=0;i<600;i++)cards.add(example);
        File file=new File(folder,"stress.pdf");try{QrPrintDocument.write(file,"DEMO",Collections.singletonList(new QrPrintDocument.Group("Planta de prueba",cards)),new QrPrintDocument.Layout(QrPrintDocument.Paper.CARTA,7),()->{});try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer reader=new PdfRenderer(fd)){assertEquals(150,reader.getPageCount());}assertTrue(file.length()<50*1024*1024);}finally{file.delete();}
    }
    @Test public void printScreenOffersSizesAndCreatesTheSelectedPlantPdf()throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();QrPrintActivity activity=(QrPrintActivity)instrumentation.startActivitySync(new android.content.Intent(fixture.context,QrPrintActivity.class).putExtra("company","soboce").putExtra("plant","Planta Norte").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));PeerDatabaseTest ui=new PeerDatabaseTest();
        try{instrumentation.waitForIdleSync();ui.screenshot("12-imprimir-qr-opciones");instrumentation.runOnMainSync(()->{assertTrue(ui.containsText(activity.getWindow().getDecorView(),"7 x 7 cm"));ui.findText(activity.getWindow().getDecorView(),"Generar PDF").performClick();});long end=System.currentTimeMillis()+20000;while(activity.generated==null&&System.currentTimeMillis()<end)Thread.sleep(100);assertNotNull(activity.generated);assertTrue(activity.generated.isFile());ui.screenshot("13-qr-pdf-listo");}finally{instrumentation.runOnMainSync(activity::finish);}
    }
}
