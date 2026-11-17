import Foundation

public enum Ledger {
 public static let meals=["desayuno","almuerzo","cena","dieta"]
 public static func allowance(_ meal:String)->String{meal=="dieta" ? "almuerzo":meal}
 public static func document(_ raw:String)throws->String{let ci=raw.uppercased().components(separatedBy:.whitespacesAndNewlines).joined();try require(Wire.matches(ci,"[0-9]{5,20}(?:-[A-Z0-9]{1,6})?"),"Escribe el carnet sin puntos. Complemento: 1234567-1A.");return ci}
 static func validateOperator(_ op:J,state:CompanyState,plant:String)throws{try require(["admin","encargado"].contains(op["role"].s) && (3...100).contains(op["name"].s.count) && state.plants.contains(op["plant"].s) && state.plants.contains(plant) && (op["role"].s=="admin" || op["plant"].s==plant),"Encargado o planta sin autorización.")}
 public static func workerCard(_ state:CompanyState,person:J,op:J,now:Int64)throws->String{
  try require(person["activo"].b && person["tipo"].s != "VISITA","Trabajador inactivo.");try validateOperator(op,state:state,plant:person["planta"].s)
  if !person["card"].s.isEmpty{return person["card"].s}
  let b:J=["v":3,"cluster":.string(state.profile.cluster),"issuer":-1,"plant":person["planta"],"issued":.num(now),"kind":"employee","id":person["id"],"device":.string(state.device),"operator":op]
  return try card3(state,b)
 }
 static func card3(_ state:CompanyState,_ body:J)throws->String{let data=try body.text();let qr="SB3:"+Wire.b64(Data(data.utf8))+"."+Wire.b64(Data(state.profile.mac("card|"+data).utf8));try require(qr.count<=2048,"La tarjeta contiene demasiados datos.");return qr}
 public static func visitorCard(_ state:CompanyState,name:String,document:String,cost:String,plant:String,expires:String,allowed:[String],op:J,now:Int64)throws->String{
  let ci=document.uppercased().filter{$0.isASCII && ($0.isLetter || $0.isNumber)};try require((3...100).contains(name.count) && (5...60).contains(ci.count) && (1...50).contains(cost.count),"Completa nombre, carnet y centro de costo.")
  for p in state.people.values where p["tipo"].s != "VISITA" {let ids=[p["documento"].s,p["sap"].s];try require(!ids.contains{!$0.isEmpty && $0.uppercased().filter{$0.isLetter || $0.isNumber}==ci},"Este documento ya es de un trabajador. Usa su credencial.")}
  try dates(expires,now);try require(!allowed.isEmpty && Set(allowed).isSubset(of:Set(meals)),"Selecciona comidas válidas.");try validateOperator(op,state:state,plant:plant)
  return try card3(state,["v":3,"cluster":.string(state.profile.cluster),"issuer":-1,"plant":.string(plant),"issued":.num(now),"kind":"visitor","id":.string("VIS-"+Wire.hash(ci)),"name":.string(name),"cost":.string(cost),"expires":.string(expires),"meals":.string(allowed.joined(separator:",")),"device":.string(state.device),"operator":op])
 }
 static func dates(_ expires:String,_ issued:Int64)throws{let f=DateFormatter();f.locale=Locale(identifier:"en_US_POSIX");f.timeZone=TimeZone(identifier:"America/La_Paz")!;f.dateFormat="yyyy-MM-dd";guard let end=f.date(from:expires),let start=f.date(from:Wire.day(issued))else{throw ComedorError("Fecha inválida.")};try require(f.string(from:end)==expires && end>=start && end.timeIntervalSince(start)<=30*86400,"La visita puede durar de hoy hasta 30 días.")}
 public static func addWorker(_ state:inout CompanyState,document raw:String,name rawName:String,plant:String,op:J,now:Int64)throws->(J,String){
  let ci=try document(raw),name=rawName.split(whereSeparator:{$0.isWhitespace}).joined(separator:" ");try require(name.isEmpty || (3...100).contains(name.count),"Usa un nombre de 3 a 100 caracteres o déjalo pendiente.");try validateOperator(op,state:state,plant:plant)
  if let p=state.people.values.first(where:{$0["documento"].s==ci && $0["tipo"].s != "VISITA"}){try require(p["activo"].b && p["planta"].s==plant,"Este carnet ya está registrado en \(p["planta"].s). Conserva su identidad.");return (p,try workerCard(state,person:p,op:op,now:now))}
  let seq=state.rosterSequence+1;try require(seq>0 && seq<2147483647 && now>0,"No se pudo crear el alta.")
  let b:J=["v":4,"cluster":.string(state.profile.cluster),"origin":.string(state.device),"seq":.num(seq),"time":.num(now),"operator":op,"ci":.string(ci),"name":.string(name),"plant":.string(plant)],body=try b.text()
  let qr="SB4:"+Wire.b64(Data(body.utf8))+"."+state.profile.mac("worker|"+body)
  try require(qr.count<=2048,"El nombre es demasiado largo para esta tarjeta.");let p=try acceptWorker(&state,qr:qr,now:now)
  state.rosterSequence=seq;if !state.rosterOutbox.contains(qr){state.rosterOutbox.append(qr)};return (p,qr)
 }
 public static func acceptWorker(_ state:inout CompanyState,qr:String,now:Int64)throws->J{
  try require(qr.count<=2048 && qr.hasPrefix("SB4:"),"Credencial inválida.");let parts=qr.dropFirst(4).split(separator:".",omittingEmptySubsequences:false);try require(parts.count==2,"QR incompleto.");let body=String(decoding:try Wire.unb64(String(parts[0])),as:UTF8.self)
  try require(Wire.equal(state.profile.mac("worker|"+body),String(parts[1])),"Alta alterada o de otra empresa.");let b=try J.parse(body),ci=try document(b["ci"].s)
  try require(b["v"].i==4 && b["cluster"].s==state.profile.cluster && Wire.matches(b["origin"].s,"[a-f0-9]{64}") && b["seq"].i>0 && b["seq"].i<2147483647 && b["time"].i>0 && b["time"].i<=now+120000 && b["ci"].s==ci,"Identidad o fecha del alta inválida.")
  try validateOperator(b["operator"],state:state,plant:b["plant"].s);try require(b["name"].s.isEmpty || (3...100).contains(b["name"].s.count),"Nombre inválido.")
  let id=state.catalog["personPrefix"].s+ci;try require(!state.catalog["seedIds"].a.map(\.s).contains(id) && !state.catalog["seedDocuments"].a.map(\.s).contains(ci),"Este carnet pertenece al padrón inicial. Usa su QR existente.")
  let old=state.people[id];if let old,!old["card"].s.isEmpty,Wire.hash(old["card"].s)<Wire.hash(qr){return old}
  let name=b["name"].s.isEmpty ? "CI \(ci) · Nombre pendiente":b["name"].s
  let p:J=["id":.string(id),"sap":"","documento":.string(ci),"documentoTipo":"CI","nombre":.string(name),"planta":b["plant"],"centroCosto":"","tipo":"TRABAJADOR","activo":.bool(old?["activo"].b ?? true),"qrHash":"","vence":"","comidas":"desayuno,almuerzo,cena","card":.string(qr),"rosterConflict":.bool(old != nil && old?["card"].s != qr)]
  state.people[id]=p;return p
 }
 public static func resolve(_ state:inout CompanyState,qr:String,now:Int64)throws->J{
  if qr.hasPrefix("CWQ1:"){return try wallet(state,qr:qr,now:now)}
  if qr.hasPrefix("SB4:"){let person=try acceptWorker(&state,qr:qr,now:now);if !state.rosterOutbox.contains(qr){state.rosterOutbox.append(qr)};return person}
  if Wire.matches(qr,"SB1:[a-f0-9]{64}"),let p=state.people.values.first(where:{$0["qrHash"].s==Wire.hash(qr)}){return p}
  try require(qr.count<=2048 && (qr.hasPrefix("SB3:") || qr.hasPrefix("SB2:")),"Este QR no pertenece al comedor.")
  let parts=qr.dropFirst(4).split(separator:".",omittingEmptySubsequences:false);try require(parts.count==2,"QR incompleto.");let body=String(decoding:try Wire.unb64(String(parts[0])),as:UTF8.self),b=try J.parse(body)
  try require(b["cluster"].s==state.profile.cluster && b["issued"].i>=0 && b["issued"].i<=now+120000,"QR de otra empresa o con fecha incorrecta.")
  if qr.hasPrefix("SB3:"){try require(b["v"].i==3 && Wire.equal(state.profile.mac("card|"+body),String(decoding:try Wire.unb64(String(parts[1])),as:UTF8.self)),"QR alterado.");try validateOperator(b["operator"],state:state,plant:b["plant"].s)}else{let issuer=Int(b["issuer"].i),keys=state.catalog["legacyKeys"].a;try require(b["v"].i==2 && keys.indices.contains(issuer) && state.catalog["legacyOperators"].a.indices.contains(issuer),"Emisor inválido.");try validateOperator(state.catalog["legacyOperators"].a[issuer],state:state,plant:b["plant"].s);try require(try Wire.p256(keys[issuer].s,"card|\(state.profile.cluster)|\(body)",try Wire.unb64(String(parts[1])).base64EncodedString()),"QR alterado.")}
  try require(state.plants.contains(b["plant"].s),"Planta desconocida.")
  if b["kind"].s=="employee"{guard let p=state.people[b["id"].s]else{throw ComedorError("Trabajador desconocido. Sincroniza primero.")};try require(p["tipo"].s != "VISITA" && p["planta"].s==b["plant"].s,"Tarjeta de otra planta de origen.");return p}
  try require(b["kind"].s=="visitor" && Wire.matches(b["id"].s,"VIS-[a-f0-9]{64}"),"Visitante inválido.");try dates(b["expires"].s,b["issued"].i)
  if let existing=state.people[b["id"].s]{try require(existing["activo"].b,"Visitante inactivo.")}
  try require((3...100).contains(b["name"].s.count) && (1...50).contains(b["cost"].s.count),"Datos de visitante inválidos.")
  return ["id":b["id"],"nombre":b["name"],"planta":b["plant"],"centroCosto":b["cost"],"tipo":"VISITA","activo":true,"vence":b["expires"],"comidas":b["meals"]]
 }
 static func wallet(_ state:CompanyState,qr:String,now:Int64)throws->J{
  try require(qr.count<=3400,"QR inválido.");let parts=qr.dropFirst(5).split(separator:".",omittingEmptySubsequences:false);try require(parts.count==4,"QR incompleto.");let encoded=String(parts[0]),mac=String(parts[1]),cert="CWC1:"+encoded+"."+mac,b=try J.parse(String(decoding:Wire.unb64(encoded),as:UTF8.self))
  try require(b["v"].i==1 && b["c"].s==state.profile.cluster && b["tag"].s==state.profile.mac("wallet-person|"+b["p"].s) && Wire.equal(state.profile.mac("wallet-cert|"+encoded),mac),"Credencial alterada o de otra empresa.")
  guard let step=Int64(parts[2]),step>0,abs(step-now/30000)<=2 else{throw ComedorError("QR vencido. Muestra el código actual.")}
  try require(b["r"].i>0 && b["i"].i>0 && b["e"].i>b["i"].i && b["e"].i-b["i"].i<=30*License.day && now>=b["i"].i-15000 && now<b["e"].i && now>=step*30000-15000 && now<step*30000+45000,"Credencial o QR vencidos.")
  try require(try Wire.p256(b["k"].s,"qr|\(cert)|\(step)",String(parts[3]),urlSafe:true),"El QR no corresponde a la clave del celular.")
  if let binding=state.bindings[b["tag"].s],binding["version"].i>=b["r"].i,binding["time"].i<=now{try require(binding["version"].i==b["r"].i && binding["active"].b && binding["keyHash"].s==Wire.hash(b["k"].s),"Celular revocado o reemplazado.")}
  guard let person=state.people[b["p"].s],person["activo"].b,person["tipo"].s != "VISITA" else{throw ComedorError("Trabajador activo no encontrado. Sincroniza el padrón.")};return person
 }
 public static func record(_ state:inout CompanyState,request:J,op:J,now:Int64)throws->J{
  try require(request["action"].s=="scan" && Wire.matches(request["requestId"].s,"[a-f0-9-]{36}"),"Solicitud inválida.");let meal=request["meal"].s,plant=request["plant"].s
  try require(meals.contains(meal),"Comida inválida.");try validateOperator(op,state:state,plant:plant)
  let p=try resolve(&state,qr:request["qr"].s,now:now),day=Wire.day(now),allowed=p["comidas"].s.split(separator:",").map(String.init)
  try require(p["activo"].b && (p["vence"].s.isEmpty || p["vence"].s>=day),"Persona inactiva o autorización vencida.");try require(allowed.contains(meal) || meal=="dieta" && allowed.contains("almuerzo"),"Comida no autorizada.");try require(p["tipo"].s != "VISITA" || p["planta"].s==plant,"Visita autorizada en otra planta.")
  guard let rate=state.catalog["rates"].a.filter({$0["desde"].s<=day}).max(by:{$0["desde"].s<$1["desde"].s}),rate[allowance(meal)].n.isFinite,rate[allowance(meal)].n>=0,rate[allowance(meal)] != .null else{throw ComedorError("Falta una tarifa válida.")}
  var r:J=["clave":.string(p["id"].s+"|"+day+"|"+allowance(meal)),"solicitud":request["requestId"],"persona":p["id"],"nombre":p["nombre"],"fecha":.string(day),"comida":.string(meal),"planta":.string(plant),"operador":op["name"],"hora":.string(Wire.date(now,"HH:mm:ss")),"precio":rate[allowance(meal)],"centroCosto":.string(p["centroCosto"].s),"qrHash":.string(Wire.hash(request["qr"].s))]
  if state.catalog["ratesConfigured"] == .bool(false){r["precioPendiente"]=true};return r
 }
 public static func scanLocal(_ state:inout CompanyState,request:J,op:J,now:Int64,uptime:Double=ProcessInfo.processInfo.systemUptime)throws->J{
  try require(state.pending==nil,"Hay una entrega sin confirmar. Compruébala antes de continuar.");try require(License.allowed(state.license,config:state.profile.license,wall:now,uptime:uptime),"Comprueba tu permiso en Licencia y pagos.");try require(now>0 && now>=state.lastMealTime-60000,"Activa fecha y hora automáticas.")
  let record=try record(&state,request:request,op:op,now:now)
  if let previous=state.records.values.first(where:{$0["clave"]==record["clave"]}){return ["ok":true,"status":"duplicate","person":record["nombre"],"plant":previous["planta"],"time":previous["hora"],"message":"Ya recibió esta comida. No entregar otra ración."]}
  let seq=state.sequence+1,body:J=["kind":"shared-meal-v1","origin":.string(state.device),"seq":.num(seq),"time":.num(now),"operator":op,"request":request,"record":record],raw=try body.text(),sealed:J=["body":.string(raw),"signature":.string(state.profile.mac("receipt|"+raw))]
  let value=try sealed.text();state.outbox.append(value);state.sequence=seq;state.lastMealTime=max(now,state.lastMealTime);try acceptReceipt(&state,value:value);state.checkpoint(wall:now,uptime:uptime)
  return ["ok":true,"status":"accepted","person":record["nombre"],"plant":record["planta"],"time":record["hora"],"message":"Guardado en este iPhone. Pendiente de compartir con los demás."]
 }
 public static func acceptReceipt(_ state:inout CompanyState,value:String)throws{let sealed=try J.parse(value),body=sealed["body"].s;try require(Wire.equal(state.profile.mac("receipt|"+body),sealed["signature"].s),"Entrega sin firma válida.");let b=try J.parse(body),r=b["record"],request=b["request"];try require(b["kind"].s=="shared-meal-v1" && Wire.matches(b["origin"].s,"[a-f0-9]{64}") && b["seq"].i>0 && r["solicitud"]==request["requestId"] && r["qrHash"].s==Wire.hash(request["qr"].s) && r["clave"].s==r["persona"].s+"|"+r["fecha"].s+"|"+allowance(r["comida"].s),"Entrega incompatible.");var stored=r;let id=b["origin"].s+":"+String(b["seq"].i);stored["registro"] = .string(id);stored["origen"]=r["operador"];state.records[id]=stored}
}
