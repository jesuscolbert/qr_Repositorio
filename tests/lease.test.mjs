import {test} from 'node:test';
import assert from 'node:assert/strict';
import {transition, TTL} from '../backend/coordination/lease.mjs';
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
