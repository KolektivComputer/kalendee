interface Env {
  MAILER: SendEmail;
  MAILER_TOKEN?: string;
}

interface MailRequest {
  from: string;
  to: string;
  subject: string;
  text: string;
  html?: string;
}

const JSON_HEADERS = { "Content-Type": "application/json; charset=utf-8" };
const BEARER_PATTERN = /^Bearer\s+(.+)$/i;

function jsonError(error: string, status: number): Response {
  return new Response(JSON.stringify({ error }), {
    status,
    headers: JSON_HEADERS,
  });
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) {
    return false;
  }
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

function bearerToken(header: string | null): string | null {
  if (header === null) {
    return null;
  }
  const match = BEARER_PATTERN.exec(header.trim());
  return match === null ? null : match[1];
}

function parseMailRequest(value: unknown): MailRequest | null {
  if (typeof value !== "object" || value === null) {
    return null;
  }
  const body = value as Record<string, unknown>;
  const from = body.from;
  const to = body.to;
  const subject = body.subject;
  const text = body.text;
  if (
    typeof from !== "string" ||
    from.length === 0 ||
    typeof to !== "string" ||
    to.length === 0 ||
    typeof subject !== "string" ||
    typeof text !== "string"
  ) {
    return null;
  }
  const request: MailRequest = { from, to, subject, text };
  if (typeof body.html === "string" && body.html.length > 0) {
    request.html = body.html;
  }
  return request;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "POST") {
      return new Response("Method Not Allowed", {
        status: 405,
        headers: { Allow: "POST" },
      });
    }

    const expected = env.MAILER_TOKEN;
    const provided = bearerToken(request.headers.get("Authorization"));
    if (
      expected === undefined ||
      expected.length === 0 ||
      provided === null ||
      !timingSafeEqual(provided, expected)
    ) {
      if (expected === undefined || expected.length === 0) {
        console.error("MAILER_TOKEN is not configured");
      }
      return jsonError("unauthorized", 401);
    }

    let payload: unknown;
    try {
      payload = await request.json();
    } catch {
      return jsonError("invalid JSON body", 400);
    }

    const mail = parseMailRequest(payload);
    if (mail === null) {
      return jsonError("missing or invalid required field", 400);
    }

    try {
      await env.MAILER.send(mail);
      return new Response(null, { status: 204 });
    } catch (error) {
      console.error("send_email binding failed", error);
      return jsonError("failed to send email", 502);
    }
  },
} satisfies ExportedHandler<Env>;
