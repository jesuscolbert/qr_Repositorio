#if DEBUG
import Foundation
import CryptoKit
import ComedorCore
enum Demo {
 static func state()throws->AppState{var state=AppState();state.selected="soboce";state.operatorName="Ana · demostración"
  for id in ["soboce","frigor"]{let profile=try Profile(["v":1,"id":.string(id),"url":.string(id=="soboce" ? "https://comedor.colbert2027.shop":"https://frigor.colbert2027.shop"),"cluster":.string(String(repeating:id=="soboce" ? "b":"c",count:64)),"key":.string(String(repeating:"a",count:64)),"license":["version":1,"url":"https://licencias.colbert2027.shop","contractId":.string(id+"-comedor"),"productId":.string(id+"-comedor"),"token":.string(String(repeating:"d",count:64))]])
   var c=CompanyState(profile:profile);let now=Wire.now
   c.catalog=["cluster":.string(profile.cluster),"plants":["Planta Norte","Planta Sur"],"rates":[["desde":"2020-01-01","desayuno":10,"almuerzo":20,"cena":15]],"ratesConfigured":true,"seedIds":["DEMO-001"],"seedDocuments":[],"legacyKeys":[],"personPrefix":.string(id=="soboce" ? "CI-":"FRIGOR-CI-")]
   c.people["DEMO-001"]=["id":"DEMO-001","nombre":"María López · demostración","sap":"001","documento":"","planta":"Planta Norte","tipo":"OBRERO","centroCosto":"PRUEBA","activo":true,"comidas":"desayuno,almuerzo,cena","vence":"","qrHash":""]
   let key=P256.Signing.PrivateKey(),claims:J=["version":2,"grantKind":"paid","contractId":profile.license["contractId"],"productId":profile.license["productId"],"nonce":"demo","issuedAt":.num(now),"expiresAt":.num(now+30*License.day),"paidUntil":.num(now+30*License.day),"allowed":true,"revision":1],body=try claims.text(),publicKey=key.publicKey.derRepresentation.base64EncodedString()
   let envelope:J=["claims":.string(body),"publicKey":.string(publicKey),"signature":.string(try key.signature(for:Data(body.utf8)).derRepresentation.base64EncodedString())];c.license=LicenseCache(envelope:envelope,key:publicKey,wall:now,uptime:ProcessInfo.processInfo.systemUptime);c.lastSync=now
   for i in 0..<24{let name=["Luis Pérez","Ana Vargas","Pedro Rojas","Rosa Soto"][i%4];c.records["demo-\(i)"]=["registro":.string("demo-\(i)"),"clave":.string("DEMO-\(i+10)|\(Wire.day(now))|almuerzo"),"persona":.string("DEMO-\(i+10)"),"nombre":.string(name+" · demostración"),"fecha":.string(Wire.day(now)),"hora":.string(String(format:"12:%02d:00",i)),"comida":"almuerzo","planta":"Planta Norte","operador":"Ana · demostración","precio":20,"centroCosto":"PRUEBA"]}
   state.companies[id]=c
  };return state
 }
}
#endif
