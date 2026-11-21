import type { AssistantSessionRequest } from './assistant.js';
import type { SessionRequest, SessionResponse } from './session.js';

export class HumainError extends Error {
  /** [paths] are the request fields HUMAIN rejected, when it named them (for example "agent.voice_id"). */
  constructor(public readonly code: string, public readonly status?: number, public readonly paths: string[] = []) {
    super(`HUMAIN request failed: ${code}`);
    this.name = 'HumainError';
  }
}

type FetchLike = (url: string, init: RequestInit) => Promise<Response>;

function codeFrom(text: string): string | undefined {
  try {
    const json = JSON.parse(text) as { code?: unknown; error?: { code?: unknown } };
    const code = json?.code ?? json?.error?.code;
    return typeof code === 'string' && code ? code : undefined;
  } catch {
    return undefined;
  }
}

function pathsFrom(text: string): string[] {
  try {
    const errors = (JSON.parse(text) as { data?: { errors?: unknown } })?.data?.errors;
    if (!Array.isArray(errors)) return [];
    return errors.map((e) => (e as { path?: unknown })?.path).filter((path): path is string => typeof path === 'string');
  } catch {
    return [];
  }
}

/** Starts a Voice Agent session over HUMAIN's documented REST endpoint. */
export function makeCreateSession(apiUrl: string, apiKey: string, fetchImpl: FetchLike = fetch) {
  const endpoint = `${apiUrl.trim().replace(/\/+$/, '')}/agent/sessions`;
  const key = apiKey.trim();
  return async (request: SessionRequest | AssistantSessionRequest): Promise<SessionResponse> => {
    // A key can only be sent as a header when it is plain printable ASCII with no spaces.
    if (!/^[\x21-\x7e]+$/.test(key)) throw new HumainError('API_KEY_INVALID_CHARS');
    let response: Response;
    try {
      response = await fetchImpl(endpoint, {
        method: 'POST',
        headers: { 'x-api-key': key, 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      });
    } catch {
      throw new HumainError('NETWORK');
    }
    const text = await response.text();
    if (!response.ok) throw new HumainError(codeFrom(text) ?? `HTTP_${response.status}`, response.status, pathsFrom(text));
    return JSON.parse(text) as SessionResponse;
  };
}
