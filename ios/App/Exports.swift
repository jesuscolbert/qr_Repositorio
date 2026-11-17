import SwiftUI
import CoreImage.CIFilterBuiltins
import ComedorCore

struct SharedFile:Identifiable{let url:URL;var id:String{url.path}}
struct ShareView:UIViewControllerRepresentable{let items:[Any];func makeUIViewController(context:Context)->UIActivityViewController{UIActivityViewController(activityItems:items,applicationActivities:nil)};func updateUIViewController(_ controller:UIActivityViewController,context:Context){}}
enum Output {
 static func qr(_ text:String)->UIImage?{let filter=CIFilter.qrCodeGenerator();filter.message=Data(text.utf8);filter.correctionLevel="M";guard let base=filter.outputImage,let image=CIContext().createCGImage(base.transformed(by:CGAffineTransform(scaleX:8,y:8)),from:base.extent.applying(CGAffineTransform(scaleX:8,y:8)))else{return nil};return UIImage(cgImage:image)}
 static func save(_ data:Data,name:String)throws->URL{let folder=FileManager.default.temporaryDirectory.appendingPathComponent("exports",isDirectory:true);try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true);let url=folder.appendingPathComponent(name);try data.write(to:url,options:[.atomic,.completeFileProtection]);return url}
 static func pdf(company:String,plant:String,people:[J],size:Double,paper:String,card:(J)throws->String)throws->Data{
  let page=CGSize(width:612,height:paper=="Carta" ? 792:paper=="Oficio (21,6 × 33 cm)" ? 936:1008),side=size/2.54*72,margin:CGFloat=28
  let rows=Int((page.height-90)/(side+24)),columns=Int((page.width-2*margin)/side),perPage=rows*columns
  guard perPage>0,!people.isEmpty else{throw ComedorError("No hay trabajadores activos para imprimir.")}
  var cards:[(J,UIImage)]=[];for p in people{guard let image=qr(try card(p))else{throw ComedorError("No se pudo generar el QR.")};cards.append((p,image))}
  let renderer=UIGraphicsPDFRenderer(bounds:CGRect(origin:.zero,size:page));return renderer.pdfData{context in
   for (index,item) in cards.enumerated(){if index%perPage==0{context.beginPage();draw("\(company.uppercased()) · \(plant)",CGRect(x:margin,y:22,width:page.width-2*margin,height:24),font:16,bold:true);draw("Tarjetas de \(Int(size)) × \(Int(size)) cm · imprimir al 100%, sin ajustar",CGRect(x:margin,y:45,width:page.width-2*margin,height:20),font:9)}
    let position=index%perPage,x=margin+CGFloat(position%columns)*side,y=76+CGFloat(position/columns)*(side+24),rect=CGRect(x:x,y:y,width:side,height:side)
    UIColor(white:0.78,alpha:1).setStroke();context.cgContext.setLineWidth(0.5);context.cgContext.stroke(rect)
    let qrSide=side-52;item.1.draw(in:CGRect(x:x+26,y:y+12,width:qrSide,height:qrSide));draw(item.0["nombre"].s,CGRect(x:x+8,y:y+side-40,width:side-16,height:26),font:10,bold:true)
    let ci=item.0["documento"].s;draw(ci.isEmpty ? "SAP \(item.0["sap"].s)":"CI \(ci)",CGRect(x:x+8,y:y+side-14,width:side-16,height:13),font:8)
   }
  }
 }
 static func draw(_ text:String,_ rect:CGRect,font:CGFloat,bold:Bool=false){let p=NSMutableParagraphStyle();p.lineBreakMode = .byTruncatingTail;(text as NSString).draw(in:rect,withAttributes:[.font:bold ? UIFont.boldSystemFont(ofSize:font):UIFont.systemFont(ofSize:font),.foregroundColor:UIColor(red:0.05,green:0.12,blue:0.22,alpha:1),.paragraphStyle:p])}
}
struct CardView:View {let card:CardPresentation;@Environment(\.dismiss) var dismiss;@State var share:SharedFile?,error=""
 var body:some View{NavigationStack{VStack(spacing:24){Text(card.name).font(.title2.bold()).multilineTextAlignment(.center);if let image=Output.qr(card.qr){Image(uiImage:image).interpolation(.none).resizable().scaledToFit().padding(24).background(.white,in:RoundedRectangle(cornerRadius:24));Button("Compartir QR"){do{guard let data=image.pngData()else{return};share=SharedFile(url:try Output.save(data,name:"Credencial-\(UUID().uuidString.prefix(8)).png"))}catch{self.error=error.localizedDescription}}.buttonStyle(PrimaryButton())};Text("Credencial personal. Entrégala únicamente a su titular.").font(.footnote).foregroundStyle(.secondary);Text(error).foregroundStyle(.red)}.padding(26).background(Style.paper).navigationTitle("Credencial").toolbar{ToolbarItem(placement:.cancellationAction){Button("Cerrar"){dismiss()}}}}.sheet(item:$share){ShareView(items:[$0.url])}}
}
struct PrintView:View {@EnvironmentObject var model:AppModel;@Environment(\.dismiss) var dismiss;@State var size=7.0,paper="Carta",share:SharedFile?,error=""
 var body:some View{NavigationStack{Form{Section("QRs de \(model.plant)"){Picker("Tamaño de tarjeta",selection:$size){Text("7 × 7 cm").tag(7.0);Text("8 × 8 cm").tag(8.0)};Picker("Hoja",selection:$paper){Text("Carta").tag("Carta");Text("Oficio (21,6 × 33 cm)").tag("Oficio (21,6 × 33 cm)");Text("Legal (21,6 × 35,6 cm)").tag("Legal")};Text("Se incluyen los trabajadores activos de esta planta. Imprime al 100% para conservar las dimensiones.").font(.footnote)};Button("Generar PDF de la planta"){do{let people=(model.company?.people.values.filter{$0["activo"].b && $0["tipo"].s != "VISITA" && $0["planta"].s==model.plant} ?? []).sorted{$0["nombre"].s<$1["nombre"].s};let data=try Output.pdf(company:model.state.selected,plant:model.plant,people:people,size:size,paper:paper,card:model.card);share=SharedFile(url:try Output.save(data,name:"QRs-\(model.state.selected)-\(Wire.day(Wire.now)).pdf"))}catch{self.error=error.localizedDescription}};Text(error).foregroundStyle(.red)}.navigationTitle("Imprimir credenciales").toolbar{ToolbarItem(placement:.cancellationAction){Button("Cerrar"){dismiss()}}}}.sheet(item:$share){ShareView(items:[$0.url])}}
}
struct ReportsPanel:View {@EnvironmentObject var model:AppModel;@State var date=Date(),monthly=false,allPlants=true,error="",share:SharedFile?
 var rows:[J]{model.records(date:date,monthly:monthly,allPlants:allPlants)}
 var body:some View{VStack(alignment:.leading,spacing:18){Picker("Período",selection:$monthly){Text("Diario").tag(false);Text("Mensual").tag(true)}.pickerStyle(.segmented);DatePicker("Fecha del reporte",selection:$date,displayedComponents:.date);Toggle("Todas las plantas",isOn:$allPlants)
  Button{export()}label:{Label(monthly ? "Excel ejecutivo del mes":"Excel ejecutivo del día",systemImage:"square.and.arrow.up")}.buttonStyle(PrimaryButton()).disabled(model.company?.initialized != true || model.company?.permitted != true).accessibilityIdentifier("exportExcel")
  Button{Task{await model.synchronize()}}label:{Label("Actualizar reporte",systemImage:"arrow.clockwise")}.disabled(model.busy);if !error.isEmpty{Text(error).foregroundStyle(.red)}
  HStack{Metric(title:"Raciones",value:"\(rows.count)",icon:"fork.knife");Metric(title:"Personas",value:"\(Set(rows.map{$0["persona"].s}).count)",icon:"person.2")}
  HStack{ForEach(Ledger.meals,id:\.self){meal in VStack{Text("\(rows.filter{$0["comida"].s==meal}.count)").font(.title3.bold());Text(meal.capitalized).font(.caption2)}.frame(maxWidth:.infinity)}}.card()
  Text("Se muestran los datos guardados en este equipo. La sincronización descarga los últimos 35 días y conserva el historial ya recibido. Puede haber entregas pendientes en otros teléfonos.").font(.footnote).foregroundStyle(.secondary)
  Text("Movimiento de entregas").font(.title3.bold());LazyVStack(alignment:.leading,spacing:12){ForEach(Array(rows.enumerated()),id:\.offset){_,r in RecordRow(record:r).card()};if rows.isEmpty{Text("No hay entregas disponibles para este período y filtro.").foregroundStyle(.secondary).card()}}
 }.sheet(item:$share){ShareView(items:[$0.url])}}
 func export(){guard let c=model.company,c.permitted else{return};do{let period=Wire.date(Int64(date.timeIntervalSince1970*1000),monthly ? "yyyy-MM":"yyyy-MM-dd"),data=try Excel.workbook(company:c.profile.id,period:period,plant:allPlants ? "Todas las plantas":model.plant,records:rows,lastSync:c.lastSync,pending:c.outbox.count);share=SharedFile(url:try Output.save(data,name:"\(c.profile.id.uppercased())-\(period).xlsx"))}catch{self.error=error.localizedDescription}}
}
