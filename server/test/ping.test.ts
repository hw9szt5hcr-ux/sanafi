import { describe, expect, it } from 'vitest';
import handler from '../api/ping.js';

function run(method: string) {
  const headers: Record<string, string> = {};
  const res = { statusCode: 0, body: '', setHeader: (k: string, v: string) => { headers[k] = v; }, end(text: string) { this.body = text; } };
  handler({ method } as never, res as never);
  return { status: res.statusCode, body: res.body, headers };
}

describe('ping', () => {
  it('answers GET with ok and is never cached', () => {
    const r = run('GET');
    expect(r.status).toBe(200);
    expect(JSON.parse(r.body)).toEqual({ ok: true });
    expect(r.headers['Cache-Control']).toBe('no-store');
  });
  it('refuses other methods', () => {
    expect(run('POST').status).toBe(405);
  });
});
