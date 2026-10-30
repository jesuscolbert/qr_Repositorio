import {transition} from './lease.mjs';

export class LeaderLease {
  constructor(ctx, env) { this.ctx = ctx; this.env = env; }
  async fetch(request) {
    const input = await request.json();
    const reply = await this.ctx.storage.transaction(async tx => {
      const next = transition(await tx.get('lease'), input, Date.now());
      await tx.put('lease', next.state);
      return next.reply;
    });
    return Response.json(reply, {headers: {'Cache-Control': 'no-store'}});
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== 'POST' || url.pathname !== '/lease') return new Response('Not found', {status: 404});
    // App-only secret, never a Cloudflare management token. Do not log requests.
    const supplied = request.headers.get('Authorization') || '';
    const expected = 'Bearer ' + env.NETWORK_KEY;
    const a = new TextEncoder().encode(supplied), b = new TextEncoder().encode(expected);
    if (!env.NETWORK_KEY || a.length !== b.length || !crypto.subtle.timingSafeEqual(a, b)) return new Response('Unauthorized', {status: 401});
    const body = await request.text();
    if (body.length > 1024) return new Response('Too large', {status: 413});
    try {
      const input = JSON.parse(body);
      // Validate before creating or accessing the single installation object.
      transition(undefined, input, Date.now());
      return env.LEADER.get(env.LEADER.idFromName('comedor')).fetch(new Request('https://internal/lease', {method: 'POST', body}));
    } catch { return new Response('Invalid request', {status: 400}); }
  }
};
