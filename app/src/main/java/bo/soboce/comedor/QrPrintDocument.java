package bo.soboce.comedor;

import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.text.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.*;
import java.util.*;

/** Physical QR dimensions are PDF points, independent of screen density and printer DPI. */
final class QrPrintDocument {
    enum Paper {
        CARTA("Carta - 21,59 x 27,94 cm",612,792),
        OFICIO("Oficio - 21,59 x 33,02 cm",612,936),
        LEGAL("Legal / Oficio - 21,59 x 35,56 cm",612,1008);
        final String label;final int width,height;
        Paper(String label,int width,int height){this.label=label;this.width=width;this.height=height;}
        @Override public String toString(){return label;}
    }
    static final int MIN_CM=3,MAX_CM=8;
    static final float CM=72f/2.54f,MARGIN=.6f*CM,HEADER=28,FOOTER=20,GAP=4,PADDING=3;
    static final class Layout {
        final Paper paper;final int cm,width,height,columns,rows,perPage;final boolean landscape;final float qr,caption,cardWidth,cardHeight,left,top;
        Layout(Paper paper,int cm){
            if(paper==null||cm<MIN_CM||cm>MAX_CM)throw new IllegalArgumentException("Elige QR de 3 a 8 cm.");
            this.paper=paper;this.cm=cm;qr=cm*CM;caption=cm<=4?36:44;cardWidth=qr+2*PADDING;cardHeight=qr+caption+2*PADDING;
            int vertical=columns(paper.width)*rows(paper.height),horizontal=columns(paper.height)*rows(paper.width);
            landscape=horizontal>vertical;width=landscape?paper.height:paper.width;height=landscape?paper.width:paper.height;
            columns=columns(width);rows=rows(height);perPage=columns*rows;
            left=(width-columns*cardWidth-(columns-1)*GAP)/2;
            top=MARGIN+HEADER+(height-2*MARGIN-HEADER-FOOTER-rows*cardHeight-(rows-1)*GAP)/2;
            if(columns<1||rows<1||left<MARGIN-.01f)throw new IllegalArgumentException("El tamaño no cabe en esta hoja.");
        }
        private int columns(int w){return (int)Math.floor((w-2*MARGIN+GAP)/(cardWidth+GAP));}
        private int rows(int h){return (int)Math.floor((h-2*MARGIN-HEADER-FOOTER+GAP)/(cardHeight+GAP));}
        String orientation(){return landscape?"Horizontal":"Vertical";}
        RectF cardBox(int index){if(index<0||index>=perPage)throw new IllegalArgumentException("Posición fuera de la hoja.");int col=index%columns,row=index/columns;float x=left+col*(cardWidth+GAP),y=top+row*(cardHeight+GAP);return new RectF(x,y,x+cardWidth,y+cardHeight);}
        RectF qrBox(int index){RectF card=cardBox(index);return new RectF(card.left+PADDING,card.top+PADDING,card.left+PADDING+qr,card.top+PADDING+qr);}
    }
    static final class Card {
        final String id,name,identity,qr;
        Card(String id,String name,String identity,String qr){this.id=id;this.name=name;this.identity=identity;this.qr=qr;}
    }
    static final class Group {
        final String plant;final List<Card> cards;
        Group(String plant,List<Card> cards){this.plant=plant;this.cards=Collections.unmodifiableList(new ArrayList<>(cards));}
    }
    static final class Entry {
        final String plant;final Card card;
        Entry(String plant,Card card){this.plant=plant;this.card=card;}
    }
    /** Stable plant/name order; optionally fill a plant's last sheet with the next plant. */
    static List<List<Entry>> sheets(List<Group> groups,Layout layout,boolean separate){
        List<List<Entry>> result=new ArrayList<>();List<Entry> current=null;
        for(Group group:groups){if(separate)current=null;for(Card card:group.cards){
            if(current==null||current.size()==layout.perPage){current=new ArrayList<>();result.add(current);}current.add(new Entry(group.plant,card));
        }}return result;
    }
    interface Guard {void check()throws Exception;}
    static int pages(List<Group> groups,Layout layout){return pages(groups,layout,true);}
    static int pages(List<Group> groups,Layout layout,boolean separate){int n=0,count=0;for(Group group:groups){count+=group.cards.size();if(separate)n+=(group.cards.size()+layout.perPage-1)/layout.perPage;}return separate?n:(count+layout.perPage-1)/layout.perPage;}
    static BitMatrix matrix(String qr)throws WriterException {
        Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.MARGIN,4);hints.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M);hints.put(EncodeHintType.CHARACTER_SET,"UTF-8");
        return new QRCodeWriter().encode(qr,BarcodeFormat.QR_CODE,1,1,hints);
    }
    static void write(File target,String company,List<Group> groups,Layout layout,Guard guard)throws Exception {
        write(target,company,groups,layout,true,guard);
    }
    static void write(File target,String company,List<Group> groups,Layout layout,boolean separate,Guard guard)throws Exception {
        List<List<Entry>> sheets=sheets(groups,layout,separate);
        if(sheets.isEmpty())throw new IllegalArgumentException("No hay trabajadores activos para imprimir.");
        boolean success=false;PdfDocument pdf=new PdfDocument();
        try{
            int number=0,total=sheets.size();guard.check();
            for(List<Entry> sheet:sheets){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();guard.check();
                PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(layout.width,layout.height,++number).create());
                try{
                    Canvas canvas=page.getCanvas();canvas.drawColor(Color.WHITE);
                    text(canvas,company+" / CREDENCIALES",MARGIN,MARGIN+10,layout.width-2*MARGIN,12,true);
                    Set<String> names=new LinkedHashSet<>();for(Entry entry:sheet)names.add(entry.plant);
                    text(canvas,String.join(" / ",names)+" - "+sheet.size()+" trabajadores en esta hoja",MARGIN,MARGIN+23,layout.width-2*MARGIN,8.5f,false);
                    for(int i=0;i<sheet.size();i++){
                        if(Thread.currentThread().isInterrupted())throw new InterruptedException();guard.check();
                        Entry entry=sheet.get(i);drawCard(canvas,entry.card,entry.plant,layout,i);
                    }
                    text(canvas,"QR "+layout.cm+" x "+layout.cm+" cm - Imprimir al 100% / Tamaño real",MARGIN,layout.height-MARGIN-10,layout.width-2*MARGIN,8,false);
                    text(canvas,"No ajustar a página - "+layout.paper.name()+" / "+layout.orientation()+" - Página "+number+" de "+total,MARGIN,layout.height-MARGIN,layout.width-2*MARGIN,7.5f,false);
                }finally{pdf.finishPage(page);}
            }
            guard.check();try(OutputStream out=new FileOutputStream(target)){pdf.writeTo(out);}guard.check();success=true;
        }finally{try{pdf.close();}finally{if(!success&&target.exists())target.delete();}}
    }
    private static void drawCard(Canvas canvas,Card card,String plant,Layout layout,int index)throws Exception {
        RectF box=layout.qrBox(index);
        Paint outline=new Paint(Paint.ANTI_ALIAS_FLAG);outline.setColor(0xFFAAAAAA);outline.setStyle(Paint.Style.STROKE);outline.setStrokeWidth(.4f);outline.setPathEffect(new DashPathEffect(new float[]{2,3},0));
        canvas.drawRect(layout.cardBox(index),outline);
        BitMatrix matrix=matrix(card.qr);Path modules=new Path();
        for(int y=0;y<matrix.getHeight();y++)for(int x=0;x<matrix.getWidth();){
            if(!matrix.get(x,y)){x++;continue;}int start=x;while(x<matrix.getWidth()&&matrix.get(x,y))x++;modules.addRect(start,y,x,y+1,Path.Direction.CW);
        }
        Paint ink=new Paint();ink.setColor(Color.BLACK);ink.setAntiAlias(false);canvas.save();canvas.translate(box.left,box.top);canvas.scale(box.width()/matrix.getWidth(),box.height()/matrix.getHeight());canvas.drawPath(modules,ink);canvas.restore();
        boolean small=layout.cm<=4;TextPaint name=new TextPaint(Paint.ANTI_ALIAS_FLAG);name.setColor(Color.BLACK);name.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));name.setTextSize(small?8.5f:10);
        StaticLayout label=StaticLayout.Builder.obtain(card.name,0,card.name.length(),name,(int)box.width()-4).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).build();
        canvas.save();canvas.translate(box.left+2,box.bottom+2);label.draw(canvas);canvas.restore();
        text(canvas,card.identity,box.left+2,box.bottom+(small?26:33),box.width()-4,small?8:9,true);
        text(canvas,plant,box.left+2,box.bottom+(small?34:42),box.width()-4,small?7.5f:8,false);
    }
    private static void text(Canvas canvas,String value,float x,float baseline,float width,float size,boolean bold){
        TextPaint p=new TextPaint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.BLACK);p.setTextSize(size);p.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
        canvas.drawText(TextUtils.ellipsize(value,p,width,TextUtils.TruncateAt.END).toString(),x,baseline,p);
    }
}
