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
        fixture=new CompaniesTest();fixture.setup();java.security.KeyPair root=WalletTest.key();Companies.item(fixture.context,"soboce").getJSONObject("network").put("staffAuthority",bo.colbert.credential.WalletProtocol.publicText(root.getPublic()));Companies.install(fixture.context);fixture.permission("soboce",true);license=Companies.license(fixture.context,"soboce");config=fixture.config("soboce");
        StaffAccess auth=StaffAccess.attach(fixture.context,config);auth.create("Encargado de impresión","591234");auth=StaffAccess.attach(fixture.context,config);StaffTest signer=new StaffTest();signer.auth=auth;signer.config=config;signer.root=root;signer.now=fixture.now;signer.authorize("master",true,"Planta Norte","Planta Sur");db=new PeerDatabase(fixture.context,config);
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
        finally{StaffAccess.lock();if(db!=null)db.close();if(fixture!=null)fixture.cleanup();}
    }
    @Test public void allPaperAndQrSizesRenderAtPhysicalSizeWithoutRecordingMeals()throws Exception {
        List<QrPrintDocument.Group> groups=QrPrintData.snapshot(db,config,license,"",fixture.now);assertEquals(2,groups.size());assertEquals(8,groups.get(0).cards.size());assertEquals(3,groups.get(1).cards.size());assertEquals(0,new OfflineLedger(db,config).records().size());
        JSONArray manifest=new JSONArray();List<String> unreadable=new ArrayList<>();
        for(QrPrintDocument.Paper paper:QrPrintDocument.Paper.values())for(int cm=3;cm<=8;cm++){
            QrPrintDocument.Layout layout=new QrPrintDocument.Layout(paper,cm);assertEquals(cm*72/2.54,layout.qr,.001);
            File file=new File(folder,"QR-DEMO-"+paper.name()+"-"+cm+"cm.pdf");QrPrintDocument.write(file,"EMPRESA DE PRUEBA",groups,layout,false,()->QrPrintData.requirePermission(license));
            JSONArray pages=new JSONArray();
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){
                assertEquals(QrPrintDocument.pages(groups,layout,false),renderer.getPageCount());int index=0;
                for(List<QrPrintDocument.Entry> sheet:QrPrintDocument.sheets(groups,layout,false)){
                    JSONArray expected=new JSONArray();try(PdfRenderer.Page page=renderer.openPage(index++)){
                        assertEquals(layout.width,page.getWidth());assertEquals(layout.height,page.getHeight());float scale=300f/72; // Standard print rasterization; PDF paths retain arbitrary resolution.
                        Bitmap bitmap=Bitmap.createBitmap(Math.round(layout.width*scale),Math.round(layout.height*scale),Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);Matrix transform=new Matrix();transform.setScale(scale,scale);page.render(bitmap,new Rect(0,0,bitmap.getWidth(),bitmap.getHeight()),transform,PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        try{for(int i=0;i<sheet.size();i++){
                            RectF r=layout.qrBox(i);assertTrue(layout.cardBox(i).bottom<=layout.height-QrPrintDocument.MARGIN-QrPrintDocument.FOOTER+.01f);
                            int x=Math.round(r.left*scale),y=Math.round(r.top*scale),w=Math.round(r.width()*scale);int[] pixels=new int[w*w];bitmap.getPixels(pixels,0,w,x,y,w,w);
                            QrPrintDocument.Card card=sheet.get(i).card;assertTrue(card.qr.startsWith("SB5:"));
                            assertEquals(card.id,PeerCards.resolve(db,config,card.qr,fixture.now).getString("id"));
                            try{String qr=new com.google.zxing.qrcode.QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w,w,pixels))),Collections.singletonMap(DecodeHintType.TRY_HARDER,Boolean.TRUE)).getText();assertEquals(card.qr,qr);assertEquals(card.id,PeerCards.resolve(db,config,qr,fixture.now).getString("id"));}
                            catch(ReaderException failure){unreadable.add(file.getName()+" page="+index+" card="+card.id);}
                            expected.put(new JSONObject().put("id",card.id).put("identity",card.identity).put("plant",sheet.get(i).plant).put("qr",card.qr).put("x",r.left).put("y",r.top));
                        }
                        // CI decodes every printed card with an independent reader. Java ZXing's
                        // finder can miss valid dense symbols at particular raster offsets.
                        try(OutputStream raster=new FileOutputStream(new File(folder,file.getName()+"-page-"+index+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,raster));}
                        }finally{bitmap.recycle();}
                    }pages.put(new JSONObject().put("cards",expected));
                }
            }
            manifest.put(new JSONObject().put("file",file.getName()).put("paper",paper.name()).put("cm",cm).put("width",layout.width).put("height",layout.height).put("columns",layout.columns).put("rows",layout.rows).put("perPage",layout.perPage).put("pages",pages));
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
        File file=new File(folder,"stress.pdf");try{QrPrintDocument.write(file,"DEMO",Collections.singletonList(new QrPrintDocument.Group("Planta de prueba",cards)),new QrPrintDocument.Layout(QrPrintDocument.Paper.CARTA,7),()->{});try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer reader=new PdfRenderer(fd)){assertEquals(100,reader.getPageCount());}assertTrue(file.length()<50*1024*1024);}finally{file.delete();}
    }
    @Test public void printScreenOffersSizesAndCreatesTheSelectedPlantPdf()throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();QrPrintActivity activity=(QrPrintActivity)instrumentation.startActivitySync(new android.content.Intent(fixture.context,QrPrintActivity.class).putExtra("company","soboce").putExtra("plant","Planta Norte").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));PeerDatabaseTest ui=new PeerDatabaseTest();
        try{instrumentation.waitForIdleSync();instrumentation.runOnMainSync(()->{android.widget.Spinner size=(android.widget.Spinner)ui.findDescription(activity.getWindow().getDecorView(),"TAMAÑO DEL QR");assertEquals(6,size.getCount());assertEquals("3 x 3 cm",size.getItemAtPosition(0));assertEquals("8 x 8 cm",size.getItemAtPosition(5));size.setSelection(0);});ui.waitText(activity,"Hasta 30 QR por hoja");ui.screenshot("12-imprimir-qr-opciones");instrumentation.runOnMainSync(()->ui.findText(activity.getWindow().getDecorView(),"Generar PDF").performClick());long end=System.currentTimeMillis()+20000;while(activity.generated==null&&System.currentTimeMillis()<end)Thread.sleep(100);assertNotNull(activity.generated);assertTrue(activity.generated.isFile());try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(activity.generated,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer pdf=new PdfRenderer(fd)){assertEquals(1,pdf.getPageCount());}ui.screenshot("13-qr-pdf-listo");}finally{instrumentation.runOnMainSync(activity::finish);}
    }
    @Test public void allSizesMaximizeRegularGridWithinPrintMarginsWithoutOverlaps()throws Exception {
        int[][] capacity={{30,18,9,9,6,4},{36,21,12,10,8,6},{42,21,12,12,8,6}};
        for(QrPrintDocument.Paper paper:QrPrintDocument.Paper.values())for(int cm=3;cm<=8;cm++){
            QrPrintDocument.Layout l=new QrPrintDocument.Layout(paper,cm);assertEquals(capacity[paper.ordinal()][cm-3],l.perPage);
            int best=0;for(int turn=0;turn<2;turn++){int w=turn==0?paper.width:paper.height,h=turn==0?paper.height:paper.width;
                for(int cols=1;cols<=12;cols++)for(int rows=1;rows<=12;rows++)if(cols*l.cardWidth+(cols-1)*QrPrintDocument.GAP<=w-2*QrPrintDocument.MARGIN&&rows*l.cardHeight+(rows-1)*QrPrintDocument.GAP<=h-2*QrPrintDocument.MARGIN-QrPrintDocument.HEADER-QrPrintDocument.FOOTER)best=Math.max(best,cols*rows);
            }assertEquals(best,l.perPage);
            for(int i=0;i<l.perPage;i++){RectF card=l.cardBox(i),qr=l.qrBox(i);assertTrue(card.left>=QrPrintDocument.MARGIN-.01f&&card.right<=l.width-QrPrintDocument.MARGIN+.01f);assertTrue(card.top>=QrPrintDocument.MARGIN+QrPrintDocument.HEADER-.01f&&card.bottom<=l.height-QrPrintDocument.MARGIN-QrPrintDocument.FOOTER+.01f);assertEquals(cm*72/2.54,qr.width(),.001);assertTrue(card.contains(qr));for(int j=0;j<i;j++)assertFalse(RectF.intersects(card,l.cardBox(j)));}
        }
        for(int cm:new int[]{2,9})WalletTest.rejected(()->new QrPrintDocument.Layout(QrPrintDocument.Paper.CARTA,cm));
    }
    @Test public void continuousPagesSavePaperAndPreserveEveryPlantsOrder()throws Exception {
        List<QrPrintDocument.Group> groups=QrPrintData.snapshot(db,config,license,"",fixture.now);QrPrintDocument.Layout l=new QrPrintDocument.Layout(QrPrintDocument.Paper.CARTA,3);
        assertEquals(1,QrPrintDocument.pages(groups,l,false));assertEquals(2,QrPrintDocument.pages(groups,l,true));
        List<QrPrintDocument.Entry> page=QrPrintDocument.sheets(groups,l,false).get(0);assertEquals(11,page.size());int position=0;for(QrPrintDocument.Group group:groups)for(QrPrintDocument.Card card:group.cards){assertEquals(group.plant,page.get(position).plant);assertEquals(card.id,page.get(position++).card.id);}
        List<QrPrintDocument.Card> full=new ArrayList<>();for(int i=0;i<l.perPage+1;i++)full.add(new QrPrintDocument.Card("sample-"+i,"Trabajador de ejemplo "+(i+1),"CI "+(100000+i),groups.get(0).cards.get(i%groups.get(0).cards.size()).qr));
        List<QrPrintDocument.Group> sample=Collections.singletonList(new QrPrintDocument.Group("PLANTA DE EJEMPLO",full));assertEquals(2,QrPrintDocument.pages(sample,l,false));
        QrPrintDocument.write(new File(folder,"distribucion-completa-3cm.pdf"),"DEMOSTRACIÓN DE DISTRIBUCIÓN",sample,l,false,()->{});
        assertEquals(0,new OfflineLedger(db,config).records().size());
    }
}
