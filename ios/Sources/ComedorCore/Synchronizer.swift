import Foundation
public enum Synchronizer {
 public static func checked(_ r:J)throws->J{try require(r["ok"].b,r["error"].s.isEmpty ? "El servidor no confirmó la operación.":r["error"].s);return r}
 /// Returns a new state only after the complete snapshot. Failed requests never clear the durable outbox.
 public static func run(_ original:CompanyState,call:(J)async throws->J)async throws->CompanyState{
  var state=original;let boot=try checked(await call(["action":"bootstrap"]));try require(boot["cluster"].s==state.profile.cluster && ["CI-","FRIGOR-CI-"].contains(boot["personPrefix"].s) && !boot["plants"].a.isEmpty,"El servidor pertenece a otra empresa.");let epoch=boot["epoch"].i;try require(epoch>0,"Servidor sin confirmar.");state.catalog=boot
  var rounds=0
  while !state.rosterOutbox.isEmpty || !state.outbox.isEmpty {
   rounds+=1;try require(rounds<=250,"Hay muchas entregas pendientes. Conserva los datos y vuelve a sincronizar.")
   let cards=Array(state.rosterOutbox.prefix(20)),events=Array(state.outbox.prefix(20)),reply=try checked(await call(["action":"sync","roster":.strings(cards),"events":.strings(events)]));try require(reply["epoch"].i==epoch,"El servidor cambió. Reintenta la sincronización.")
   let ack=reply["vector"][state.device].i;try require(ack<=state.sequence,"Identidad repetida. Conserva los datos y contacta con Jesús.")
   if let last=events.last{let b=try J.parse(J.parse(last)["body"].s);try require(ack>=b["seq"].i,"No se confirmó la copia de las entregas.")}
   state.rosterOutbox.removeFirst(cards.count);state.outbox=try state.outbox.filter{try J.parse(J.parse($0)["body"].s)["seq"].i>ack}
  }
  for kind in ["people","bindings","records"] {
   var cursor="",pages=0
   while true{pages+=1;try require(pages<=500,"Historial demasiado grande; conserva los datos y contacta con Jesús.")
    let page=try checked(await call(["action":"page","kind":.string(kind),"after":.string(cursor)]));try require(page["epoch"].i==epoch && page["items"].a.count<=200,"Página de otra sesión o demasiado grande.")
    for item in page["items"].a{if kind=="people"{let id=item["id"].s;try require(!id.isEmpty && state.plants.contains(item["planta"].s),"Padrón incompatible.");state.people[id]=item}else if kind=="bindings"{let tag=item["tag"].s;try require(Wire.matches(tag,"[a-f0-9]{64}") && item["version"].i>0,"Vinculación inválida.");if state.bindings[tag]==nil || state.bindings[tag]!["version"].i<item["version"].i{state.bindings[tag]=item}}else{let id=item["registro"].s;try require(!id.isEmpty && item["clave"].s==item["persona"].s+"|"+item["fecha"].s+"|"+Ledger.allowance(item["comida"].s),"Entrega incompatible.");state.records[id]=item}}
    if !page["more"].b{break};try require(!page["items"].a.isEmpty && page["after"].s>cursor,"El servidor repitió una página.");cursor=page["after"].s
   }
  }
  state.epoch=epoch;state.lastSync=Wire.now;return state
 }
}
