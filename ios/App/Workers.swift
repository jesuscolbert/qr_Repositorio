import SwiftUI
import ComedorCore

struct CardPresentation:Identifiable{let id=UUID();let name:String,qr:String}
struct WorkerPresentation:Identifiable{let person:J;var id:String{person["id"].s}}
struct WorkersPanel:View {
 @EnvironmentObject var model:AppModel
 @State var query=""
 @State var showNew=false
 @State var error=""
 @State var card:CardPresentation?
 @State var wallet:WorkerPresentation?
 @State var printSheet=false
 var people:[J]{(model.company?.people.values.filter{$0["tipo"].s != "VISITA" && $0["planta"].s==model.plant && (query.isEmpty || ($0["nombre"].s+" "+$0["documento"].s+" "+$0["sap"].s).localizedCaseInsensitiveContains(query))} ?? []).sorted{$0["nombre"].s<$1["nombre"].s}}
 var body:some View{VStack(alignment:.leading,spacing:18){HStack{Button{showNew=true}label:{Label("Nuevo trabajador",systemImage:"person.badge.plus")};Spacer();Button{printSheet=true}label:{Image(systemName:"printer")}.accessibilityLabel("Imprimir QRs de la planta")}.font(.subheadline.bold()).disabled(model.busy || model.company?.permitted != true);TextField("Buscar nombre, carnet o SAP",text:$query).textFieldStyle(.roundedBorder);Text("\(people.count) trabajadores · \(model.plant)").font(.footnote).foregroundStyle(.secondary);if !error.isEmpty{Text(error).foregroundStyle(.red)}
  LazyVStack(spacing:12){ForEach(Array(people.enumerated()),id:\.offset){_,p in VStack(alignment:.leading,spacing:14){HStack{Image(systemName:"person.crop.circle.fill").font(.largeTitle).foregroundStyle(Style.teal);VStack(alignment:.leading){Text(p["nombre"].s).font(.headline);Text(p["documento"].s.isEmpty ? "SAP \(p["sap"].s)":"CI \(p["documento"].s)").font(.caption).foregroundStyle(.secondary)}};HStack{Button{do{card=CardPresentation(name:p["nombre"].s,qr:try model.card(p))}catch{self.error=error.localizedDescription}}label:{Label("Ver QR",systemImage:"qrcode")};Spacer();Button{wallet=WorkerPresentation(person:p)}label:{Label("Vincular celular",systemImage:"iphone")}}.font(.subheadline).disabled(model.busy || !p["activo"].b || model.company?.permitted != true)}.card()}}
 }.sheet(isPresented:$showNew){NewWorkerView()}.sheet(item:$card){CardView(card:$0)}.sheet(item:$wallet){WalletView(person:$0.person)}.sheet(isPresented:$printSheet){PrintView()}}
}
struct NewWorkerView:View {
 @EnvironmentObject var model:AppModel
 @Environment(\.dismiss) var dismiss
 @State var ci=""
 @State var name=""
 @State var error=""
 @State var card:CardPresentation?
 var body:some View {
  NavigationStack {
   Form {
    Section("Alta en \(model.plant)") {
     TextField("Número de carnet",text:$ci).textInputAutocapitalization(.characters).autocorrectionDisabled()
     TextField("Nombre completo (opcional)",text:$name).textContentType(.name)
     Text("El carnet conserva sus ceros y será su identificador. Si ya existe, se mantiene la misma persona.").font(.footnote)
    }
    Section {
     Button("Crear trabajador y generar QR") {
      do{let v=try model.addWorker(document:ci,name:name);card=CardPresentation(name:v.0["nombre"].s,qr:v.1)}catch{self.error=error.localizedDescription}
     }.disabled(model.busy)
     Text(error).foregroundStyle(.red)
    }
   }.navigationTitle("Nuevo trabajador")
    .toolbar{ToolbarItem(placement:.cancellationAction){Button("Cerrar"){dismiss()}}}
  }.sheet(item:$card,onDismiss:{dismiss()}){CardView(card:$0)}
 }
}
struct VisitorPanel:View {@EnvironmentObject var model:AppModel
 @State var name=""
 @State var ci=""
 @State var cost=""
 @State var date=Date()
 @State var allowed:Set<String>=["almuerzo"]
 @State var error=""
 @State var card:CardPresentation?
 var body:some View{VStack(alignment:.leading,spacing:18){Text("Pase de visitante").font(.title2.bold());Text("Autorización limitada a esta planta y a las comidas elegidas.").font(.subheadline).foregroundStyle(.secondary);TextField("Nombre del visitante",text:$name);TextField("Carnet o documento",text:$ci);TextField("Centro de costo / motivo",text:$cost);DatePicker("Válido hasta",selection:$date,in:Date()...Date().addingTimeInterval(30*86400),displayedComponents:.date);ForEach(Ledger.meals,id:\.self){meal in Toggle(meal.capitalized,isOn:Binding(get:{allowed.contains(meal)},set:{if $0{allowed.insert(meal)}else{allowed.remove(meal)}}))};Button("Crear QR de visita"){do{let qr=try model.visitor(name:name,document:ci,cost:cost,expires:Wire.day(Int64(date.timeIntervalSince1970*1000)),meals:Ledger.meals.filter{allowed.contains($0)});card=CardPresentation(name:name,qr:qr)}catch{self.error=error.localizedDescription}}.buttonStyle(PrimaryButton()).disabled(model.busy || model.company?.permitted != true);Text(error).foregroundStyle(.red)}.textFieldStyle(.roundedBorder).card().sheet(item:$card){CardView(card:$0)}}
}
struct WalletView:View {
 let person:J;@EnvironmentObject var model:AppModel;@Environment(\.dismiss) var dismiss;@Environment(\.scenePhase) var phase
 @State var binding:J = .null
 @State var checked=false
 @State var loaded=false
 @State var scanning=false
 @State var working=false
 @State var error=""
 @State var certificate:CardPresentation?
 @State var revokeConfirm=false
 var body:some View{NavigationStack{ScrollView{VStack(alignment:.leading,spacing:20){Text(person["nombre"].s).font(.title2.bold());Text("Un celular personal por trabajador").font(.headline);Text("Comprueba presencialmente su carnet. En Mi Comedor, el trabajador muestra el QR de vinculación; escanéalo y después muéstrale la autorización.");Toggle("Verifiqué al titular y su carnet",isOn:$checked);if binding != .null{Text(binding["active"].b ? "Ya tiene un celular vinculado. Una nueva vinculación reemplazará el anterior.":"La vinculación anterior está revocada.").font(.footnote)}
  if scanning{CameraView(active:phase == .active && !working,receive:{pairing in guard !working else{return};working=true;Task{defer{working=false;scanning=false};do{let r=try await model.wallet(person:person,pairing:pairing,expected:binding["version"].i);certificate=CardPresentation(name:"Autorizar a \(person["nombre"].s)",qr:r["certificate"].s)}catch{self.error=error.localizedDescription;loaded=false}}},failure:{error=$0}).frame(height:320).clipShape(RoundedRectangle(cornerRadius:24))}
  else{Button("Escanear vinculación del trabajador"){scanning=true}.buttonStyle(PrimaryButton()).disabled(!loaded || !checked || model.busy)}
  if binding["active"].b{Button("Revocar celular anterior",role:.destructive){revokeConfirm=true}.disabled(!checked || !loaded || model.busy)};Button("Actualizar estado"){Task{await load()}}.disabled(model.busy);Text(error).font(.footnote).foregroundStyle(.red);Text("La autorización dura hasta 30 días. Si desinstala Mi Comedor o cambia de celular, debe volver a vincularse con el encargado. Los equipos sin conexión reconocerán una revocación al sincronizar.").font(.footnote).foregroundStyle(.secondary)}.padding(24)}.navigationTitle("Credencial digital").toolbar{ToolbarItem(placement:.cancellationAction){Button("Cerrar"){dismiss()}}}.task{await load()}.sheet(item:$certificate,onDismiss:{Task{await load()}}){CardView(card:$0)}.confirmationDialog("¿Revocar el celular del trabajador?",isPresented:$revokeConfirm,titleVisibility:.visible){Button("Revocar celular",role:.destructive){Task{do{_ = try await model.wallet(person:person,revoke:true,expected:binding["version"].i);await load()}catch{self.error=error.localizedDescription;loaded=false}}}}}
 func load()async{do{let r=try await model.wallet(person:person);binding=r["binding"];loaded=true;error=""}catch{self.error=error.localizedDescription;loaded=false}}
}
