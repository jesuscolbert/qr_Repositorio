import Foundation

public struct LicenseCache:Codable,Equatable,Sendable {
 public var envelope:J;public var publicKey:String;public var wall:Int64;public var uptime:Double;public var bootEstimate:Double;public var checkpoint:Int64;public var lastWall:Int64
 public init(envelope:J,key:String,wall:Int64,uptime:Double){self.envelope=envelope;publicKey=key;self.wall=wall;self.uptime=uptime;bootEstimate=Double(wall)/1000-uptime;checkpoint=0;lastWall=wall}
 public var claims:J{(try? J.parse(envelope["claims"].s)) ?? .null}
}
public enum License {
 public static let day:Int64=86_400_000
 public static func paid(_ c:J)->Bool{c["version"].i==2 && c["allowed"].b && c["grantKind"].s=="paid" && c["paidUntil"].i>c["issuedAt"].i && c["expiresAt"].i==c["paidUntil"].i}
 public static func verify(_ envelope:J,key:String,config:J,nonce:String?=nil)throws->J{
  try require(envelope["publicKey"].s==key && (try Wire.p256(key,envelope["claims"].s,envelope["signature"].s)),"No se pudo verificar el servidor de permisos.")
  let c=try J.parse(envelope["claims"].s),issued=c["issuedAt"].i,end=c["expiresAt"].i,paidUntil=c["paidUntil"].i
  try require([1,2].contains(c["version"].i) && c["contractId"].s==config["contractId"].s && c["productId"].s==config["productId"].s,"Permiso de otra empresa.")
  if let nonce{try require(c["nonce"].s==nonce,"Respuesta de otra consulta.")}
  try require(issued>0 && end>=issued && (paid(c) || end-issued<=day),"Vigencia de permiso inválida.")
  if c["version"].i==2{let kind=c["grantKind"].s;try require(["paid","temporary","denied"].contains(kind) && c["allowed"].b==(kind != "denied"),"Tipo de permiso inválido.");if kind=="paid"{try require(paidUntil>issued && end==paidUntil,"Permiso superior al período pagado.")};if kind=="temporary"{try require(paidUntil==0,"Permiso temporal inválido.")}}
  if c["allowed"].b{try require(end>issued && (paidUntil==0 || end<=paidUntil),"Permiso superior al período pagado.")};return c
 }
 public static func now(_ cache:LicenseCache,claims:J,wall:Int64,uptime:Double)->Int64{
  let same=abs(Double(wall)/1000-uptime-cache.bootEstimate)<120,elapsed=uptime-cache.uptime;let candidate:Int64
  if same && elapsed>=0{candidate=claims["issuedAt"].i+Int64(elapsed*1000)}else{guard paid(claims),cache.wall>0,wall>=cache.wall-120000,wall>=cache.lastWall-120000 else{return -1};candidate=claims["issuedAt"].i+max(0,wall-cache.wall)}
  return max(candidate,cache.checkpoint)
 }
 public static func allowed(_ cache:LicenseCache?,config:J,wall:Int64=Wire.now,uptime:Double=ProcessInfo.processInfo.systemUptime)->Bool{guard let cache,let c=try? verify(cache.envelope,key:cache.publicKey,config:config)else{return false};let time=now(cache,claims:c,wall:wall,uptime:uptime);return c["allowed"].b && time>=c["issuedAt"].i && time<c["expiresAt"].i}
 public static func warning(_ cache:LicenseCache?,config:J,wall:Int64=Wire.now,uptime:Double=ProcessInfo.processInfo.systemUptime)->String{guard let cache,allowed(cache,config:config,wall:wall,uptime:uptime)else{return ""};let c=cache.claims,remaining=c["paidUntil"].i-now(cache,claims:c,wall:wall,uptime:uptime);guard remaining>0 && remaining<=2*day else{return ""};let days=(remaining+day-1)/day;return "Tu licencia vence en \(days) \(days==1 ? "día":"días"). Puedes renovar con Jesús sin perder tu vigencia actual."}
 public static func renewed(_ envelope:J,previous:LicenseCache?,config:J,nonce:String,wall:Int64,uptime:Double)throws->LicenseCache{
  let key=previous?.publicKey ?? envelope["publicKey"].s,c=try verify(envelope,key:key,config:config,nonce:nonce)
  if let previous{let old=try verify(previous.envelope,key:previous.publicKey,config:config);if c["revision"].i<old["revision"].i || c["issuedAt"].i<old["issuedAt"].i{return previous}}
  return LicenseCache(envelope:envelope,key:key,wall:wall,uptime:uptime)
 }
}
