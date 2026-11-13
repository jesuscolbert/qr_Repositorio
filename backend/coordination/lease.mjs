// No meal data is stored here. Every state transition runs in one DO transaction.
export const TTL = 30000;
export function transition(previous, request, now, policy = {}) {
  const s = {epoch: 0, owner: '', boot: '', expires: 0, ...previous};
  if (!/^[a-f0-9]{64}$/.test(request.device || '') ||
      !/^[a-f0-9]{64}$/.test(request.boot || '') ||
      !['acquire', 'renew', 'status', 'release'].includes(request.action)) throw Error('Invalid request');
  const reports = request.reportsProtocol ?? 0;
  if (!Number.isSafeInteger(reports) || reports < 0 || reports > 1) throw Error('Invalid capability');
  const minimum = policy.minimumReportsProtocol ?? 0;
  const compatible = reports >= minimum || (policy.compatibleDevices || []).includes(request.device);
  const owns = s.owner === request.device && s.boot === request.boot && s.expires > now;
  if (request.action === 'acquire' && compatible && s.expires <= now) {
    s.owner = request.device; s.boot = request.boot; s.epoch++; s.expires = now + TTL;
  } else if (request.action === 'renew' && compatible && owns && request.epoch === s.epoch) {
    s.expires = now + TTL;
  } else if (request.action === 'release' && owns && request.epoch === s.epoch) {
    s.expires = now;
  }
  return {state: s, reply: {owner: s.expires > now ? s.owner : '', boot: s.boot,
    epoch: s.epoch, remaining: Math.max(0, s.expires - now), needsUpdate: !compatible,
    requiredReportsProtocol: minimum}};
}
