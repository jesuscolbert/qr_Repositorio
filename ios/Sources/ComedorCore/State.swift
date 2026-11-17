import Foundation
import CryptoKit

public struct CompanyState:Codable,Sendable {
 public var profile:Profile;public var device=Wire.random();public var catalog:J = .null
 public var people:[String:J]=[:],records:[String:J]=[:],bindings:[String:J]=[:]
 public var outbox:[String]=[],rosterOutbox:[String]=[]
 public var pending:J?;public var license:LicenseCache?;public var lastSync:Int64=0,epoch:Int64=0,sequence:Int64=0,rosterSequence:Int64=0,lastMealTime:Int64=0
 public init(profile:Profile){self.profile=profile}
 public var plants:[String]{catalog["plants"].a.map(\.s)}
 public var initialized:Bool{catalog["cluster"].s==profile.cluster && !plants.isEmpty && lastSync>0}
 public func op(name:String,plant:String)->J{["name":.string(name),"role":"admin","plant":.string(plant)]}
 public var permitted:Bool{License.allowed(license,config:profile.license)}
 public mutating func checkpoint(wall:Int64=Wire.now,uptime:Double=ProcessInfo.processInfo.systemUptime){guard var cache=license,let c=try? License.verify(cache.envelope,key:cache.publicKey,config:profile.license)else{return};let now=License.now(cache,claims:c,wall:wall,uptime:uptime);if now>cache.checkpoint{cache.checkpoint=now;cache.lastWall=wall;license=cache}}
}
public struct AppState:Codable,Sendable {public var version=1;public var companies:[String:CompanyState]=[:];public var selected="";public var operatorName="Encargado iPhone";public init(){}}
public final class EncryptedFile {
 public let url:URL;private let key:SymmetricKey
 public init(url:URL,key:Data)throws{try require(key.count==32,"Clave local inválida.");self.url=url;self.key=SymmetricKey(data:key)}
 public func load()throws->AppState{guard FileManager.default.fileExists(atPath:url.path)else{return AppState()};let data=try Data(contentsOf:url);try require(data.count<=128*1024*1024,"La base local excede el límite permitido.");let raw=try AES.GCM.open(AES.GCM.SealedBox(combined:data),using:key);let state=try JSONDecoder().decode(AppState.self,from:raw);try require(state.version==1,"Base local incompatible. Conserva los datos.");return state}
 public func save(_ state:AppState)throws{let data=try JSONEncoder().encode(state);try require(data.count<=128*1024*1024,"La base local está llena. Sincroniza antes de continuar.");guard let encrypted=try AES.GCM.seal(data,using:key).combined else{throw ComedorError("No se pudo proteger la base local.")};let parent=url.deletingLastPathComponent();try FileManager.default.createDirectory(at:parent,withIntermediateDirectories:true)
  #if os(iOS)
  try encrypted.write(to:url,options:[.atomic,.completeFileProtection]);var folder=parent;var values=URLResourceValues();values.isExcludedFromBackup=true;try folder.setResourceValues(values)
  #else
  try encrypted.write(to:url,options:.atomic)
  #endif
 }
}
