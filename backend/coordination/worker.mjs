import {transition} from './lease.mjs';
import {validateWallet,walletOperation} from './wallet.mjs';
import {staffOperation,authenticate,authorizeWallet,policy} from './staff.mjs';

export function serverPolicy(env) {
  const minimumReportsProtocol=Number(env.REQUIRED_REPORTS_PROTOCOL||0);
  const compatibleDevices=JSON.parse(env.REPORT_COMPAT_DEVICES||'[]');
  if(![0,1].includes(minimumReportsProtocol)||!Array.isArray(compatibleDevices)||compatibleDevices.length>100||compatibleDevices.some(id=>typeof id!=='string'||!/^[a-f0-9]{64}$/.test(id)))throw Error('Invalid server policy');
  return {minimumReportsProtocol,compatibleDevices};
}

export class LeaderLease {
  constructor(ctx, env) { this.ctx = ctx; this.env = env; }
  async fetch(request) {
    const input = await request.json();const path=new URL(request.url).pathname,now=Date.now();
    if(path==='/staff'){
      try {const reply=await this.ctx.storage.transaction(tx=>staffOperation(tx,input,this.env,now));return Response.json(reply,{headers:{'Cache-Control':'no-store'}});}
      catch(e){return Response.json({ok:false,error:e.message},{headers:{'Cache-Control':'no-store'}});}
    }
    if(path==='/wallet') {
      const reply=await this.ctx.storage.transaction(async tx=>{
        let operation=input;
        if(await tx.get('staffEnabled')){const auth=await authenticate(tx,input.staff,'wallet',this.env,now);operation=auth.input;
          const extra=await authorizeWallet(tx,operation,auth.user,this.env,now);if(extra)return extra;
          if(['bind','revoke'].includes(operation.action)){operation.actor=auth.user.device;operation.by=auth.user.name;}
        }
        return walletOperation(tx,operation,now);
      });
      return Response.json(reply,{headers:{'Cache-Control':'no-store'}});
    }
    const reply = await this.ctx.storage.transaction(async tx => {
      const enabled=!!await tx.get('staffEnabled');let operation=input;
      if(enabled){const auth=await authenticate(tx,input.staff,'lease',this.env,now);operation=auth.input;
        if(operation.device!==auth.user.device)throw Error('Device mismatch');}
      // Never shorten a live lease when enforcing the upgrade. Its old owner
      // stops at its original deadline before a compatible phone can acquire.
      const next = transition(await tx.get('lease'), operation, Date.now(), serverPolicy(this.env));
      await tx.put('lease', next.state);
      return {...next.reply,walletRevision:await tx.get('walletRevision')||0,staffEnabled:enabled,staffRevision:await tx.get('staffRevision')||0};
    });
    return Response.json(reply, {headers: {'Cache-Control': 'no-store'}});
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== 'POST' || !['/lease','/wallet','/staff'].includes(url.pathname)) return new Response('Not found', {status: 404});
    // App-only secret, never a Cloudflare management token. Do not log requests.
    const supplied = request.headers.get('Authorization') || '';
    const expected = 'Bearer ' + env.NETWORK_KEY;
    const a = new TextEncoder().encode(supplied), b = new TextEncoder().encode(expected);
    if (!env.NETWORK_KEY || a.length !== b.length || !crypto.subtle.timingSafeEqual(a, b)) return new Response('Unauthorized', {status: 401});
    const body = await request.text();
    if (body.length > 24000) return new Response('Too large', {status: 413});
    try {
      const input = JSON.parse(body);
      // Validate before creating or accessing the single installation object.
      if(url.pathname==='/staff')policy(env);
      else if(!input.staff){if(url.pathname==='/lease')transition(undefined,input,Date.now());else validateWallet(input);}
      return await env.LEADER.get(env.LEADER.idFromName('comedor')).fetch(new Request('https://internal'+url.pathname, {method: 'POST', body}));
    } catch { return new Response('Invalid request', {status: 400}); }
  }
};
