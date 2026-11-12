// Only pseudonymous binding metadata lives here; meal records remain on supervisors' phones.
const hex = /^[a-f0-9]{64}$/;
export function validateWallet(r) {
  if (!r || !['get','changes','bind','revoke'].includes(r.action)) throw Error('Invalid action');
  if(r.action==='changes') {if(!Number.isSafeInteger(r.after)||r.after<0)throw Error('Invalid cursor');return;}
  if(!hex.test(r.tag||''))throw Error('Invalid person');
  if(r.action==='get')return;
  if(!Number.isSafeInteger(r.expected)||r.expected<0||!hex.test(r.requestId||'')||!hex.test(r.actor||'')||typeof r.by!=='string'||r.by.length<3||r.by.length>100)throw Error('Invalid binding');
  if(r.action==='bind'&&!hex.test(r.keyHash||''))throw Error('Invalid key');
}
export async function walletOperation(tx,r,now) {
  validateWallet(r);
  const revision=await tx.get('walletRevision')||0;
  if(r.action==='changes') {
    if(r.after>revision)throw Error('Future cursor');
    const changes=await tx.list({prefix:'walletLog:',startAfter:'walletLog:'+String(r.after).padStart(16,'0'),limit:100});
    const items=[...changes.values()];return {ok:true,items,after:items.length?items.at(-1).change:r.after,revision};
  }
  const previous=await tx.get('wallet:'+r.tag);
  if(r.action==='get')return {ok:true,binding:previous||null};
  const active=r.action==='bind';
  if(previous?.requestId===r.requestId) {
    if(previous.active!==active||active&&previous.keyHash!==r.keyHash)throw Error('Request mismatch');
    return {ok:true,binding:previous};
  }
  if((previous?.version||0)!==r.expected)return {ok:false,error:'La vinculación cambió en otro teléfono. Vuelve a consultar antes de confirmar.'};
  if(active) {
    const other=await tx.get('walletKey:'+r.keyHash);
    if(other&&other!==r.tag)return {ok:false,error:'Este celular ya está vinculado a otro trabajador de esta empresa.'};
  }
  if(previous?.active)await tx.delete('walletKey:'+previous.keyHash);
  const binding={tag:r.tag,version:(previous?.version||0)+1,keyHash:active?r.keyHash:'',active,time:now,actor:r.actor,by:r.by,requestId:r.requestId,change:revision+1};
  await tx.put('wallet:'+r.tag,binding);await tx.put('walletRevision',revision+1);await tx.put('walletLog:'+String(revision+1).padStart(16,'0'),binding);
  if(active)await tx.put('walletKey:'+r.keyHash,r.tag);
  return {ok:true,binding};
}
