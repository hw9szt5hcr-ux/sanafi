import { Readable } from 'node:stream';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import handler from '../api/agent-session.js';

function call(method: string, headers: Record<string, string>, body: string) {
  const req = Object.assign(Readable.from([Buffer.from(body)]), { method, headers }) as unknown as IncomingMessage;
  const out = { status: 0, body: '', headers: {} as Record<string, string> };
  const res = {
    setHeader: (k: string, v: string) => { out.headers[k] = v; },
    set statusCode(v: number) { out.status = v; },
    end: (text: string) => { out.body = text; },
  } as unknown as ServerResponse;
  return handler(req, res).then(() => ({ ...out, json: JSON.parse(out.body) }));
}

beforeEach(() => {
  process.env.API_URL = 'https://api.voice.humain.com';
  process.env.API_KEY = 'KEY';
  process.env.DEVICE_SECRET = 's3cret';
});
afterEach(() => { vi.unstubAllGlobals(); });

describe('POST /api/agent-session', () => {
  test('GET is refused with 405', async () => {
    const out = await call('GET', {}, '');
    expect(out.status).toBe(405);
  });

  test('a wrong secret is 401 and reaches no network', async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);
    const out = await call('POST', { 'x-device-secret': 'nope' }, '{"device_id":"d"}');
    expect(out.status).toBe(401);
    expect(out.json).toEqual({ error: 'bad_secret' });
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test('a body that is not JSON is 400', async () => {
    const out = await call('POST', { 'x-device-secret': 's3cret' }, 'not json');
    expect(out.status).toBe(400);
  });

  test('a good request returns the grant and is never cached', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      server_url: 'wss://lk', participant_token: 'tok', session_id: 'as_1', expires_at: 'e',
      limits: { max_duration_s: 900, idle_timeout_s: 120 },
    }), { status: 201 })));
    const out = await call('POST', { 'x-device-secret': 's3cret' }, '{"device_id":"p3pro-1"}');
    expect(out.status).toBe(200);
    expect(out.json.participant_token).toBe('tok');
    expect(out.headers['Cache-Control']).toBe('no-store');
  });

  test('HUMAIN refusing the key surfaces its code', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"code":"AUTH_SCOPE_MISSING"}', { status: 403 })));
    const out = await call('POST', { 'x-device-secret': 's3cret' }, '{"device_id":"d"}');
    expect(out.status).toBe(502);
    expect(out.json).toEqual({ error: 'humain', code: 'AUTH_SCOPE_MISSING' });
  });

  test('a wrong secret is 401 even when the API key is not configured', async () => {
    delete process.env.API_KEY;
    const out = await call('POST', { 'x-device-secret': 'nope' }, '{"device_id":"d"}');
    expect(out.status).toBe(401);
    expect(out.json).toEqual({ error: 'bad_secret' });
  });

  test('the key is also accepted when the variable was saved in lowercase as api_key', async () => {
    delete process.env.API_KEY;
    process.env.api_key = 'LOWER';
    const fetchSpy = vi.fn(async () => new Response(JSON.stringify({
      server_url: 'wss://lk', participant_token: 'tok', session_id: 'as_1', expires_at: 'e',
      limits: { max_duration_s: 900, idle_timeout_s: 120 },
    }), { status: 201 }));
    vi.stubGlobal('fetch', fetchSpy);
    try {
      const out = await call('POST', { 'x-device-secret': 's3cret' }, '{"device_id":"d"}');
      expect(out.status).toBe(200);
      const init = (fetchSpy.mock.calls[0] as unknown as [string, RequestInit])[1];
      expect((init.headers as Record<string, string>)['x-api-key']).toBe('LOWER');
    } finally {
      delete process.env.api_key;
    }
  });

  test('a missing API key is a 500, not a crash', async () => {
    delete process.env.API_KEY;
    const out = await call('POST', { 'x-device-secret': 's3cret' }, '{"device_id":"d"}');
    expect(out.status).toBe(500);
    expect(out.json).toEqual({ error: 'server_misconfigured' });
  });
});
