import { describe, expect, test, vi } from 'vitest';
import { makeCreateSession } from '../lib/humain.js';
import { makeResolveVoice } from '../lib/voices.js';

const request = {
  participant: { identity: 'd', gender: 'male' as const },
  agent: { prompt: 'p', intro: 'i', web_search: false },
};

function reply(status: number, body: string): Response {
  return new Response(body, { status, headers: { 'content-type': 'application/json' } });
}

describe('makeCreateSession', () => {
  test('posts the request to /agent/sessions with the api key and returns the grant', async () => {
    const fetchImpl = vi.fn(async () => reply(201, JSON.stringify({ server_url: 'wss://x', participant_token: 't', session_id: 's', expires_at: 'e', limits: { max_duration_s: null, idle_timeout_s: 120 } })));
    const out = await makeCreateSession('https://api.voice.humain.com/', 'KEY', fetchImpl)(request);
    expect(out.participant_token).toBe('t');
    const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('https://api.voice.humain.com/agent/sessions');
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>)['x-api-key']).toBe('KEY');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(JSON.parse(init.body as string)).toEqual(request);
  });

  test.each([
    ['top-level code', 403, '{"code":"AUTH_SCOPE_MISSING","message":"m"}', 'AUTH_SCOPE_MISSING'],
    ['nested error.code', 402, '{"error":{"code":"CREDITS_EXHAUSTED"}}', 'CREDITS_EXHAUSTED'],
    ['body that is not JSON', 500, '<html>oops</html>', 'HTTP_500'],
    ['JSON without a code', 429, '{"message":"slow down"}', 'HTTP_429'],
  ])('rejects with the HUMAIN code: %s', async (_name, status, body, code) => {
    const create = makeCreateSession('https://api', 'KEY', async () => reply(status, body));
    await expect(create(request)).rejects.toMatchObject({ code });
  });

  test('a network failure rejects with code NETWORK', async () => {
    const create = makeCreateSession('https://api', 'KEY', async () => { throw new TypeError('fetch failed'); });
    await expect(create(request)).rejects.toMatchObject({ code: 'NETWORK' });
  });
});

describe('makeResolveVoice', () => {
  const voices = [{ id: 'id-nourah', label: 'mul_nourah' }, { id: 'id-abd', label: 'mul_abdullah' }];

  test('maps a label to its id', async () => {
    expect(await makeResolveVoice(async () => voices)('mul_abdullah')).toBe('id-abd');
  });

  test('no label means no lookup', async () => {
    const list = vi.fn(async () => voices);
    expect(await makeResolveVoice(list)(undefined)).toBeUndefined();
    expect(list).not.toHaveBeenCalled();
  });

  test('a label the account does not have resolves to undefined', async () => {
    expect(await makeResolveVoice(async () => voices)('mul_abdulaziz')).toBeUndefined();
  });

  test('the voice list is fetched once and reused', async () => {
    const list = vi.fn(async () => voices);
    const resolve = makeResolveVoice(list);
    await resolve('mul_abdullah');
    await resolve('mul_nourah');
    expect(list).toHaveBeenCalledTimes(1);
  });

  test('a failed lookup resolves to undefined and is retried next time', async () => {
    const list = vi.fn().mockRejectedValueOnce(new Error('timeout')).mockResolvedValueOnce(voices);
    const resolve = makeResolveVoice(list);
    expect(await resolve('mul_abdullah')).toBeUndefined();
    expect(await resolve('mul_abdullah')).toBe('id-abd');
  });
});
