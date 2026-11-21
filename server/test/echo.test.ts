import { beforeEach, describe, expect, test, vi } from 'vitest';
import { buildSessionRequest, handle } from '../lib/session.js';

const grant = { server_url: 'w', participant_token: 't', session_id: 's', expires_at: 'e', limits: { max_duration_s: null, idle_timeout_s: null } };
beforeEach(() => { process.env.DEVICE_SECRET = 's3cret'; });

describe('echo mode', () => {
  test('the echo call has a long scripted intro and tells the agent to stay quiet after it', () => {
    const normal = buildSessionRequest({ deviceId: 'd' });
    const echo = buildSessionRequest({ deviceId: 'd', mode: 'echo' });
    expect(echo.agent.intro).toContain('اختبار للصدى');
    expect(echo.agent.intro.length).toBeGreaterThan(normal.agent.intro.length * 5);
    expect(echo.agent.prompt).toContain('لا تتكلم');
    expect(echo.agent.prompt).not.toBe(normal.agent.prompt);
  });

  test('mode echo reaches HUMAIN as the echo script', async () => {
    const createSession = vi.fn().mockResolvedValue(grant);
    const res = await handle({ secret: 's3cret', body: { device_id: 'd', mode: 'echo' } }, { createSession, resolveVoice: async () => undefined });
    expect(res.status).toBe(200);
    expect(createSession.mock.calls[0][0].agent.intro).toContain('اختبار للصدى');
  });

  test('a request without mode is the normal call', async () => {
    const createSession = vi.fn().mockResolvedValue(grant);
    await handle({ secret: 's3cret', body: { device_id: 'd' } }, { createSession, resolveVoice: async () => undefined });
    expect(createSession.mock.calls[0][0].agent.intro).toBe('هلا والله، آمرني.');
  });

  test('an unknown mode is 400 and starts nothing', async () => {
    const createSession = vi.fn();
    const res = await handle({ secret: 's3cret', body: { device_id: 'd', mode: 'party' } }, { createSession, resolveVoice: async () => undefined });
    expect(res).toEqual({ status: 400, body: { error: 'bad_request' } });
    expect(createSession).not.toHaveBeenCalled();
  });
});
