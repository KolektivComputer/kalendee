interface Env {
  BUCKET: R2Bucket;
  READ_TOKEN?: string;
}

const BEARER_PATTERN = /^Bearer\s+(.+)$/i;
const UUID_PATTERN =
  /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const HASH_PATTERN = /(?:^|[._/-])[0-9a-f]{16,}(?:[._/-]|$)/i;

const MIME_BY_EXTENSION: Record<string, string> = {
  avif: "image/avif",
  gif: "image/gif",
  ico: "image/x-icon",
  jpeg: "image/jpeg",
  jpg: "image/jpeg",
  png: "image/png",
  svg: "image/svg+xml",
  webp: "image/webp",
  pdf: "application/pdf",
};

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

function isAuthorized(request: Request, expected: string): boolean {
  const queryToken = new URL(request.url).searchParams.get("token");
  if (queryToken !== null && timingSafeEqual(queryToken, expected)) {
    return true;
  }
  const headerToken = bearerToken(request.headers.get("Authorization"));
  return headerToken !== null && timingSafeEqual(headerToken, expected);
}

function normalizeKey(rawKey: string): string | null {
  if (rawKey.includes("..") || rawKey.startsWith("/")) {
    return null;
  }
  let decoded: string;
  try {
    decoded = decodeURIComponent(rawKey);
  } catch {
    return null;
  }
  if (
    decoded.length === 0 ||
    decoded.includes("..") ||
    decoded.startsWith("/")
  ) {
    return null;
  }
  return decoded;
}

function contentTypeFor(object: R2Object, key: string): string {
  const declared = object.httpMetadata?.contentType;
  if (typeof declared === "string" && declared.length > 0) {
    return declared;
  }
  const dot = key.lastIndexOf(".");
  if (dot !== -1) {
    const inferred = MIME_BY_EXTENSION[key.slice(dot + 1).toLowerCase()];
    if (inferred !== undefined) {
      return inferred;
    }
  }
  return "application/octet-stream";
}

function isImmutableKey(key: string): boolean {
  return UUID_PATTERN.test(key) || HASH_PATTERN.test(key);
}

function etagMatches(ifNoneMatch: string, etag: string): boolean {
  const target = etag.replace(/^W\//, "");
  return ifNoneMatch
    .split(",")
    .map((part) => part.trim().replace(/^W\//, ""))
    .some((part) => part === "*" || part === target);
}

function responseHeaders(object: R2Object, key: string): Headers {
  const headers = new Headers();
  const contentType = contentTypeFor(object, key);
  headers.set("ETag", object.httpEtag);
  headers.set("Content-Type", contentType);
  headers.set(
    "Cache-Control",
    isImmutableKey(key)
      ? "public, max-age=31536000, immutable"
      : "public, max-age=86400",
  );
  if (contentType.startsWith("image/")) {
    headers.set("Access-Control-Allow-Origin", "*");
  }
  return headers;
}

function notFound(): Response {
  return new Response("Not Found", { status: 404 });
}

function badRequest(message: string): Response {
  return new Response(message, { status: 400 });
}

function forbidden(): Response {
  return new Response("Forbidden", { status: 403 });
}

function methodNotAllowed(): Response {
  return new Response("Method Not Allowed", {
    status: 405,
    headers: { Allow: "GET, HEAD" },
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "GET" && request.method !== "HEAD") {
      return methodNotAllowed();
    }

    const url = new URL(request.url);
    const rawKey = url.pathname.startsWith("/")
      ? url.pathname.slice(1)
      : url.pathname;
    if (rawKey.length === 0) {
      return notFound();
    }
    const key = normalizeKey(rawKey);
    if (key === null) {
      return badRequest("invalid object key");
    }

    const readToken = env.READ_TOKEN;
    if (
      readToken !== undefined &&
      readToken.length > 0 &&
      !isAuthorized(request, readToken)
    ) {
      return forbidden();
    }

    const ifNoneMatch = request.headers.get("If-None-Match");

    if (request.method === "HEAD") {
      const object = await env.BUCKET.head(key);
      if (object === null) {
        return notFound();
      }
      const headers = responseHeaders(object, key);
      if (ifNoneMatch !== null && etagMatches(ifNoneMatch, object.httpEtag)) {
        return new Response(null, { status: 304, headers });
      }
      return new Response(null, { status: 200, headers });
    }

    const object = await env.BUCKET.get(key);
    if (object === null) {
      return notFound();
    }
    const headers = responseHeaders(object, key);
    if (ifNoneMatch !== null && etagMatches(ifNoneMatch, object.httpEtag)) {
      return new Response(null, { status: 304, headers });
    }
    return new Response(object.body, { status: 200, headers });
  },
} satisfies ExportedHandler<Env>;
