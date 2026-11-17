"""Reusable Python desktop adapter for Colbert Licenses v1 (cryptography required).

Call require_access() before each protected operation, not only on application startup.
Configuration is customer/product-specific. Never bundle owner/tunnel credentials here.
"""
import base64
import json
import os
from pathlib import Path
import re
import secrets
import time
import urllib.request
import urllib.error
from urllib.parse import urlsplit
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric import ec

OFFLINE_MS = 86_400_000
USER_AGENT = 'Colbert-Licencias/1.0'

class LicenseBlocked(RuntimeError):
    pass

class LicenseUnavailable(LicenseBlocked):
    """Permission could not be validated; this is not evidence of an unpaid invoice."""
    pass

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def verify(envelope, pinned_key, contract, product, nonce=None):
    if envelope['publicKey'] != pinned_key:
        raise ValueError('Cambió la identidad del servidor.')
    key = serialization.load_der_public_key(base64.b64decode(pinned_key, validate=True))
    if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != 'secp256r1':
        raise ValueError('Clave de firma incompatible.')
    key.verify(base64.b64decode(envelope['signature'], validate=True),
               envelope['claims'].encode('utf-8'), ec.ECDSA(hashes.SHA256()))
    c = json.loads(envelope['claims'])
    if c['version'] not in (1, 2) or c['contractId'] != contract or c['productId'] != product:
        raise ValueError('Permiso de otro cliente o aplicación.')
    if nonce is not None and c['nonce'] != nonce:
        raise ValueError('Respuesta de otra consulta.')
    issued, expires, paid = c['issuedAt'], c['expiresAt'], c['paidUntil']
    if issued <= 0 or expires < issued or (not paid_permit(c) and expires-issued > OFFLINE_MS):
        raise ValueError('Vigencia inválida.')
    if c['version'] == 2:
        kind = c.get('grantKind')
        if kind not in ('paid','temporary','denied') or bool(c['allowed']) != (kind != 'denied'):
            raise ValueError('Tipo de permiso inválido.')
        if kind == 'paid' and not (paid > issued and expires == paid):
            raise ValueError('El permiso debe terminar en el vencimiento pagado.')
        if kind == 'temporary' and paid != 0:
            raise ValueError('Permiso temporal inválido.')
    if c['allowed'] and (expires <= issued or paid and expires > paid):
        raise ValueError('El permiso supera el período pagado.')
    return c

def paid_permit(claims):
    return (claims.get('version') == 2 and claims.get('allowed') is True and claims.get('grantKind') == 'paid'
            and claims.get('paidUntil',0) > claims.get('issuedAt',0) and claims.get('expiresAt') == claims.get('paidUntil'))

def trusted_now(claims, state, wall, monotonic):
    elapsed = (monotonic-state['monotonic'])*1000
    same_boot = abs(wall-monotonic-state['bootEstimate']) < 120
    if same_boot and elapsed >= 0:
        candidate = claims['issuedAt']+elapsed
    else:
        saved = state.get('wallAtValidation',0)
        if not paid_permit(claims) or saved <= 0 or wall < saved-120 or wall < state.get('lastWall',saved)-120:
            return -1
        candidate = claims['issuedAt']+max(0,wall-saved)*1000
    return max(candidate,state.get('lastServer',claims['issuedAt']))

class LicenseClient:
    def __init__(self, config_file, cache_file):
        self.config = dict(config_file) if isinstance(config_file, dict) else json.loads(Path(config_file).read_text(encoding='utf-8-sig'))
        c = self.config
        u = urlsplit(c['url'])
        if c['version'] != 1 or u.scheme != 'https' or not u.hostname or u.username or u.password or u.query or u.fragment or u.path not in ('', '/'):
            raise ValueError('URL de licencia inválida.')
        if not re.fullmatch(r'[a-f0-9]{64}', c['token']):
            raise ValueError('Credencial de licencia inválida.')
        self.cache_path = Path(cache_file)
        self.opener = urllib.request.build_opener(NoRedirect)

    def _load(self):
        return json.loads(self.cache_path.read_text(encoding='utf-8')) if self.cache_path.exists() else {}

    def _claims(self, state, nonce=None):
        return verify(state['envelope'], state['publicKey'], self.config['contractId'], self.config['productId'], nonce)

    def _post(self, route, data):
        req = urllib.request.Request(self.config['url'].rstrip('/') + route,
            data=json.dumps(data).encode('utf-8'), method='POST',
            headers={'Authorization': 'Bearer ' + self.config['token'], 'Content-Type': 'application/json',
                     'Accept': 'application/json', 'User-Agent': USER_AGENT, 'Cache-Control': 'no-store'})
        with self.opener.open(req, timeout=12) as response:
            raw = response.read(4 * 1024 * 1024 + 1)
            if len(raw) > 4 * 1024 * 1024:
                raise ValueError('Respuesta demasiado grande.')
            return json.loads(raw)

    def refresh(self):
        nonce = secrets.token_hex(32)
        sent = time.monotonic()
        previous = self._load()
        wall = time.time()
        envelope = self._post('/v1/status', {'nonce': nonce, 'permitVersion': 2})
        pinned = previous.get('publicKey') or envelope['publicKey']
        claims = verify(envelope, pinned, self.config['contractId'], self.config['productId'], nonce)
        previous = self._load()
        if previous:
            old = self._claims(previous)
            if claims['revision'] < old['revision'] or claims['issuedAt'] < old['issuedAt']:
                return old
        state = {'publicKey': pinned, 'envelope': envelope, 'monotonic': sent,
                 'bootEstimate': wall-sent, 'wallAtValidation':wall, 'lastWall':wall, 'lastServer':claims['issuedAt']}
        self._save(state)
        return claims

    def _save(self, state):
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.cache_path.with_suffix('.'+secrets.token_hex(8)+'.tmp')
        try:
            fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w', encoding='utf-8') as out:
                json.dump(state, out)
            os.replace(temporary, self.cache_path)
        finally:
            temporary.unlink(missing_ok=True)

    def allowed(self):
        try:
            state = self._load()
            c = self._claims(state)
            wall = time.time()
            now = trusted_now(c,state,wall,time.monotonic())
            if now > 0 and now-state.get('lastServer',c['issuedAt']) >= 60000 and paid_permit(c):
                state.update(lastServer=now,lastWall=wall);self._save(state)
            return bool(c['allowed'] and c['issuedAt'] <= now < c['expiresAt'])
        except (KeyError, ValueError, OSError, TypeError, InvalidSignature):
            return False

    def require_access(self):
        problem = None
        try:
            state = self._load()
            due = not self.allowed() or time.monotonic() - state.get('monotonic', 0) >= 60
            if due:
                self.refresh()
        except urllib.error.HTTPError as exc:
            problem = f'Colbert Cobros no pudo validar esta computadora (HTTP {exc.code}). Revisa la conexión y el servidor de permisos. Este error no confirma una deuda.'
        except (urllib.error.URLError, TimeoutError):
            problem = 'No se pudo conectar con Colbert Cobros para validar esta computadora. Enciende su servidor y vuelve a actualizar. Esto no significa que debas pagar otra vez.'
        except OSError:
            problem = 'No se pudo leer o guardar la validación en esta computadora. Comprueba el acceso a su carpeta de datos; el pago no se ha modificado.'
        except (KeyError, ValueError, TypeError, InvalidSignature):
            problem = 'No se pudo verificar la respuesta de Colbert Cobros. Revisa la configuración del visor; no se ha confirmado un problema de pago.'
        # A network outage cannot extend a permit, discard a signed denial, or invent an approval.
        if self.allowed():
            return
        if problem:
            raise LicenseUnavailable(problem)
        try:
            claims = self._claims(self._load())
            if claims.get('allowed') is False and claims.get('reason') == 'payment_due':
                raise LicenseBlocked('Colbert Cobros todavía no confirma el acceso pagado de esta empresa. Si ya pagaste, comprueba que se haya aprobado en su contrato; no hagas otro pago por abrir el visor.')
            if claims.get('allowed') is False and claims.get('reason') == 'suspended':
                raise LicenseBlocked('El administrador suspendió el acceso de esta empresa en Colbert Cobros. Debe reactivarlo allí; no es un cobro adicional de Windows.')
        except (KeyError, ValueError, TypeError, OSError, InvalidSignature):
            pass
        raise LicenseUnavailable('Esta computadora necesita validar su permiso con Colbert Cobros. Enciende el servidor y actualiza el reporte. El visor utiliza el mismo contrato de la app.')

    def submit_receipt(self, invoice_id, image_file, note='', request_id=None):
        raw = Path(image_file).read_bytes()
        if len(raw) > 512 * 1024:
            raise ValueError('El comprobante debe ser JPEG/PNG de hasta 512 KB.')
        # Retain this request ID if the HTTP response is lost; retry the SAME body and ID.
        request_id = request_id or secrets.token_hex(32)
        return self._post('/v1/receipt', {'requestId': request_id, 'invoiceId': invoice_id,
            'image': base64.b64encode(raw).decode('ascii'), 'note': note})
