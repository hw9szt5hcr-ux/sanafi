import { createHash, timingSafeEqual } from 'node:crypto';
import { MAX_APPS, MAX_APP_NAME, MAX_MEMORY_CHARS, buildAssistantRequest, type AssistantSessionRequest } from './assistant.js';
import { HumainError } from './humain.js';

export const PROMPT =
  'أنت "سنافي"، رفيق سواق سعودي. تتكلم باللهجة النجدية، خفيف دم ومختصر. هذي مكالمة اختبار: رد بجملة أو جملتين.';
export const INTRO = 'هلا والله، آمرني.';
export const ECHO_PROMPT =
  'أنت "سنافي". هذا اختبار صدى: بعد الافتتاحية لا تتكلم أبداً إلا إذا كلمك المستخدم بكلام واضح ومباشر.';
export const ECHO_INTRO =
  'هذا اختبار للصدى. بتكلم الحين ربع دقيقة تقريباً وانت لا ترد علي. واحد، اثنين، ثلاثة، أربعة، خمسة، ستة، سبعة، ثمانية، تسعة، عشرة. أحد عشر، اثنا عشر، ثلاثة عشر، أربعة عشر، خمسة عشر. خلصنا، مشكور.';
export const ALLOWED_VOICE_LABELS = ['mul_abdullah', 'mul_abdulaziz'] as const;
const MAX_IDENTITY_LENGTH = 128;
const MAX_VOICE_ID_LENGTH = 64;

export interface SessionRequest {
  participant: { identity: string; gender: 'male' };
  agent: { prompt: string; intro: string; voice_id?: string; web_search: boolean };
}

export interface SessionResponse {
  server_url: string;
  participant_token: string;
  session_id: string;
  expires_at: string;
  limits: { max_duration_s: number | null; idle_timeout_s: number | null };
  effective?: { voice_id?: string };
}

export interface Deps {
  createSession: (request: SessionRequest | AssistantSessionRequest) => Promise<SessionResponse>;
  resolveVoice: (label?: string) => Promise<string | undefined>;
}

export interface Reply {
  status: number;
  body: object;
}

/** The server itself is not set up (for example a missing environment variable). */
export class ConfigError extends Error {}

function digest(value: string): Buffer {
  return createHash('sha256').update(value).digest();
}

/** Constant-time comparison; an unset secret authorizes nobody. */
export function isAuthorized(headerValue: string | undefined, secret: string): boolean {
  if (!secret || headerValue === undefined) return false;
  return timingSafeEqual(digest(headerValue), digest(secret));
}

export function buildSessionRequest(input: { deviceId: string; voiceId?: string; mode?: 'echo' }): SessionRequest {
  const echo = input.mode === 'echo';
  const agent: SessionRequest['agent'] = { prompt: echo ? ECHO_PROMPT : PROMPT, intro: echo ? ECHO_INTRO : INTRO, web_search: false };
  if (input.voiceId) agent.voice_id = input.voiceId;
  return { participant: { identity: input.deviceId, gender: 'male' }, agent };
}

export function toErrorBody(err: unknown): { status: 502; body: { error: 'humain'; code: string } } {
  const code = (err as { code?: unknown } | null)?.code;
  return { status: 502, body: { error: 'humain', code: typeof code === 'string' && code ? code : 'UNKNOWN' } };
}

interface Ask {
  deviceId: string;
  voiceLabel?: string;
  mode?: 'echo' | 'assistant';
  voiceId?: string;
  memory?: string;
  apps?: string[];
  talkFirst?: boolean;
}

function parseBody(body: unknown): Ask | undefined {
  if (typeof body !== 'object' || body === null) return undefined;
  const { device_id: deviceId, voice_label: voiceLabel, mode, voice_id: voiceId, memory, apps, talk_first: talkFirst } = body as Record<string, unknown>;
  if (typeof deviceId !== 'string' || deviceId.length === 0 || deviceId.length > MAX_IDENTITY_LENGTH) return undefined;
  if (mode !== undefined && mode !== 'echo' && mode !== 'assistant') return undefined;
  if (voiceLabel !== undefined && (typeof voiceLabel !== 'string' || !(ALLOWED_VOICE_LABELS as readonly string[]).includes(voiceLabel))) return undefined;
  if (voiceId !== undefined && (typeof voiceId !== 'string' || voiceId.length === 0 || voiceId.length > MAX_VOICE_ID_LENGTH)) return undefined;
  if (memory !== undefined && (typeof memory !== 'string' || memory.length > MAX_MEMORY_CHARS)) return undefined;
  if (talkFirst !== undefined && typeof talkFirst !== 'boolean') return undefined;
  let appNames: string[] | undefined;
  if (apps !== undefined) {
    if (!Array.isArray(apps) || apps.length > MAX_APPS) return undefined;
    appNames = [];
    for (const app of apps) {
      if (typeof app !== 'string') return undefined;
      const name = app.trim();
      if (name.length === 0 || name.length > MAX_APP_NAME) return undefined;
      appNames.push(name);
    }
  }
  return {
    deviceId,
    voiceLabel: voiceLabel as string | undefined,
    mode: mode as Ask['mode'],
    voiceId: voiceId as string | undefined,
    memory: memory as string | undefined,
    apps: appNames,
    talkFirst: talkFirst as boolean | undefined,
  };
}

/** HUMAIN refused the voice id the device had cached: its id for that voice has changed. */
function isStaleVoice(err: unknown): boolean {
  return err instanceof HumainError && err.code === 'VALIDATION_INVALID_PARAM' && err.paths.includes('agent.voice_id');
}

export async function handle(req: { secret: string | undefined; body: unknown }, deps: Deps): Promise<Reply> {
  if (!isAuthorized(req.secret, process.env.DEVICE_SECRET ?? '')) {
    return { status: 401, body: { error: 'bad_secret' } };
  }
  const input = parseBody(req.body);
  if (!input) return { status: 400, body: { error: 'bad_request' } };

  const assistant = input.mode === 'assistant';
  const build = (voiceId?: string) =>
    assistant
      ? buildAssistantRequest({ deviceId: input.deviceId, voiceId, memory: input.memory, apps: input.apps, talkFirst: input.talkFirst })
      : buildSessionRequest({ deviceId: input.deviceId, voiceId, mode: input.mode === 'echo' ? 'echo' : undefined });

  try {
    // The app sends back the voice id of its last call, which saves a lookup on every call.
    const cached = assistant ? input.voiceId : undefined;
    let session: SessionResponse;
    try {
      session = await deps.createSession(build(cached ?? (await deps.resolveVoice(input.voiceLabel))));
    } catch (err) {
      if (!cached || !isStaleVoice(err)) throw err;
      session = await deps.createSession(build(await deps.resolveVoice(input.voiceLabel)));
    }
    const voiceId = assistant ? session.effective?.voice_id : undefined;
    return {
      status: 200,
      body: {
        server_url: session.server_url,
        participant_token: session.participant_token,
        session_id: session.session_id,
        expires_at: session.expires_at,
        limits: session.limits,
        ...(voiceId ? { voice_id: voiceId } : {}),
      },
    };
  } catch (err) {
    if (err instanceof ConfigError) throw err;
    return toErrorBody(err);
  }
}
