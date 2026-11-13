"""Authenticated, read-only reports over the existing phone leader tunnel."""
import base64
import hashlib
import hmac
import io
import json
import os
from pathlib import Path
import secrets
import time
import urllib.request
import urllib.error
import zipfile
from colbert_license import NoRedirect, LicenseBlocked
from executive_excel import executive_workbook

LIMIT = 30 * 1024 * 1024

class ReportUnavailable(RuntimeError):
    """Transport or server availability failure; a verified local copy may be used."""

def mac(key, message):
    return hmac.new(key.encode(), message.encode(), hashlib.sha256).hexdigest()

class ReportsClient:
    def __init__(self, company):
        self.company = company
        self.opener = urllib.request.build_opener(NoRedirect)

    def request(self, selection, export=False):
        return self._request(selection,export)[0]

    def snapshot(self, selection, timeout=15):
        return self._request(selection,True,timeout)[1]

    def _request(self, selection, export=False, timeout=None):
        payload = json.dumps(dict(selection, action='export' if export else 'report'), ensure_ascii=False, separators=(',', ':'))
        nonce, now = secrets.token_hex(32), int(time.time()*1000)
        envelope = dict(time=now, nonce=nonce, payload=payload,
                        signature=mac(self.company['reportKey'], f'request|{now}|{nonce}|{payload}'))
        req = urllib.request.Request(self.company['url'] + '/reports', data=json.dumps(envelope).encode(),
            headers={'Content-Type':'application/json', 'Accept':'application/json',
                     'User-Agent':'Colbert-Reportes/1.2.0', 'Cache-Control':'no-store'}, method='POST')
        try:
            with self.opener.open(req, timeout=timeout or (45 if export else 12)) as response:
                raw = response.read(LIMIT+1)
        except urllib.error.HTTPError as e:
            if e.code == 400:
                raise ReportUnavailable('El servidor rechazó la consulta (HTTP 400). Se volverá a comprobar la conexión; este error no confirma falta de pago.') from None
            if e.code in (404,405):
                raise ReportUnavailable(f'El servidor no ofrece reportes compatibles (HTTP {e.code}).') from None
            if e.code in (401,403):
                raise RuntimeError(f'El acceso al túnel fue rechazado (HTTP {e.code}). No se pudo consultar el reporte.') from None
            raise ReportUnavailable(f'Sin conexión con un encargado compatible (HTTP {e.code}).') from None
        except (urllib.error.URLError,TimeoutError,ConnectionError,OSError):
            raise ReportUnavailable('No se pudo conectar con el comedor. Se volverá a intentar automáticamente.') from None
        if len(raw) > LIMIT:
            raise ValueError('El reporte es demasiado grande. Elige un período menor.')
        return self.verify(raw,nonce),dict(request=envelope,response=raw.decode('utf-8'))

    def verify(self, raw, nonce, historical=False):
        result = json.loads(raw)
        payload = result['payload']
        if not hmac.compare_digest(mac(self.company['reportKey'], 'reply|'+nonce+'|'+payload), result['signature']):
            raise ValueError('No se pudo verificar el reporte del comedor.')
        report = json.loads(payload)
        if not report.get('ok'):
            if report.get('error') == 'license':
                raise LicenseBlocked('El encargado debe actualizar su permiso en Licencia y pagos. Enciende Colbert Cobros y pulsa Comprobar acceso en el teléfono.')
            if report.get('error')=='leader':raise ReportUnavailable('El comedor está cambiando de servidor. Se volverá a consultar automáticamente.')
            raise RuntimeError('El servidor no pudo preparar el reporte. No se sustituyeron los datos guardados.')
        stamp=report.get('serverTime')
        if not isinstance(stamp,(int,float)) or not 0<stamp<=time.time()*1000+180000 or (not historical and abs(time.time()*1000-stamp)>180000):
            raise ValueError('El reporte no es reciente. Comprueba la hora automática de ambos equipos.')
        return report

def workbook_bytes(report):
    raw = base64.b64decode(report['file'], validate=True)
    if len(raw) > 20*1024*1024 or hashlib.sha256(raw).hexdigest() != report['sha256']:
        raise ValueError('El archivo Excel llegó incompleto.')
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        if not {'[Content_Types].xml','xl/workbook.xml','xl/worksheets/sheet1.xml','xl/worksheets/sheet2.xml'}.issubset(z.namelist()) or z.testzip() is not None:
            raise ValueError('El archivo recibido no es un Excel válido.')
    return raw

def save_workbook(path, report):
    data = executive_workbook(workbook_bytes(report),report)
    path = Path(path)
    temporary = path.with_name(path.name+'.'+secrets.token_hex(4)+'.tmp')
    try:
        with temporary.open('xb') as f:
            f.write(data)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
