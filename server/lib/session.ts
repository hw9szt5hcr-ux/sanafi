import { createHash, timingSafeEqual } from 'node:crypto';

export const PROMPT =
  'أنت "سنافي"، رفيق سواق سعودي. تتكلم باللهجة النجدية، خفيف دم ومختصر. هذي مكالمة اختبار: رد بجملة أو جملتين.';
export const INTRO = 'هلا والله، آمرني.';
export const ALLOWED_VOICE_LABELS = ['mul_abdullah', 'mul_abdulaziz'] as const;
const MAX_IDENTITY_LENGTH = 128;

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
}

export interface Deps {
  createSession: (request: SessionRequest) => Promise<SessionResponse>;
  resolveVoice: (label?: string) => Promise<string | undefined>;
}

export interface Reply {
  status: number;
  body: object;
}

function digest(value: string): Buffer {
  return createHash('sha256').update(value).digest();
}

/** Constant-time comparison; an unset secret authorizes nobody. */
export function isAuthorized(headerValue: string | undefined, secret: string): boolean {
  if (!secret || headerValue === undefined) return false;
  return timingSafeEqual(digest(headerValue), digest(secret));
}

export function buildSessionRequest(input: { deviceId: string; voiceId?: string }): SessionRequest {
  const agent: SessionRequest['agent'] = { prompt: PROMPT, intro: INTRO, web_search: false };
  if (input.voiceId) agent.voice_id = input.voiceId;
  return { participant: { identity: input.deviceId, gender: 'male' }, agent };
}

export function toErrorBody(err: unknown): { status: 502; body: { error: 'humain'; code: string } } {
  const code = (err as { code?: unknown } | null)?.code;
  return { status: 502, body: { error: 'humain', code: typeof code === 'string' && code ? code : 'UNKNOWN' } };
}

function parseBody(body: unknown): { deviceId: string; voiceLabel?: string } | undefined {
  if (typeof body !== 'object' || body === null) return undefined;
  const { device_id: deviceId, voice_label: voiceLabel } = body as Record<string, unknown>;
  if (typeof deviceId !== 'string' || deviceId.length === 0 || deviceId.length > MAX_IDENTITY_LENGTH) return undefined;
  if (voiceLabel === undefined) return { deviceId };
  if (typeof voiceLabel !== 'string' || !(ALLOWED_VOICE_LABELS as readonly string[]).includes(voiceLabel)) return undefined;
  return { deviceId, voiceLabel };
}

export async function handle(req: { secret: string | undefined; body: unknown }, deps: Deps): Promise<Reply> {
  if (!isAuthorized(req.secret, process.env.DEVICE_SECRET ?? '')) {
    return { status: 401, body: { error: 'bad_secret' } };
  }
  const input = parseBody(req.body);
  if (!input) return { status: 400, body: { error: 'bad_request' } };

  try {
    const voiceId = await deps.resolveVoice(input.voiceLabel);
    const session = await deps.createSession(buildSessionRequest({ deviceId: input.deviceId, voiceId }));
    return {
      status: 200,
      body: {
        server_url: session.server_url,
        participant_token: session.participant_token,
        session_id: session.session_id,
        expires_at: session.expires_at,
        limits: session.limits,
      },
    };
  } catch (err) {
    return toErrorBody(err);
  }
}
