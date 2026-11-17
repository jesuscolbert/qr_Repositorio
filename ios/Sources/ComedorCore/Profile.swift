import Foundation
public struct Profile:Codable,Equatable,Sendable {
 public var value:J
 public var id:String{value["id"].s}
 public var url:String{value["url"].s}
 public var cluster:String{value["cluster"].s}
 public var key:String{value["key"].s}
 public var license:J{value["license"]}
 public init(_ value:J)throws{
  let id=value["id"].s
  try require(value["v"].i==1 && ["soboce","frigor"].contains(id),"Activación de otra aplicación.")
  try require(value["url"].s==(id=="soboce" ? "https://comedor.colbert2027.shop":"https://frigor.colbert2027.shop"),"Dirección del comedor no reconocida.")
  try require(Wire.matches(value["key"].s,"[a-f0-9]{64}") && Wire.matches(value["cluster"].s,"[a-f0-9]{64}"),"Activación incompleta.")
  let l=value["license"]
  try require(l["version"].i==1 && l["url"].s=="https://licencias.colbert2027.shop" && l["productId"].s==id+"-comedor" && l["contractId"].s==id+"-comedor" && Wire.matches(l["token"].s,"[a-f0-9]{64}"),"El permiso no pertenece a esta empresa.")
  self.value=value
 }
 public init(qr:String)throws{try require(qr.hasPrefix("COLBERT-IOS1:") && qr.count<=4096,"Escanea la activación de iPhone que te entrega Jesús.");try self.init(J.parse(String(decoding:Wire.unb64(String(qr.dropFirst(13))),as:UTF8.self)))}
 public func mac(_ message:String)->String{Wire.mac(key,cluster+"|"+message)}
 public func request(_ payload:J,now:Int64=Wire.now,nonce:String=Wire.random())throws->J{let body=try payload.text();return ["time":.num(now),"nonce":.string(nonce),"payload":.string(body),"signature":.string(mac("control-request|\(now)|\(nonce)|\(body)"))]}
 public func reply(_ envelope:J,nonce:String,now:Int64=Wire.now)throws->J{let body=envelope["payload"].s;try require(Wire.equal(mac("control-reply|\(nonce)|\(body)"),envelope["signature"].s),"Respuesta sin firma válida.");let r=try J.parse(body);try require(r["controlProtocol"].i==1 && abs(r["serverTime"].i-now)<=180000,"Revisa la hora automática del iPhone y del servidor.");return r}
}
