"""Prepare private account authority and device-bound enrollment for the existing networks.

Does not rotate network/tunnel/billing credentials, change a roster or activate a master.
Run --deploy only after source tests pass. The first physical master claim activates enforcement.
"""
from pathlib import Path
import argparse,base64,hashlib,hmac,json,secrets,subprocess,time,urllib.request
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization,hashes
import qrcode

ROOT=Path(__file__).resolve().parents[1]
PRIVATE=ROOT/'private/staff-accounts'
def b64(x):return base64.urlsafe_b64encode(x).decode().rstrip('=')
def text(x):return json.dumps(x,ensure_ascii=False,separators=(',',':'))
def prepare():
 PRIVATE.mkdir(parents=True,exist_ok=True);states={}
 for company,path in [('soboce','private/shared-network/bundle.json'),('frigor','private/frigor/network/bundle.json')]:
  original=ROOT/path;network=json.loads(original.read_text('utf-8-sig'));seed=json.loads(network['seed']);cluster=hashlib.sha256(network['manifest'].encode()).hexdigest();saved=PRIVATE/(company+'.json')
  if saved.exists():state=json.loads(saved.read_text('utf-8'));assert state['cluster']==cluster
  else:
   key=ec.generate_private_key(ec.SECP256R1());state={'cluster':cluster,'private':b64(key.private_bytes(serialization.Encoding.DER,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())),'public':b64(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)),'bootstrap':secrets.token_hex(32),'recovery':secrets.token_hex(32)}
   saved.write_text(text(state),'utf-8')
  backup=PRIVATE/(company+'-network-before.json')
  if not backup.exists():backup.write_text(text(network),'utf-8')
  assert 'staffAuthority' not in network or network['staffAuthority']==state['public']
  network['staffAuthority']=state['public'];original.write_text(text(network),'utf-8')
  state['policy']={'cluster':cluster,'authority':state['public'],'plants':seed['plants'],'personPrefix':'FRIGOR-CI-' if company=='frigor' else 'CI-'}
  env={'STAFF_SIGNING_KEY':state['private'],'STAFF_BOOTSTRAP_HASH':hashlib.sha256(state['bootstrap'].encode()).hexdigest(),'STAFF_RECOVERY_HASH':hashlib.sha256(state['recovery'].encode()).hexdigest(),'STAFF_POLICY':text(state['policy'])}
  (PRIVATE/(company+'-worker-secrets.json')).write_text(text(env),'utf-8');states[company]=(state,network)
 for name,kind,field in [('Activar-maestro-una-vez','activation','bootstrap'),('Recuperacion-maestro-GUARDAR-PRIVADO','recovery','recovery')]:
  payload='CSM1:'+text({'kind':kind,'companies':[{'cluster':s['cluster'],'code':s[field]} for s,n in states.values()]})
  qrcode.make(payload).save(PRIVATE/(name+'.png'))
 (PRIVATE/'LEER-PRIMERO.txt').write_text('CUENTAS DEL COMEDOR — ARCHIVOS PRIVADOS\n\nInstala la actualización encima de cada app, sin desinstalar ni borrar datos. La base, trabajadores, QR, consumos, operaciones pendientes y permiso de Cobros se conservan.\n\nPrimero actualiza el teléfono de la administradora. Crea su perfil personal y PIN, toca Activar usuario maestro y escanea Activar-maestro-una-vez.png en cada empresa. El mismo perfil puede tener permisos para SOBOCE y FRIGOR. Cada código inicial se consume una sola vez y se vincula a la clave del teléfono. No compartas estos códigos con los encargados.\n\nEn los otros teléfonos actualizados, crear perfil y Mostrar solicitud al maestro. La administradora escanea esa solicitud desde Mi cuenta > Encargados y permisos, elige el rol y las plantas. El encargado toca Comprobar. Un administrador no puede crear otras cuentas. Cada encargado puede vincular obreros de sus plantas.\n\nTodos los teléfonos que sincronizan esa empresa deben actualizarse y autorizarse. Al activar al maestro, los servidores antiguos dejan de renovar su turno; sus datos siguen en el teléfono y se recuperan al actualizar y autorizar ese equipo. No desinstales las versiones antiguas.\n\nEl código Recuperacion-maestro-GUARDAR-PRIVADO.png permite reemplazar al maestro si pierde el teléfono o el PIN, sin borrar otras cuentas ni consumos. Es de un solo uso por empresa. Guarda ese archivo fuera del teléfono del maestro, con acceso restringido. Después de usarlo se debe preparar una recuperación nueva.\n\nDesactivar una cuenta no elimina sus registros. La revocación se aplica al sincronizar; un teléfono aislado conserva su autorización guardada. El permiso mensual de Colbert Cobros sigue siendo independiente y no se recorta.\n\nLa cuenta del maestro y su recuperación todavía requieren la instalación y activación en el teléfono real. Estos archivos no deben publicarse en GitHub ni enviarse a obreros.\n','utf-8')
 return states
def deploy(states):
 wrangler=ROOT/'.tools/worker-cli/node_modules/wrangler/bin/wrangler.js'
 for company,(state,network) in states.items():
  config=ROOT/f'backend/coordination/wrangler{".frigor" if company=="frigor" else ""}.jsonc'
  for args in [('deploy','--config',str(config),'--keep-vars'),('secret','bulk',str(PRIVATE/(company+'-worker-secrets.json')),'--config',str(config))]:
   p=subprocess.run(['node',str(wrangler),*args],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace')
   (PRIVATE/(company+'-'+args[0]+'.log')).write_text(p.stdout+'\n'+p.stderr,'utf-8')
   if p.returncode:raise RuntimeError(f'{company}: {args[0]} failed. Review private log.')
  key=serialization.load_der_private_key(base64.urlsafe_b64decode(state['private']+'='*(-len(state['private'])%4)),None)
  people=json.loads(network['seed'])['people'];items=[{'tag':hmac.new(network['networkKey'].encode(),(state['cluster']+'|wallet-person|'+p['id']).encode(),hashlib.sha256).hexdigest(),'plant':p['planta']} for p in people if p.get('tipo')!='VISITA']
  for start in range(0,len(items),75):
   payload=text({'action':'seed','items':items[start:start+75]});now=int(time.time()*1000);nonce=secrets.token_hex(32);message=f'staff-request|{state["cluster"]}|staff|{now}|{nonce}|{payload}'
   envelope={'key':state['public'],'time':now,'nonce':nonce,'payload':payload,'signature':b64(key.sign(message.encode(),ec.ECDSA(hashes.SHA256())))}
   request=urllib.request.Request(network['coordinator']+'/staff',data=text(envelope).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+network['networkKey'],'User-Agent':'Colbert-Staff-Setup'})
   with urllib.request.urlopen(request,timeout=30) as response:reply=json.load(response)
   if not reply.get('ok'):raise RuntimeError(company+': seed registration rejected')
  print(company+': authority deployed; existing worker plants registered; master remains unclaimed.')
if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--deploy',action='store_true');args=parser.parse_args();states=prepare()
 if args.deploy:deploy(states)
 else:print('Private master activation/recovery prepared. No master claimed; no live enforcement changed.')
