// No meal data is stored here. Every state transition runs in one DO transaction.
export const TTL = 30000;
export function transition(previous, request, now) {
  const s = {epoch: 0, owner: '', boot: '', expires: 0, ...previous};
  if (!/^[a-f0-9]{64}$/.test(request.device || '') ||
      !/^[a-f0-9]{64}$/.test(request.boot || '') ||
      !['acquire', 'renew', 'status', 'release'].includes(request.action)) throw Error('Invalid request');
  const owns = s.owner === request.device && s.boot === request.boot && s.expires > now;
  if (request.action === 'acquire' && s.expires <= now) {
    s.owner = request.device; s.boot = request.boot; s.epoch++; s.expires = now + TTL;
  } else if (request.action === 'renew' && owns && request.epoch === s.epoch) {
    s.expires = now + TTL;
  } else if (request.action === 'release' && owns && request.epoch === s.epoch) {
    s.expires = now;
  }
  return {state: s, reply: {owner: s.expires > now ? s.owner : '', boot: s.boot,
    epoch: s.epoch, remaining: Math.max(0, s.expires - now)}};
}
