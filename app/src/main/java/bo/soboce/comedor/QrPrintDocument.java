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
    static final float CM=72f/2.54f,MARGIN=CM,HEADER=40,FOOTER=24,GAP=12,CAPTION=60;
    static final class Layout {
        final Paper paper;final int cm,columns=2,rows,perPage;final float qr,cardHeight,left,top;
        Layout(Paper paper,int cm){
            if(paper==null||cm!=7&&cm!=8)throw new IllegalArgumentException("Elige QR de 7 u 8 cm.");
            this.paper=paper;this.cm=cm;qr=cm*CM;cardHeight=qr+CAPTION;
            rows=(int)Math.floor((paper.height-2*MARGIN-HEADER-FOOTER+GAP)/(cardHeight+GAP));perPage=columns*rows;
            left=(paper.width-columns*qr-GAP)/2;top=MARGIN+HEADER;
            if(rows<1||left<MARGIN)throw new IllegalArgumentException("El tamaño no cabe en esta hoja.");
        }
        RectF qrBox(int index){int col=index%columns,row=index/columns;float x=left+col*(qr+GAP),y=top+row*(cardHeight+GAP);return new RectF(x,y,x+qr,y+qr);}
    }
    static final class Card {
        final String id,name,identity,qr;
        Card(String id,String name,String identity,String qr){this.id=id;this.name=name;this.identity=identity;this.qr=qr;}
    }
    static final class Group {
        final String plant;final List<Card> cards;
        Group(String plant,List<Card> cards){this.plant=plant;this.cards=Collections.unmodifiableList(new ArrayList<>(cards));}
    }
    interface Guard {void check()throws Exception;}
    static int pages(List<Group> groups,Layout layout){int n=0;for(Group group:groups)n+=(group.cards.size()+layout.perPage-1)/layout.perPage;return n;}
    static BitMatrix matrix(String qr)throws WriterException {
        Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.MARGIN,4);hints.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M);hints.put(EncodeHintType.CHARACTER_SET,"UTF-8");
        return new QRCodeWriter().encode(qr,BarcodeFormat.QR_CODE,1,1,hints);
    }
    static void write(File target,String company,List<Group> groups,Layout layout,Guard guard)throws Exception {
        if(pages(groups,layout)==0)throw new IllegalArgumentException("No hay trabajadores activos para imprimir.");
        boolean success=false;PdfDocument pdf=new PdfDocument();
        try{
            int number=0,total=pages(groups,layout);guard.check();
            for(Group group:groups)for(int offset=0;offset<group.cards.size();offset+=layout.perPage){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();guard.check();
                PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(layout.paper.width,layout.paper.height,++number).create());
                try{
                    Canvas canvas=page.getCanvas();canvas.drawColor(Color.WHITE);
                    text(canvas,company+" / CREDENCIALES",MARGIN,MARGIN+12,layout.paper.width-2*MARGIN,14,true);
                    text(canvas,group.plant+" - "+group.cards.size()+" trabajadores",MARGIN,MARGIN+29,layout.paper.width-2*MARGIN,10,false);
                    for(int i=0;i<layout.perPage&&offset+i<group.cards.size();i++){
                        if(Thread.currentThread().isInterrupted())throw new InterruptedException();guard.check();
                        drawCard(canvas,group.cards.get(offset+i),group.plant,layout.qrBox(i));
                    }
                    text(canvas,"QR "+layout.cm+" x "+layout.cm+" cm - Imprimir al 100% / Tamaño real",MARGIN,layout.paper.height-MARGIN-13,layout.paper.width-2*MARGIN,9,false);
                    text(canvas,"No ajustar a página - "+layout.paper.label+" - Página "+number+" de "+total,MARGIN,layout.paper.height-MARGIN,layout.paper.width-2*MARGIN,8,false);
                }finally{pdf.finishPage(page);}
            }
            guard.check();try(OutputStream out=new FileOutputStream(target)){pdf.writeTo(out);}guard.check();success=true;
        }finally{try{pdf.close();}finally{if(!success&&target.exists())target.delete();}}
    }
    private static void drawCard(Canvas canvas,Card card,String plant,RectF box)throws Exception {
        Paint outline=new Paint(Paint.ANTI_ALIAS_FLAG);outline.setColor(0xFFAAAAAA);outline.setStyle(Paint.Style.STROKE);outline.setStrokeWidth(.4f);outline.setPathEffect(new DashPathEffect(new float[]{2,3},0));
        canvas.drawRect(box.left-3,box.top-3,box.right+3,box.bottom+CAPTION-3,outline);
        BitMatrix matrix=matrix(card.qr);Path modules=new Path();
        for(int y=0;y<matrix.getHeight();y++)for(int x=0;x<matrix.getWidth();){
            if(!matrix.get(x,y)){x++;continue;}int start=x;while(x<matrix.getWidth()&&matrix.get(x,y))x++;modules.addRect(start,y,x,y+1,Path.Direction.CW);
        }
        Paint ink=new Paint();ink.setColor(Color.BLACK);ink.setAntiAlias(false);canvas.save();canvas.translate(box.left,box.top);canvas.scale(box.width()/matrix.getWidth(),box.height()/matrix.getHeight());canvas.drawPath(modules,ink);canvas.restore();
        TextPaint name=new TextPaint(Paint.ANTI_ALIAS_FLAG);name.setColor(Color.BLACK);name.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));name.setTextSize(10);
        StaticLayout label=StaticLayout.Builder.obtain(card.name,0,card.name.length(),name,(int)box.width()-10).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).build();
        canvas.save();canvas.translate(box.left+5,box.bottom+4);label.draw(canvas);canvas.restore();
        text(canvas,card.identity,box.left+5,box.bottom+38,box.width()-10,10,true);
        text(canvas,plant,box.left+5,box.bottom+52,box.width()-10,9,false);
    }
    private static void text(Canvas canvas,String value,float x,float baseline,float width,float size,boolean bold){
        TextPaint p=new TextPaint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.BLACK);p.setTextSize(size);p.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
        canvas.drawText(TextUtils.ellipsize(value,p,width,TextUtils.TruncateAt.END).toString(),x,baseline,p);
    }
}
