"""Company/selection-scoped signed snapshots. No cached access bypasses licensing."""
import datetime as dt
import hashlib
import hmac
import json
from pathlib import Path
import re
import secrets
from reports_client import ReportsClient,ReportUnavailable,mac,workbook_bytes,LIMIT
from executive_excel import validated_source

def selection_key(selection):
    if set(selection)!={'period','date','month','plant'} or selection['period'] not in ('day','month'):
        raise ValueError('Selección de reporte inválida.')
    date=dt.date.fromisoformat(selection['date']).isoformat()
    if date[:7]!=selection['month'] or not isinstance(selection['plant'],str):raise ValueError('Período inválido.')
    return json.dumps(selection,ensure_ascii=False,sort_keys=True,separators=(',',':'))

class SnapshotStore:
    def __init__(self,client,folder):self.client,self.folder=client,Path(folder)
    def path(self,selection):
        identity=self.client.company['id']+'|'+self.client.company['url']+'|'+selection_key(selection)
        return self.folder/(hashlib.sha256(identity.encode()).hexdigest()+'.json')
    def decode(self,document,selection):
        envelope=document['request'];nonce=envelope['nonce'];payload=envelope['payload'];time=envelope['time']
        if not isinstance(time,int) or not re.fullmatch('[a-f0-9]{64}',nonce):raise ValueError('Copia guardada inválida.')
        key=self.client.company['reportKey']
        if not hmac.compare_digest(mac(key,f'request|{time}|{nonce}|{payload}'),envelope['signature']):raise ValueError('No se pudo verificar la copia guardada.')
        if json.loads(payload)!=dict(selection,action='export'):raise ValueError('La copia pertenece a otra selección.')
        report=self.client.verify(document['response'],nonce,historical=True)
        _,records,_,_=validated_source(workbook_bytes(report),report)
        fields=('fecha','hora','persona','nombre','comida','planta','operador','precio','centroCosto','registro','origen','revision','precioPendiente')
        items=[]
        for row in records[:100]:
            row=list(row)
            row[0]=(dt.datetime(1899,12,30)+dt.timedelta(days=float(row[0]))).strftime('%Y-%m-%d')
            seconds=round(float(row[1])*86400)%86400;row[1]=f'{seconds//3600:02d}:{seconds//60%60:02d}:{seconds%60:02d}'
            row[7]=None if row[7] is None else float(row[7]);row[12]=row[12]=='Sí'
            items.append(dict(zip(fields,row)))
        report['items']=items
        return report
    def get(self,selection):
        path=self.path(selection)
        if not path.exists():return None
        if path.stat().st_size>LIMIT+1048576:raise ValueError('Copia guardada demasiado grande.')
        return self.decode(json.loads(path.read_text(encoding='utf-8')),selection)
    def put(self,document,selection):
        # Always authenticate before writing and replace atomically.
        self.decode(document,selection)
        self.folder.mkdir(parents=True,exist_ok=True)
        path=self.path(selection);temporary=path.with_suffix('.'+secrets.token_hex(8)+'.tmp')
        try:
            with temporary.open('x',encoding='utf-8') as f:json.dump(document,f,ensure_ascii=False,separators=(',',':'))
            temporary.replace(path)
        finally:temporary.unlink(missing_ok=True)
        files=sorted(self.folder.glob('*.json'),key=lambda p:p.stat().st_mtime,reverse=True)
        size=0
        for index,file in enumerate(files):
            size+=file.stat().st_size
            if file!=path and (index>=24 or size>128*1024*1024):file.unlink(missing_ok=True)

class ReportSession:
    def __init__(self,company,license,folder):
        self.license=license;self.client=ReportsClient(company);self.store=SnapshotStore(self.client,folder)
    def load(self,selection,export=False):
        selection_key(selection);self.license.require_access()
        try:document=self.client.snapshot(selection,timeout=45 if export else 15)
        except ReportUnavailable as error:
            self.license.require_access();report=self.store.get(selection)
            if report is None:raise ReportUnavailable(str(error)+' Todavía no hay una copia guardada de esta selección.') from None
            self.license.require_access();report['_cached']=True;report['_connectionMessage']=str(error)
            return report
        report=self.store.decode(document,selection)
        self.license.require_access()
        try:self.store.put(document,selection);report['_cacheSaved']=True
        except OSError:report['_cacheSaved']=False
        report['_cached']=False
        return report
