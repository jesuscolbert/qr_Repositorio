import {test} from 'node:test';
import assert from 'node:assert/strict';
import {walletOperation,validateWallet} from '../backend/coordination/wallet.mjs';
const tag='a'.repeat(64),key='b'.repeat(64),actor='c'.repeat(64);
class Store {
 data=new Map();async get(k){return this.data.get(k);}async put(k,v){this.data.set(k,structuredClone(v));}async delete(k){this.data.delete(k);}
 async list(o){return new Map([...this.data].sort(([a],[b])=>a.localeCompare(b)).filter(([k])=>k.startsWith(o.prefix)&&k>o.startAfter).slice(0,o.limit));}
}
const bind=(x={})=>({action:'bind',tag,keyHash:key,actor,by:'Encargado prueba',expected:0,requestId:'d'.repeat(64),...x});
test('binding is idempotent, rejects concurrent replacement, and records revocation',async()=>{
 const tx=new Store();let r=await walletOperation(tx,bind(),100);assert.equal(r.binding.version,1);
 assert.equal((await walletOperation(tx,bind(),101)).binding.version,1);
 assert.equal((await walletOperation(tx,bind({keyHash:'e'.repeat(64),requestId:'f'.repeat(64)}),102)).ok,false);
 r=await walletOperation(tx,bind({expected:1,keyHash:'e'.repeat(64),requestId:'f'.repeat(64)}),103);assert.equal(r.binding.version,2);
 r=await walletOperation(tx,bind({action:'revoke',expected:2,requestId:'1'.repeat(64)}),104);assert.equal(r.binding.active,false);
 assert.equal((await walletOperation(tx,{action:'get',tag},105)).binding.version,3);
 const log=await walletOperation(tx,{action:'changes',after:0},106);assert.equal(log.items.length,3);assert.equal(log.after,3);assert.equal(log.items[0].by,'Encargado prueba');
 assert.equal((await walletOperation(tx,{action:'changes',after:3},106)).items.length,0);
});
test('one phone key cannot activate another worker until revoked',async()=>{
 const tx=new Store();await walletOperation(tx,bind(),100);
 assert.equal((await walletOperation(tx,bind({tag:'e'.repeat(64)}),101)).ok,false);
 await walletOperation(tx,bind({action:'revoke',expected:1,requestId:'f'.repeat(64)}),102);
 assert.equal((await walletOperation(tx,bind({tag:'e'.repeat(64)}),103)).ok,true);
});
test('registry validates selectors and paginates without exposing worker or meal data',async()=>{
 const tx=new Store();for(let i=0;i<105;i++)await walletOperation(tx,bind({tag:i.toString(16).padStart(64,'0'),keyHash:(i+1000).toString(16).padStart(64,'0')}),100+i);
 const first=await walletOperation(tx,{action:'changes',after:0},1000),second=await walletOperation(tx,{action:'changes',after:first.after},1000);
 assert.equal(first.items.length,100);assert.equal(second.items.length,5);assert.equal(second.after,105);
 assert.ok(first.items.every(x=>!('person' in x)&&!('meals' in x)&&!('name' in x)));
 for(const request of [{action:'changes',after:-1},{action:'get',tag:'123'},{...bind(),expected:.5},{...bind(),keyHash:'bad'}])assert.throws(()=>validateWallet(request));
 await assert.rejects(walletOperation(tx,{action:'changes',after:106},1000));
});
