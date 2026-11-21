import { beforeEach, describe, expect, test, vi } from 'vitest';
import { handle } from '../lib/session.js';

const grant = {
  server_url: 'wss://lk.example',
  participant_token: 'tok',
  session_id: 'as_01j9z3k7q4m8xv6t2n5r8b1c0d',
  expires_at: '2026-11-21T17:00:00Z',
  effective: { prompt: 'p', intro: 'i', voice_id: 'v', client_tools: [], web_search: false },
  limits: { max_duration_s: 900, idle_timeout_s: 120 },
};

beforeEach(() => { process.env.DEVICE_SECRET = 's3cret'; });

describe('handle', () => {
  test('wrong secret is 401 and never calls HUMAIN', async () => {
    const createSession = vi.fn();
    const res = await handle({ secret: 'x', body: { device_id: 'd' } }, { createSession, resolveVoice: async () => undefined });
    expect(res).toEqual({ status: 401, body: { error: 'bad_secret' } });
    expect(createSession).not.toHaveBeenCalled();
  });

  test('a valid request returns only the fields the app needs', async () => {
    const createSession = vi.fn().mockResolvedValue(grant);
    const res = await handle({ secret: 's3cret', body: { device_id: 'p3pro-1' } }, { createSession, resolveVoice: async () => undefined });
    expect(res).toEqual({
      status: 200,
      body: {
        server_url: 'wss://lk.example',
        participant_token: 'tok',
        session_id: 'as_01j9z3k7q4m8xv6t2n5r8b1c0d',
        expires_at: '2026-11-21T17:00:00Z',
        limits: { max_duration_s: 900, idle_timeout_s: 120 },
      },
    });
  });

  test('the resolved voice id reaches HUMAIN', async () => {
    const createSession = vi.fn().mockResolvedValue(grant);
    const resolveVoice = vi.fn(async (label?: string) => (label === 'mul_abdullah' ? 'voice-abd' : undefined));
    await handle({ secret: 's3cret', body: { device_id: 'd', voice_label: 'mul_abdullah' } }, { createSession, resolveVoice });
    expect(createSession.mock.calls[0][0].agent.voice_id).toBe('voice-abd');
    expect(createSession.mock.calls[0][0].participant.identity).toBe('d');
  });

  test('a voice label outside the two allowed ones is 400', async () => {
    const createSession = vi.fn();
    const res = await handle({ secret: 's3cret', body: { device_id: 'd', voice_label: 'mul_nourah' } }, { createSession, resolveVoice: async () => 'x' });
    expect(res.status).toBe(400);
    expect(createSession).not.toHaveBeenCalled();
  });

  test.each(['AUTH_SCOPE_MISSING', 'CREDITS_EXHAUSTED', 'CONCURRENCY_LIMIT_EXCEEDED', 'RATE_LIMIT_EXCEEDED'])(
    'HUMAIN code %s is passed through as 502', async (code) => {
      const createSession = vi.fn().mockRejectedValue(Object.assign(new Error('x'), { code }));
      const res = await handle({ secret: 's3cret', body: { device_id: 'd' } }, { createSession, resolveVoice: async () => undefined });
      expect(res).toEqual({ status: 502, body: { error: 'humain', code } });
    });

  test.each([
    ['missing', {}],
    ['empty', { device_id: '' }],
    ['not a string', { device_id: 42 }],
    ['129 characters', { device_id: 'a'.repeat(129) }],
    ['body is not an object', 'hello'],
  ])('device_id %s is 400 and never calls HUMAIN', async (_name, body) => {
    const createSession = vi.fn();
    const res = await handle({ secret: 's3cret', body }, { createSession, resolveVoice: async () => undefined });
    expect(res).toEqual({ status: 400, body: { error: 'bad_request' } });
    expect(createSession).not.toHaveBeenCalled();
  });

  test('device_id of exactly 128 characters is accepted', async () => {
    const createSession = vi.fn().mockResolvedValue(grant);
    const res = await handle({ secret: 's3cret', body: { device_id: 'a'.repeat(128) } }, { createSession, resolveVoice: async () => undefined });
    expect(res.status).toBe(200);
  });
});
