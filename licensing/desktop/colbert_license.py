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

class LicenseBlocked(RuntimeError):
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
    if c['version'] != 1 or c['contractId'] != contract or c['productId'] != product:
        raise ValueError('Permiso de otro cliente o aplicación.')
    if nonce is not None and c['nonce'] != nonce:
        raise ValueError('Respuesta de otra consulta.')
    issued, expires, paid = c['issuedAt'], c['expiresAt'], c['paidUntil']
    if not 0 <= expires - issued <= OFFLINE_MS or issued <= 0:
        raise ValueError('Vigencia inválida.')
    if c['allowed'] and (expires <= issued or paid and expires > paid):
        raise ValueError('El permiso supera el período pagado.')
    return c

class LicenseClient:
    def __init__(self, config_file, cache_file):
        self.config = json.loads(Path(config_file).read_text(encoding='utf-8-sig'))
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
            headers={'Authorization': 'Bearer ' + self.config['token'], 'Content-Type': 'application/json'})
        with self.opener.open(req, timeout=12) as response:
            raw = response.read(4 * 1024 * 1024 + 1)
            if len(raw) > 4 * 1024 * 1024:
                raise ValueError('Respuesta demasiado grande.')
            return json.loads(raw)

    def refresh(self):
        nonce = secrets.token_hex(32)
        sent = time.monotonic()
        previous = self._load()
        envelope = self._post('/v1/status', {'nonce': nonce})
        pinned = previous.get('publicKey') or envelope['publicKey']
        claims = verify(envelope, pinned, self.config['contractId'], self.config['productId'], nonce)
        if previous:
            old = self._claims(previous)
            if claims['revision'] < old['revision'] or claims['issuedAt'] < old['issuedAt']:
                return old
        state = {'publicKey': pinned, 'envelope': envelope, 'monotonic': sent,
                 'bootEstimate': time.time() - time.monotonic()}
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.cache_path.with_suffix('.tmp')
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as out:
            json.dump(state, out)
        os.replace(temporary, self.cache_path)
        return claims

    def allowed(self):
        try:
            state = self._load()
            c = self._claims(state)
            elapsed = (time.monotonic() - state['monotonic']) * 1000
            # Reboot or significant wall-clock change requires revalidation.
            same_boot = abs(time.time() - time.monotonic() - state['bootEstimate']) < 120
            return bool(c['allowed'] and same_boot and 0 <= elapsed < min(OFFLINE_MS, c['expiresAt'] - c['issuedAt']))
        except (KeyError, ValueError, OSError, TypeError, InvalidSignature):
            return False

    def require_access(self):
        try:
            state = self._load()
            due = not self.allowed() or time.monotonic() - state.get('monotonic', 0) >= 60
            if due:
                self.refresh()
        except (urllib.error.URLError, TimeoutError, OSError):
            pass  # Only an unexpired, verified cached permit can survive a network outage.
        if not self.allowed():
            raise LicenseBlocked('Licencia pausada. Renueva el pago o conecta con el servidor de permisos.')

    def submit_receipt(self, invoice_id, image_file, note='', request_id=None):
        raw = Path(image_file).read_bytes()
        if len(raw) > 512 * 1024:
            raise ValueError('El comprobante debe ser JPEG/PNG de hasta 512 KB.')
        # Retain this request ID if the HTTP response is lost; retry the SAME body and ID.
        request_id = request_id or secrets.token_hex(32)
        return self._post('/v1/receipt', {'requestId': request_id, 'invoiceId': invoice_id,
            'image': base64.b64encode(raw).decode('ascii'), 'note': note})
