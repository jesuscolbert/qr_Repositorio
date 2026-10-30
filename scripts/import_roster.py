"""Extract roster only. No historical quantities or formula results are imported."""
import json, pathlib, openpyxl
root=pathlib.Path(__file__).resolve().parents[1]
book=openpyxl.load_workbook(next(root.glob('*.xlsx')),data_only=True,read_only=True)
people=[]; excluded=[]; seen=set()
for row in book.worksheets[0].iter_rows(min_row=6,max_row=192,values_only=True):
 _,sap,plant,kind,name,job,cost,*_=row
 sap=str(sap).strip(); plant=str(plant).strip()
 if sap in seen: raise ValueError('Duplicate SAP '+sap)
 seen.add(sap)
 if not sap.isdigit() or len(sap)<6:
  excluded.append({'codigo':sap,'tipo':kind,'nombre':name,'motivo':'Identidad individual / SAP por verificar'})
  continue
 people.append(dict(id='SAP-'+sap,sap=sap,nombre=name,planta=plant,centroCosto=cost,tipo=kind,activo=True,qrHash='',vence='',comidas='desayuno,almuerzo,cena'))
out=root/'private';out.mkdir(exist_ok=True)
(out/'padron.json').write_text(json.dumps(people,ensure_ascii=False,indent=2),encoding='utf-8')
(out/'revisar_identidades.json').write_text(json.dumps(excluded,ensure_ascii=False,indent=2),encoding='utf-8')
source='function importRoster() { const roster='+json.dumps(people,ensure_ascii=False)+'; withLock_(()=>{ const ids=new Set(rows_("Personas").map(p=>p.id)); roster.forEach(p=>{if(!ids.has(p.id)){add_("Personas",p);ids.add(p.id);}}); }); }'
(out/'ImportarPadron.gs').write_text(source,encoding='utf-8')
print(f'Prepared {len(people)} individual SAP records; {len(excluded)} require identity review. Files stay private.')
