import SwiftUI
import LocalAuthentication
import Security
import ComedorCore

@MainActor final class AppModel:ObservableObject {
 @Published private(set) var state=AppState()
 @Published var unlocked=false,busy=false,fatal="",message="",plant="",meal="almuerzo"
 private var file:EncryptedFile?;let transport=Transport()
 var company:CompanyState?{state.companies[state.selected]}
 var isDemo=false
 init(){
  #if DEBUG
  if ProcessInfo.processInfo.arguments.contains("--demo"){do{state=try Demo.state();isDemo=true;unlocked=true;plant=company?.plants.first ?? "";return}catch{fatal=error.localizedDescription;return}}
  #endif
  do{let folder=try FileManager.default.url(for:.applicationSupportDirectory,in:.userDomainMask,appropriateFor:nil,create:true).appendingPathComponent("ComedorControl",isDirectory:true);file=try EncryptedFile(url:folder.appendingPathComponent("control.bin"),key:Self.key());state=try file!.load();plant=company?.plants.first ?? ""}catch{fatal="No se pudo abrir la base protegida. Conserva la app y sus datos. \(error.localizedDescription)"}
 }
 static func key()throws->Data{
  let query:[String:Any]=[kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:"bo.colbert.control.storage",kSecAttrAccount as String:"database"]
  var value:CFTypeRef?;var read=query;read[kSecReturnData as String]=true
  let status=SecItemCopyMatching(read as CFDictionary,&value)
  if status==errSecSuccess,let value=value as? Data{return value}
  guard status==errSecItemNotFound else{throw ComedorError("Desbloquea el iPhone para acceder a la clave local.")}
  var bytes=[UInt8](repeating:0,count:32);guard SecRandomCopyBytes(kSecRandomDefault,bytes.count,&bytes)==errSecSuccess else{throw ComedorError("No se pudo crear la clave local.")}
  let data=Data(bytes);var add=query;add[kSecValueData as String]=data;add[kSecAttrAccessible as String]=kSecAttrAccessibleWhenUnlockedThisDeviceOnly
  guard SecItemAdd(add as CFDictionary,nil)==errSecSuccess else{throw ComedorError("No se pudo guardar la clave local.")};return data
 }
 func unlock()async{if isDemo{unlocked=true;return};let context=LAContext();do{let success=try await context.evaluatePolicy(.deviceOwnerAuthentication,localizedReason:"Acceder al control del comedor y a los datos de trabajadores.");unlocked=success}catch{message="Usa Face ID, Touch ID o el código del iPhone. Configura un código de bloqueo en Ajustes si aún no tienes uno."}}
 func lock(){if !isDemo{unlocked=false}}
 func save(_ value:AppState)throws{if !isDemo{guard let file else{throw ComedorError("La base no está disponible.")};try file.save(value)};state=value}
 func store(_ value:CompanyState)throws{var next=state;next.companies[value.profile.id]=value;try save(next)}
 func select(_ id:String){guard !busy else{return};do{var next=state;next.selected=id;try save(next);plant=company?.plants.first ?? "";message=""}catch{message=error.localizedDescription}}
 func rename(_ name:String)throws{let clean=name.trimmingCharacters(in:.whitespacesAndNewlines);guard (3...100).contains(clean.count)else{throw ComedorError("Escribe tu nombre, entre 3 y 100 caracteres.")};var next=state;next.operatorName=clean;try save(next)}
 func activate(_ qr:String)async throws{
  guard !busy else{throw ComedorError("Espera a que termine la operación.")};let profile=try Profile(qr:qr)
  if let current=state.companies[profile.id]{guard current.profile==profile else{throw ComedorError("Ya hay datos de esta empresa. Conserva la activación original y contacta con Jesús.")}}
  else{var next=state;next.companies[profile.id]=CompanyState(profile:profile);try save(next)}
  select(profile.id);await synchronize()
 }
 func synchronize()async{
  guard !busy,let original=company,!isDemo else{return};busy=true;defer{busy=false};message="Sincronizando \(original.profile.id.uppercased())…"
  var working=original
  do{working.license=try await transport.license(working);working.checkpoint();try store(working)}catch{if !working.permitted{message="No se pudo validar el permiso: \(error.localizedDescription)"}}
  do{let p=working.profile;working=try await Synchronizer.run(working){[transport] request in try await transport.control(p,request)};try store(working);if !working.plants.contains(plant){plant=working.plants.first ?? ""};message="Datos actualizados · \(Wire.date(working.lastSync,"HH:mm"))"}catch{message="\(error.localizedDescription) Los datos y entregas pendientes se conservan."}
 }
 func refreshLicense()async{guard !busy,var c=company,!isDemo else{return};busy=true;defer{busy=false};do{c.license=try await transport.license(c);c.checkpoint();try store(c);message=c.permitted ? "Permiso vigente guardado en este iPhone.":"El servidor de permisos no habilitó esta empresa. Contacta con Jesús."}catch{message="\(error.localizedDescription) \(c.permitted ? "Tu permiso vigente se conserva.":"")"}}
 func mutationState()throws->CompanyState{guard !busy,let c=company,c.initialized else{throw ComedorError("Primero activa la empresa y sincroniza su padrón.")};guard c.permitted else{throw ComedorError("El permiso no está vigente. Compruébalo en Licencia.")};guard c.plants.contains(plant)else{throw ComedorError("Selecciona una planta.")};return c}
 func preview(_ qr:String)throws->J{var c=try mutationState();return try Ledger.resolve(&c,qr:qr,now:Wire.now)}
 func scan(_ qr:String)async throws->J{
  var c=try mutationState();guard c.pending==nil else{throw ComedorError("Hay una entrega sin confirmar. Usa Comprobar entrega antes de continuar.")}
  busy=true;defer{busy=false};let request:J=["action":"scan","requestId":.string(UUID().uuidString.lowercased()),"qr":.string(qr),"plant":.string(plant),"meal":.string(meal)],op=c.op(name:state.operatorName,plant:plant)
  #if DEBUG
  if isDemo{let r=try Ledger.scanLocal(&c,request:request,op:op,now:Wire.now);try store(c);return r}
  #endif
  // Flush earlier local meals before authorizing online; a preflight failure has made no new meal request.
  do{let profile=c.profile
   if !c.outbox.isEmpty || !c.rosterOutbox.isEmpty{c=try await Synchronizer.run(c){[transport] in try await transport.control(profile,$0)}}
   else{let boot=try Synchronizer.checked(await transport.control(profile,["action":"bootstrap"]));guard boot["cluster"].s==profile.cluster,boot["epoch"].i>0 else{throw ComedorError("Servidor incompatible.")};c.epoch=boot["epoch"].i}
   try store(c)
  }catch{
   let result=try Ledger.scanLocal(&c,request:request,op:op,now:Wire.now);try store(c);message="Sin conexión al servidor. Entregas guardadas para sincronizar.";return result
  }
  guard c.permitted else{throw ComedorError("El permiso venció. Compruébalo en Licencia.")}
  c.pending=["request":request,"operator":op,"epoch":.num(c.epoch)];try store(c)
  let reply:J
  do{reply=try await transport.control(c.profile,["action":"scan","request":request,"operator":op,"epoch":.num(c.epoch)])}catch{message="Entrega sin confirmar. No entregues otra ración hasta comprobarla.";throw ComedorError(message)}
  return try settle(&c,reply:reply)
 }
 func settle(_ c:inout CompanyState,reply:J)throws->J{
  let status=reply["status"].s
  if reply["ok"].b && ["accepted","recovered","duplicate"].contains(status){if !reply["receipt"].s.isEmpty{try Ledger.acceptReceipt(&c,value:reply["receipt"].s)}else if status=="accepted" || status=="recovered"{throw ComedorError("El servidor no adjuntó la confirmación de la entrega.")};c.pending=nil;c.checkpoint();try store(c);return reply}
  if reply["definitive"].b{c.pending=nil;try store(c);throw ComedorError(reply["error"].s.isEmpty ? "El servidor no autorizó la entrega.":reply["error"].s)}
  throw ComedorError("La entrega todavía no está confirmada. Compruébala antes de continuar.")
 }
 func recover()async throws->J{guard !busy,var c=company,let pending=c.pending else{throw ComedorError("No hay una entrega pendiente.")};busy=true;defer{busy=false};let reply=try await transport.control(c.profile,["action":"recover","request":pending["request"]]);return try settle(&c,reply:reply)}
 func addWorker(document:String,name:String)throws->(J,String){var c=try mutationState();let value=try Ledger.addWorker(&c,document:document,name:name,plant:plant,op:c.op(name:state.operatorName,plant:plant),now:Wire.now);try store(c);return value}
 func card(_ person:J)throws->String{let c=try mutationState();return try Ledger.workerCard(c,person:person,op:c.op(name:state.operatorName,plant:plant),now:Wire.now)}
 func visitor(name:String,document:String,cost:String,expires:String,meals:[String])throws->String{let c=try mutationState();return try Ledger.visitorCard(c,name:name,document:document,cost:cost,plant:plant,expires:expires,allowed:meals,op:c.op(name:state.operatorName,plant:plant),now:Wire.now)}
 func wallet(person:J,pairing:String?=nil,revoke:Bool=false,expected:Int64=0)async throws->J{
  let c=try mutationState();busy=true;defer{busy=false};var r:J=["action":.string(pairing==nil && !revoke ? "walletStatus":revoke ? "walletRevoke":"walletBind"),"personId":person["id"],"expected":.num(expected),"identityChecked":true,"requestId":.string(UUID().uuidString.lowercased())]
  if let pairing{r["pairing"] = .string(pairing)};return try Synchronizer.checked(await transport.control(c.profile,r))
 }
 func records(date:Date,monthly:Bool,allPlants:Bool)->[J]{let prefix=Wire.date(Int64(date.timeIntervalSince1970*1000),monthly ? "yyyy-MM":"yyyy-MM-dd");return (company?.records.values.filter{$0["fecha"].s.hasPrefix(prefix) && (allPlants || $0["planta"].s==plant)} ?? []).sorted{$0["fecha"].s+$0["hora"].s > $1["fecha"].s+$1["hora"].s}}
}
