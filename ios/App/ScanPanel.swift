import SwiftUI
import AudioToolbox
import ComedorCore

struct ScanPanel:View {
 @EnvironmentObject var model:AppModel;@Environment(\.scenePhase) var phase
 @State private var result:J?
 @State private var error=""
 @State private var processing=false
 @State private var identity:J?
 @State private var identityQR=""
 @State private var releaseTask:Task<Void,Never>?
 var body:some View{VStack(alignment:.leading,spacing:18){MealPicker()
  if model.company?.pending != nil{VStack(alignment:.leading,spacing:12){Label("Entrega sin confirmar",systemImage:"exclamationmark.triangle.fill").font(.title3.bold());Text("Puede haberse registrado en el servidor. No autorices otra ración para esta operación.");Button("Comprobar entrega"){Task{processing=true;defer{processing=false};do{show(try await model.recover())}catch{self.error=error.localizedDescription}}}.buttonStyle(PrimaryButton()).disabled(model.busy)}.card()}
  else if model.company?.permitted != true{Label("Comprueba tu permiso en el menú Licencia.",systemImage:"lock.fill").card()}
  else if result==nil && error.isEmpty{ZStack{
   camera.frame(height:340).clipShape(RoundedRectangle(cornerRadius:26))
   RoundedRectangle(cornerRadius:22).stroke(Color.white.opacity(0.9),style:StrokeStyle(lineWidth:3,dash:[28,14])).frame(width:230,height:230)
   VStack{Spacer();Text("Acerca el QR dentro del marco").font(.footnote.bold()).padding(12).background(.black.opacity(0.55),in:Capsule()).foregroundStyle(.white).padding(16)}
   if processing{Color.black.opacity(0.7);ProgressView("Comprobando entrega…").tint(.white).foregroundStyle(.white)}
  }.clipShape(RoundedRectangle(cornerRadius:26))
  }
  if let result{let accepted=result["status"].s=="accepted";VStack(spacing:12){Image(systemName:accepted ? "checkmark.circle.fill":"exclamationmark.octagon.fill").font(.system(size:64));Text(accepted ? "ACCESO PERMITIDO":result["status"].s=="recovered" ? "ENTREGA YA REGISTRADA":"YA RECIBIÓ SU COMIDA").font(.title2.bold()).multilineTextAlignment(.center);Text(result["person"].s).font(.title3.bold());Text(result["message"].s).multilineTextAlignment(.center);if !accepted{Button("Continuar con el siguiente QR"){clear()}.foregroundStyle(.white).padding(.top,8)}}.frame(maxWidth:.infinity,minHeight:300).padding(24).background(accepted ? Style.teal:Color(red:0.77,green:0.12,blue:0.17),in:RoundedRectangle(cornerRadius:24)).foregroundStyle(.white).accessibilityIdentifier("scanResult")}
  if !error.isEmpty{VStack(alignment:.leading,spacing:12){Label(error,systemImage:"exclamationmark.triangle.fill").foregroundStyle(.red);Button("Volver a escanear"){clear()}}.card()}
  Text("Dieta y almuerzo comparten el mismo límite diario. Comprueba que quien presenta la credencial sea su titular.").font(.footnote).foregroundStyle(.secondary)
  #if DEBUG
  if model.isDemo{Button("Escanear QR de demostración"){if let c=model.company,let person=c.people.values.first,let qr=try? model.card(person){receive(qr)}}.accessibilityIdentifier("demoScan")}
  #endif
 }.confirmationDialog("Verifica la identidad del trabajador",isPresented:Binding(get:{identity != nil},set:{if !$0{identity=nil}}),titleVisibility:.visible){Button("Confirmo identidad y entrego"){let qr=identityQR;identity=nil;deliver(qr)};Button("Cancelar",role:.cancel){identity=nil}}message:{Text("\(identity?["nombre"].s ?? "")\nCompara el nombre con el carnet y la persona presente.")}.onDisappear{releaseTask?.cancel()}}
 @ViewBuilder var camera:some View{
  #if DEBUG
  if model.isDemo{Style.navy.overlay{Image(systemName:"qrcode").font(.system(size:130)).foregroundStyle(.white.opacity(0.4))}}else{liveCamera}
  #else
  liveCamera
  #endif
 }
 var liveCamera:some View{CameraView(active:phase == .active && model.unlocked && !processing && result==nil && error.isEmpty && identity==nil,receive:receive,failure:{error=$0})}
 func receive(_ qr:String){guard !processing,result==nil,error.isEmpty,identity==nil,!model.busy else{return};if qr.hasPrefix("CWQ1:"){do{identity=try model.preview(qr);identityQR=qr}catch{self.error=error.localizedDescription};return};deliver(qr)}
 func deliver(_ qr:String){processing=true;Task{defer{processing=false};do{show(try await model.scan(qr))}catch{self.error=error.localizedDescription;UINotificationFeedbackGenerator().notificationOccurred(.error)}}}
 func show(_ r:J){result=r;error="";let accepted=r["status"].s=="accepted";UINotificationFeedbackGenerator().notificationOccurred(accepted ? .success:.error);AudioServicesPlaySystemSound(accepted ? 1057:1073);releaseTask?.cancel();if accepted{releaseTask=Task{try? await Task.sleep(nanoseconds:2_000_000_000);if !Task.isCancelled{clear()}}}}
 func clear(){releaseTask?.cancel();result=nil;error=""}
}
