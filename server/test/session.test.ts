import { describe, expect, test } from 'vitest';
import { buildSessionRequest, isAuthorized, toErrorBody } from '../lib/session.js';

describe('buildSessionRequest', () => {
  test('builds the phase-0 request', () => {
    const r = buildSessionRequest({ deviceId: 'p3pro-1', voiceId: 'v-123' });
    expect(r.participant).toEqual({ identity: 'p3pro-1', gender: 'male' });
    expect(r.agent.voice_id).toBe('v-123');
    expect(r.agent.intro).toBe('هلا والله، آمرني.');
    expect(r.agent.prompt).toContain('سنافي');
    expect(r.agent.web_search).toBe(false);
    expect(Object.keys(r)).toEqual(['participant', 'agent']);
  });

  test('omits voice_id when no voice was resolved', () => {
    expect('voice_id' in buildSessionRequest({ deviceId: 'd' }).agent).toBe(false);
  });
});

describe('isAuthorized', () => {
  test('accepts only the exact secret', () => {
    expect(isAuthorized('s3cret', 's3cret')).toBe(true);
    expect(isAuthorized('wrong', 's3cret')).toBe(false);
    expect(isAuthorized('s3cre', 's3cret')).toBe(false);
    expect(isAuthorized(undefined, 's3cret')).toBe(false);
  });

  test('an empty configured secret authorizes nobody', () => {
    expect(isAuthorized('', '')).toBe(false);
  });
});

describe('toErrorBody', () => {
  test('passes a HUMAIN code through', () => {
    expect(toErrorBody(Object.assign(new Error('x'), { code: 'CREDITS_EXHAUSTED' })))
      .toEqual({ status: 502, body: { error: 'humain', code: 'CREDITS_EXHAUSTED' } });
  });

  test('uses UNKNOWN when the error carries no code', () => {
    expect(toErrorBody(new Error('boom'))).toEqual({ status: 502, body: { error: 'humain', code: 'UNKNOWN' } });
  });
});
