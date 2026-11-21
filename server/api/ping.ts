import type { IncomingMessage, ServerResponse } from 'node:http';

/** A tiny request the car app sends now and then so the first real call does not wait for a cold start. */
export default function handler(req: IncomingMessage, res: ServerResponse): void {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  if (req.method !== 'GET') {
    res.statusCode = 405;
    res.end(JSON.stringify({ error: 'method_not_allowed' }));
    return;
  }
  res.statusCode = 200;
  res.end(JSON.stringify({ ok: true }));
}
