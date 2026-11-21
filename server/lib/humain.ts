import type { SessionRequest, SessionResponse } from './session.js';

export class HumainError extends Error {
  constructor(public readonly code: string, public readonly status?: number) {
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

/** Starts a Voice Agent session over HUMAIN's documented REST endpoint. */
export function makeCreateSession(apiUrl: string, apiKey: string, fetchImpl: FetchLike = fetch) {
  const endpoint = `${apiUrl.replace(/\/+$/, '')}/agent/sessions`;
  return async (request: SessionRequest): Promise<SessionResponse> => {
    let response: Response;
    try {
      response = await fetchImpl(endpoint, {
        method: 'POST',
        headers: { 'x-api-key': apiKey, 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      });
    } catch {
      throw new HumainError('NETWORK');
    }
    const text = await response.text();
    if (!response.ok) throw new HumainError(codeFrom(text) ?? `HTTP_${response.status}`, response.status);
    return JSON.parse(text) as SessionResponse;
  };
}
