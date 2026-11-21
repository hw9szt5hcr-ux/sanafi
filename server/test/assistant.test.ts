import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ASSISTANT_PROMPT, CLIENT_TOOLS, buildAssistantRequest } from '../lib/assistant.js';
import { HumainError } from '../lib/humain.js';
import { ECHO_PROMPT, PROMPT, handle } from '../lib/session.js';

const grant = {
  server_url: 'wss://lk.example',
  participant_token: 'tok',
  session_id: 'as_01j9z3k7q4m8xv6t2n5r8b1c0d',
  expires_at: '2026-11-22T17:00:00Z',
  effective: { voice_id: 'v9' },
  limits: { max_duration_s: null, idle_timeout_s: null },
};

beforeEach(() => { process.env.DEVICE_SECRET = 's3cret'; });

describe('buildAssistantRequest', () => {
  it('builds a session with the seven tools, web search and both variables', () => {
    const r = buildAssistantRequest({ deviceId: 'd1', voiceId: 'v1', memory: 'اسمه عبدالله', apps: ['YouTube Music', 'Maps'] });
    expect(r.agent.client_tools.map((t) => t.name)).toEqual(['play_music', 'navigate', 'call_contact', 'open_app', 'media_control', 'remember', 'forget']);
    expect(r.agent.web_search).toBe(true);
    expect(r.agent.intro).toBe('آمر');
    expect(r.agent.voice_id).toBe('v1');
    expect(r.agent.prompt).toBe(ASSISTANT_PROMPT);
    expect(r.agent.tool_acknowledgments).toEqual({ initial: { enabled: true, after_ms: 1500 }, long_wait: { enabled: true, after_ms: 10000 } });
    expect(r.participant).toEqual({ identity: 'd1', gender: 'male' });
    expect(r.variables).toEqual({ memory: 'اسمه عبدالله', apps: 'YouTube Music، Maps' });
  });

  it('uses a placeholder word when memory or apps are empty, so no variable is ever missing', () => {
    const r = buildAssistantRequest({ deviceId: 'd1', memory: '  ', apps: [] });
    expect(r.variables).toEqual({ memory: 'لا شيء', apps: 'لا شيء' });
    expect(buildAssistantRequest({ deviceId: 'd1' }).variables).toEqual({ memory: 'لا شيء', apps: 'لا شيء' });
    expect('voice_id' in r.agent).toBe(false);
  });

  it('braces are removed from memory and app names so a value can never look like a placeholder', () => {
    const r = buildAssistantRequest({ deviceId: 'd1', memory: 'يحب {{x}}', apps: ['A}}B'] });
    expect(r.variables).toEqual({ memory: 'يحب x', apps: 'AB' });
  });

  it('talk-first sessions have an empty intro', () => {
    expect(buildAssistantRequest({ deviceId: 'd1', talkFirst: true }).agent.intro).toBe('');
  });

  it('the prompt uses exactly the two variables and names every tool and end_call', () => {
    expect([...new Set([...ASSISTANT_PROMPT.matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1]))].sort()).toEqual(['apps', 'memory']);
    for (const name of [...CLIENT_TOOLS.map((t) => t.name), 'end_call']) expect(ASSISTANT_PROMPT).toContain(name);
    expect(ASSISTANT_PROMPT.length).toBeLessThanOrEqual(4000);
  });

  it('every tool has a description and a JSON-schema object with its required argument', () => {
    const required: Record<string, string[]> = {
      play_music: ['query'], navigate: ['place'], call_contact: ['name'], open_app: ['app'], media_control: ['action'], remember: ['fact'], forget: ['fact'],
    };
    expect(CLIENT_TOOLS.length).toBeLessThanOrEqual(16);
    for (const t of CLIENT_TOOLS) {
      expect(t.name).toMatch(/^[A-Za-z0-9_-]{1,64}$/);
      expect(t.description.length).toBeGreaterThan(20);
      expect(t.description.length).toBeLessThanOrEqual(4096);
      expect(t.parameters).toMatchObject({ type: 'object', required: required[t.name] });
      expect(Object.keys(t.parameters.properties)).toEqual(required[t.name]);
    }
    expect(CLIENT_TOOLS.find((t) => t.name === 'media_control')!.parameters.properties.action.enum)
      .toEqual(['play', 'pause', 'next', 'previous', 'volume_up', 'volume_down']);
  });
});

describe('handle in assistant mode', () => {
  const ask = (extra: object = {}) => ({ secret: 's3cret', body: { device_id: 'sanafi-1', mode: 'assistant', voice_label: 'mul_abdullah', ...extra } });

  it('sends the assistant request and returns the effective voice id', async () => {
    const createSession = vi.fn().mockResolvedValue(grant);
    const res = await handle(ask({ memory: 'اسمه عبدالله', apps: ['Maps'] }), { createSession, resolveVoice: async () => 'v-abd' });
    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      server_url: 'wss://lk.example', participant_token: 'tok', session_id: 'as_01j9z3k7q4m8xv6t2n5r8b1c0d',
      expires_at: '2026-11-22T17:00:00Z', limits: { max_duration_s: null, idle_timeout_s: null }, voice_id: 'v9',
    });
    const sent = createSession.mock.calls[0][0];
    expect(sent.agent.client_tools).toHaveLength(7);
    expect(sent.agent.voice_id).toBe('v-abd');
    expect(sent.variables).toEqual({ memory: 'اسمه عبدالله', apps: 'Maps' });
  });

  it('a device-supplied voice id skips the voice lookup', async () => {
    const createSession = vi.fn().mockResolvedValue(grant);
    const resolveVoice = vi.fn(async () => 'looked-up');
    await handle(ask({ voice_id: 'v1' }), { createSession, resolveVoice });
    expect(resolveVoice).not.toHaveBeenCalled();
    expect(createSession.mock.calls[0][0].agent.voice_id).toBe('v1');
  });

  it('a stale device voice id is resolved again by label and retried once', async () => {
    const createSession = vi.fn()
      .mockRejectedValueOnce(new HumainError('VALIDATION_INVALID_PARAM', 400, ['agent.voice_id']))
      .mockResolvedValueOnce(grant);
    const resolveVoice = vi.fn(async () => 'v2');
    const res = await handle(ask({ voice_id: 'stale' }), { createSession, resolveVoice });
    expect(res.status).toBe(200);
    expect(createSession).toHaveBeenCalledTimes(2);
    expect(createSession.mock.calls[1][0].agent.voice_id).toBe('v2');
    expect(resolveVoice).toHaveBeenCalledWith('mul_abdullah');
  });

  it('a validation failure about anything else is not retried', async () => {
    const createSession = vi.fn().mockRejectedValue(new HumainError('VALIDATION_INVALID_PARAM', 400, ['agent.prompt']));
    const res = await handle(ask({ voice_id: 'v1' }), { createSession, resolveVoice: async () => 'v2' });
    expect(res).toEqual({ status: 502, body: { error: 'humain', code: 'VALIDATION_INVALID_PARAM' } });
    expect(createSession).toHaveBeenCalledTimes(1);
  });

  it('rejects oversized memory, too many apps, a long or blank app name, a non-string app and bad flags', async () => {
    const deps = { createSession: vi.fn().mockResolvedValue(grant), resolveVoice: async () => undefined };
    const bad = [
      { memory: 'م'.repeat(3001) }, { apps: Array.from({ length: 81 }, (_, i) => `a${i}`) }, { apps: ['x'.repeat(41)] },
      { apps: ['  '] }, { apps: [5] }, { apps: 'Maps' }, { memory: 5 }, { talk_first: 'yes' }, { voice_id: '' }, { voice_id: 'x'.repeat(65) }, { mode: 'other' },
    ];
    for (const extra of bad) expect((await handle(ask(extra), deps)).status, JSON.stringify(extra).slice(0, 40)).toBe(400);
    expect(deps.createSession).not.toHaveBeenCalled();
    expect((await handle(ask({ memory: 'م'.repeat(3000), apps: Array.from({ length: 80 }, (_, i) => `a${i}`), talk_first: true }), deps)).status).toBe(200);
  });

  it('requests without mode and with mode echo still build the probe session', async () => {
    const createSession = vi.fn().mockResolvedValue(grant);
    const deps = { createSession, resolveVoice: async () => undefined };
    const plain = await handle({ secret: 's3cret', body: { device_id: 'p3pro-1' } }, deps);
    await handle({ secret: 's3cret', body: { device_id: 'p3pro-1', mode: 'echo' } }, deps);
    expect(createSession.mock.calls[0][0].agent.prompt).toBe(PROMPT);
    expect(createSession.mock.calls[0][0].agent.client_tools).toBeUndefined();
    expect(createSession.mock.calls[1][0].agent.prompt).toBe(ECHO_PROMPT);
    expect('voice_id' in (plain.body as object)).toBe(false);
  });
});
