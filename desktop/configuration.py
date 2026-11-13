"""Private configuration is appended locally AFTER the generic GitHub build."""
import json
import re
import struct
from pathlib import Path
from urllib.parse import urlsplit

MAGIC = b'COLBERT-REPORTS-CONFIG-V1\x00'
MAX_CONFIG = 65536

def validate(config):
    if config.get('version') != 1 or len(config.get('companies', [])) not in (1, 2):
        raise ValueError('Configuración de empresas inválida.')
    ids = set()
    for c in config['companies']:
        if c['id'] not in ('soboce', 'frigor') or c['id'] in ids:
            raise ValueError('Empresa inválida.')
        ids.add(c['id'])
        u = urlsplit(c['url'])
        if u.scheme != 'https' or u.hostname not in ('comedor.colbert2027.shop', 'frigor.colbert2027.shop') or u.username or u.password or u.port or u.path or u.query or u.fragment:
            raise ValueError('Dirección de comedor inválida.')
        if not re.fullmatch('[a-f0-9]{64}', c['reportKey']):
            raise ValueError('Credencial de consulta inválida.')
        if c['license']['contractId'] != c['id'] + '-comedor' or c['license']['productId'] != c['id'] + '-comedor':
            raise ValueError('Permiso de otra aplicación.')
        if {'networkKey', 'tunnel', 'seed', 'privateKey'}.intersection(c):
            raise ValueError('El visor no admite permisos de encargado.')
    return config

def read(path):
    with Path(path).open('rb') as f:
        f.seek(-len(MAGIC)-4, 2)
        footer = f.read()
        if footer[4:] != MAGIC:
            raise ValueError('Este ejecutable no está configurado. Solicita a Jesús el visor privado.')
        length = struct.unpack('>I', footer[:4])[0]
        if not 0 < length <= MAX_CONFIG:
            raise ValueError('Configuración inválida.')
        f.seek(-len(MAGIC)-4-length, 2)
        return validate(json.loads(f.read(length)))

def append(executable, target, config):
    data = json.dumps(validate(config), ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    if len(data) > MAX_CONFIG:
        raise ValueError('Configuración demasiado grande.')
    Path(target).write_bytes(Path(executable).read_bytes() + data + struct.pack('>I', len(data)) + MAGIC)
