import {test} from 'node:test';
import assert from 'node:assert/strict';
import {transition, TTL} from '../backend/coordination/lease.mjs';
import {serverPolicy} from '../backend/coordination/worker.mjs';
const req = (id, action = 'acquire', epoch = 0, boot = id) => ({device: id.repeat(64), boot: boot.repeat(64), action, epoch});
test('simultaneous candidates have only one owner', () => {
  let s;
  for (const id of ['a', 'b', 'c', 'd']) s = transition(s, req(id), 100).state;
  assert.equal(s.owner, 'a'.repeat(64)); assert.equal(s.epoch, 1);
});
test('crashed owner is replaced and stale renewals cannot resurrect it', () => {
  let s = transition(undefined, req('a'), 100).state;
  s = transition(s, req('b'), 100 + TTL).state;
  assert.equal(s.owner, 'b'.repeat(64)); assert.equal(s.epoch, 2);
  s = transition(s, req('a', 'renew', 1), 101 + TTL).state;
  assert.equal(s.owner, 'b'.repeat(64)); assert.equal(s.epoch, 2);
});
test('renew, release and restart require exact owner boot and epoch', () => {
  let s = transition(undefined, req('a'), 100).state;
  const expires = s.expires;
  s = transition(s, req('a', 'renew', 1, 'c'), 200).state;
  assert.equal(s.expires, expires);
  s = transition(s, req('a', 'renew', 1), 200).state;
  assert.equal(s.expires, 200 + TTL);
  s = transition(s, req('a', 'release', 0), 300).state;
  assert.equal(s.expires, 200 + TTL);
  s = transition(s, req('a', 'release', 1), 300).state;
  s = transition(s, req('b'), 301).state;
  assert.equal(s.owner, 'b'.repeat(64));
});
test('expired renewal is fenced, same owner reacquires with a new epoch', () => {
  let s = transition(undefined, req('a'), 0).state;
  assert.equal(transition(s, req('a', 'renew', 1), TTL).reply.owner, '');
  assert.equal(transition(s, req('a'), TTL).state.epoch, 2);
});
test('status is read only and invalid request is rejected', () => {
  const s = transition(undefined, req('a'), 10).state;
  assert.deepEqual(transition(s, req('b', 'status'), 20).state, s);
  assert.throws(() => transition(s, {action: 'acquire'}, 20));
});

const policy={minimumReportsProtocol:1,compatibleDevices:['c'.repeat(64)]};
const modern=(id,action='acquire',epoch=0,boot=id)=>({...req(id,action,epoch,boot),reportsProtocol:1});
test('upgrade drains the old lease without overlapping owners',()=>{
  let s=transition(undefined,req('a'),0).state;
  s=transition(s,req('a','renew',1),1000,policy).state;
  assert.equal(s.expires,TTL);
  const wait=transition(s,modern('b'),TTL-1,policy);
  assert.equal(wait.reply.owner,'a'.repeat(64));
  s=transition(s,modern('b'),TTL,policy).state;
  assert.equal(s.owner,'b'.repeat(64));assert.equal(s.epoch,2);
  assert.equal(transition(s,req('a','renew',1),TTL+1,policy).state.owner,s.owner);
});
test('old phones cannot retake ownership after a compatible phone shuts down',()=>{
  let s=transition(undefined,modern('b'),0,policy).state;
  const expired=transition(s,req('a'),TTL,policy);
  assert.equal(expired.reply.owner,'');assert.equal(expired.reply.needsUpdate,true);
  s=transition(expired.state,modern('d'),TTL+10,policy).state;
  assert.equal(s.owner,'d'.repeat(64));
  s=transition(s,req('a'),TTL*2+10,policy).state;
  assert.equal(transition(s,req('a','status'),TTL*2+11,policy).reply.owner,'');
  s=transition(s,modern('b','acquire',0,'e'),TTL*2+20,policy).state;
  assert.equal(s.owner,'b'.repeat(64));assert.equal(s.boot,'e'.repeat(64));
});
test('verified 1.12.1 devices stay compatible without trusting unknown old phones',()=>{
  const denied=transition(undefined,req('a'),0,policy);
  assert.equal(denied.reply.owner,'');
  let s=transition(undefined,req('c'),0,policy).state;
  assert.equal(s.owner,'c'.repeat(64));
  s=transition(s,req('c','renew',1),1000,policy).state;assert.equal(s.expires,1000+TTL);
});
test('capability and deployment policy must be explicit and valid',()=>{
  for(const reportsProtocol of [-1,2,'1',null,1.5]){
    if(reportsProtocol===null)continue;
    assert.throws(()=>transition(undefined,{...req('a'),reportsProtocol},0,policy));
  }
  assert.deepEqual(serverPolicy({REQUIRED_REPORTS_PROTOCOL:'1',REPORT_COMPAT_DEVICES:JSON.stringify(['c'.repeat(64)])}),policy);
  assert.throws(()=>serverPolicy({REQUIRED_REPORTS_PROTOCOL:'oops'}));
  assert.throws(()=>serverPolicy({REPORT_COMPAT_DEVICES:'["invalid"]'}));
});
