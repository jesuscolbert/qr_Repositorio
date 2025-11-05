// --- Config Telegram (lee última URL del backend) ---
const TELEGRAM_TOKEN = "7710093298:AAGKdII4mryPkV2jvt-r4NPcNGAUWc_rNbg";
const TELEGRAM_CHAT  = "6329840227";

// --- API base dinámica ---
// "" = mismo origen; pero como este sitio es estático fijo, normalmente usaremos la URL del túnel
let API_BASE = localStorage.getItem("qr_api_base") || "";

async function fetchLatestTunnel() {
  try {
    const r = await fetch(`https://api.telegram.org/bot${TELEGRAM_TOKEN}/getUpdates`);
    const j = await r.json();
    const arr = (j.result||[]).slice().reverse();
    for (const it of arr){
      const t = it.message?.text || "";
      const m = t.match(/https:\/\/[A-Za-z0-9\-]+\.trycloudflare\.com/);
      if (m) return m[0];
    }
  } catch(e){}
  return null;
}

async function ensureApiBase() {
  if (!navigator.onLine) return; // offline: seguimos con lo que haya
  const latest = await fetchLatestTunnel();
  if (!latest) return;
  if (API_BASE !== latest) {
    API_BASE = latest;
    localStorage.setItem("qr_api_base", API_BASE);
  }
}

// resolver al inicio y cuando vuelve internet
ensureApiBase();
window.addEventListener('online', ensureApiBase);

// --- Endpoints con API_BASE ---
const API_INFO     = () => `${API_BASE}/api/product_info`;
const API_SALE     = () => `${API_BASE}/api/register_sale`;
const API_PRODUCTS = () => `${API_BASE}/api/products`;

// --- UI refs ---
const video = document.getElementById('preview');
const msg = document.getElementById('msg');
const qtyEl = document.getElementById('qty');
const cameraSelect = document.getElementById('cameraSelect');
const netStatus = document.getElementById('netStatus');
const queueSize = document.getElementById('queueSize');
const btnStart = document.getElementById('btnStart');
const btnStop = document.getElementById('btnStop');
const btnSync = document.getElementById('btnSync');
const btnManual = document.getElementById('btnManual');

const productPanel = document.getElementById('productPanel');
const pName = document.getElementById('pName');
const pCode = document.getElementById('pCode');
const pPrice = document.getElementById('pPrice');
const pStock = document.getElementById('pStock');
const pMsg = document.getElementById('pMsg');
const sellQty = document.getElementById('sellQty');
const btnSell = document.getElementById('btnSell');

// --- Cola offline ---
const QUEUE_KEY = 'venta_queue_v2';
const getQueue = () => JSON.parse(localStorage.getItem(QUEUE_KEY) || '[]');
const setQueue = (q) => { localStorage.setItem(QUEUE_KEY, JSON.stringify(q)); renderQueueSize(); };
const enqueue = (item) => { const q=getQueue(); q.push(item); setQueue(q); };
const dequeue = () => { const q=getQueue(); const x=q.shift(); setQueue(q); return x; };
const renderQueueSize = () => queueSize.textContent = String(getQueue().length);
renderQueueSize();

// --- Estado de red ---
const updateNetStatus = () => netStatus.textContent = navigator.onLine ? 'Online' : 'Offline';
window.addEventListener('online', updateNetStatus);
window.addEventListener('offline', updateNetStatus);
updateNetStatus();
window.addEventListener('online', ()=> { if (getQueue().length) syncQueue(); });

// --- Cámaras ---
async function listCameras(){
  try{
    const devices = await navigator.mediaDevices.enumerateDevices();
    const videos = devices.filter(d => d.kind === 'videoinput');
    cameraSelect.innerHTML = '';
    videos.forEach((d, i) => {
      const opt = document.createElement('option');
      opt.value = d.deviceId;
      opt.textContent = d.label || `Cámara ${i+1}`;
      cameraSelect.appendChild(opt);
    });
  }catch(e){ console.warn('No se pudieron listar cámaras', e); }
}

let stream = null;
let detector = null;
let rafId = null;

async function start(){
  try{
    await listCameras();
    let constraints;
    try {
      // Forzar trasera
      constraints = { video: { facingMode: { exact: "environment" } }, audio: false };
      stream = await navigator.mediaDevices.getUserMedia(constraints);
    } catch(e){
      const deviceId = cameraSelect.value || undefined;
      if (deviceId) constraints = { video: { deviceId: { exact: deviceId } }, audio: false };
      else constraints = { video: { facingMode: "environment" }, audio: false };
      stream = await navigator.mediaDevices.getUserMedia(constraints);
    }
    video.srcObject = stream;
    await video.play();

    if ('BarcodeDetector' in window) {
      detector = new window.BarcodeDetector({formats: ['qr_code']});
      loopDetect();
      msg.textContent = 'Escaneando…';
    } else {
      msg.innerHTML = '⚠️ BarcodeDetector no soportado. Usa el token manual.';
    }
  } catch (e){
    console.error(e);
    msg.textContent = 'No se pudo iniciar cámara: ' + e.message;
  }
}

function stop(){
  cancelAnimationFrame(rafId);
  if (stream){
    stream.getTracks().forEach(t => t.stop());
    stream = null;
  }
  msg.textContent = 'Detenido.';
}

async function loopDetect(){
  if (!detector || !video.videoWidth) {
    rafId = requestAnimationFrame(loopDetect);
    return;
  }
  try{
    const codes = await detector.detect(video);
    if (codes && codes.length){
      const val = codes[0].rawValue || codes[0].rawValueText || '';
      await showProductFromToken(val);
      await new Promise(r => setTimeout(r, 800));
    }
  }catch(e){}
  rafId = requestAnimationFrame(loopDetect);
}

async function showProductFromToken(token){
  if(!token) return;

  if (navigator.onLine && API_BASE){
    try{
      const res = await fetch(API_INFO(), {
        method:'POST', headers:{'Content-Type':'application/json'},
        body: JSON.stringify({token})
      });
      const js = await res.json();
      if(js.ok){
        const p = js.product;
        pName.textContent = p.name || '(sin nombre)';
        pCode.textContent = 'Código: ' + (p.code || '');
        pPrice.textContent = (p.price != null) ? Number(p.price).toFixed(2) : '0.00';
        pStock.textContent = (p.stock == null) ? 'N/D' : p.stock;
        sellQty.value = 1;
        productPanel.style.display = 'block';
        pMsg.textContent = '';
        productPanel.dataset.token = token;
        return;
      }
    } catch(e){ /* sigue abajo */ }
  }

  // Offline o no se pudo contactar backend
  productPanel.style.display = 'block';
  pName.textContent = 'Producto (offline)';
  pCode.textContent = '';
  pPrice.textContent = '0.00';
  pStock.textContent = '—';
  pMsg.textContent = 'Offline: la info completa se obtendrá al sincronizar.';
  productPanel.dataset.token = token;
}

async function sellCurrent(){
  const token = productPanel.dataset.token;
  if(!token){ pMsg.textContent = 'No hay token'; return; }
  const qty = parseInt(sellQty.value || '1', 10);
  const payload = { token, qty };

  if (navigator.onLine && API_BASE){
    try{
      const res = await fetch(API_SALE(), {
        method:'POST', headers:{'Content-Type':'application/json'},
        body: JSON.stringify(payload)
      });
      const js = await res.json();
      if(js.ok){
        pMsg.textContent = `✅ Venta: ${js.registered.name} x ${js.registered.qty} — Total: ${js.registered.total}`;
        await refreshProductsAndPanel();
      } else {
        pMsg.textContent = `❌ ${js.error || 'error registro'}`;
      }
    } catch(e){
      enqueue(payload);
      pMsg.textContent = `🟡 Error de red. Venta encolada (${getQueue().length})`;
    }
  } else {
    enqueue(payload);
    pMsg.textContent = `🟠 Offline. Venta encolada (${getQueue().length})`;
  }
  renderQueueSize();
}

async function syncQueue(){
  if (!navigator.onLine || !API_BASE){
    msg.textContent = '⚠️ Sin internet o sin API_BASE. Nada que sincronizar.';
    return;
  }
  let count = 0;
  while (getQueue().length){
    const item = dequeue();
    try{
      const res = await fetch(API_SALE(), {
        method:'POST', headers:{'Content-Type':'application/json'},
        body: JSON.stringify(item)
      });
      const data = await res.json();
      if (data.ok){ count++; await refreshProductsAndPanel(); }
      else { console.error('Error al sincronizar item', data); }
    } catch(e){
      enqueue(item);
      break;
    }
  }
  msg.textContent = `🔁 Sincronizados: ${count}. En cola: ${getQueue().length}`;
  renderQueueSize();
}

async function refreshProductsAndPanel(){
  // opcional: podríamos volver a pedir info del producto por token
  // si querés ver stock actualizado en vivo, se necesitaría el code adentro del token decodificado del backend
  // aquí lo dejamos simple (el panel seguirá mostrando el último fetch correcto)
}

// Manual token
btnManual.addEventListener('click', ()=>{
  const t = document.getElementById('tokenInput').value.trim();
  if (!t){ msg.textContent = 'Ingresá un token'; return; }
  showProductFromToken(t);
});

btnStart.addEventListener('click', start);
btnStop.addEventListener('click', stop);
btnSync.addEventListener('click', syncQueue);
btnSell.addEventListener('click', sellCurrent);

// init
listCameras();
