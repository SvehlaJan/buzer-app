const PEM_PATTERN = /-----BEGIN [A-Z0-9_ -]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z0-9_ -]*PRIVATE KEY-----|$)/g;

const AUTH_SCHEME_PATTERN = /\b(bearer|basic)\s+(?:(["'])(?!\[REDACTED\]\2)([^\r\n]*?)\2|(?!\[REDACTED\])[^\s,;"'\r\n]+)/gi;

const URL_CREDENTIALS_PATTERN = /([a-zA-Z][a-zA-Z0-9+.-]*:\/\/)([^@\/\s]+)@/g;

const SENSITIVE_QUERY_PARAM_PATTERN = /([?&](?:token|auth|key|secret|api_key|apikey|access_token|refresh_token|auth_token|sentry_auth_token|sentry_key|password|pwd)=)([^&#\s]+)/gi;

const COOKIE_HEADER_PATTERN = /\b(cookie|set-cookie|set_cookie)\s*:\s*(?!\[REDACTED\])[^\r\n]+/gi;

const QR_KEY_PATTERN = /\b(qr_code|qr_string|qr_content|whatsapp_session)\s*([:=])\s*(?!\[REDACTED\])[^\r\n]+/gi;

const KEY_VALUE_PATTERN = /(["']?)\b(password|passwd|pwd|secret|secret_token|secret_cookie|client_secret|app_secret|shared_secret|api_secret|api_token|access_token|refresh_token|auth_token|id_token|todoist_token|sentry_auth_token|sentry_token|devicetoken|device_token|session_token|session_key|credentials|credential|private_key|api_key|token|cookie|set-cookie|set_cookie)\1\s*([:=])\s*(?:(["'])(?!\[REDACTED\]\4)([^\r\n]*?)\4|(?!\[REDACTED\])([^\s,;"'\r\n]+))/gi;

const EXACT_CREDENTIAL_KEYS = new Set([
  "authorization",
  "proxyauthorization",
  "cookie",
  "setcookie",
  "password",
  "passwd",
  "pwd",
  "secret",
  "privatekey",
  "credentials",
  "credential",
  "apikey",
  "apitoken",
  "accesstoken",
  "refreshtoken",
  "authtoken",
  "idtoken",
  "token",
  "todoisttoken",
  "sentryauthtoken",
  "sentrytoken",
  "devicetoken",
  "clientsecret",
  "appsecret",
  "sharedsecret",
  "apisecret",
  "sessiontoken",
  "sessionkey",
  "whatsappsession",
  "qrcode",
  "qrcontent",
  "qrstring",
  "sessiondata"
]);

const NON_CREDENTIAL_KEYS = new Set([
  "reportsessionid",
  "occurrencekey",
  "deviceid",
  "taskid",
  "actionid",
  "requestid",
  "traceid",
  "schemaversion",
  "capsuleschemaversion"
]);

export function isCredentialKey(rawKey: string): boolean {
  const normalized = rawKey.toLowerCase().replace(/[-_]/g, "");
  if (NON_CREDENTIAL_KEYS.has(normalized)) return false;
  if (EXACT_CREDENTIAL_KEYS.has(normalized)) return true;
  if (
    normalized.endsWith("token") ||
    normalized.endsWith("secret") ||
    normalized.endsWith("password") ||
    normalized.endsWith("privatekey") ||
    normalized.endsWith("apikey") ||
    normalized.endsWith("cookie")
  ) {
    return true;
  }
  return false;
}

export function redactUrl(url: string): string {
  if (!url) return url;
  let clean = url.replace(URL_CREDENTIALS_PATTERN, (match, scheme, creds) => {
    if (creds === "[REDACTED]" || creds === "[REDACTED]:[REDACTED]") {
      return match;
    } else if (creds.includes(":")) {
      return `${scheme}[REDACTED]:[REDACTED]@`;
    } else {
      return `${scheme}[REDACTED]@`;
    }
  });

  clean = clean.replace(SENSITIVE_QUERY_PARAM_PATTERN, (_match, prefix) => {
    return `${prefix}[REDACTED]`;
  });

  return clean;
}

export function redactString(input: string): string {
  if (input.length === 0) return input;
  if (
    !input.includes(":") &&
    !input.includes("=") &&
    !input.includes("@") &&
    !input.includes("-----") &&
    !/bearer|basic/i.test(input)
  ) {
    return input;
  }

  let text = input;

  // 1. PEM private key blocks
  if (text.includes("-----")) {
    text = text.replace(PEM_PATTERN, "[REDACTED]");
  }

  // 2. Bearer and Basic authorization patterns
  if (/bearer|basic/i.test(text)) {
    text = text.replace(AUTH_SCHEME_PATTERN, (_match, scheme, quote) => {
      if (quote) {
        return `${scheme} ${quote}[REDACTED]${quote}`;
      }
      return `${scheme} [REDACTED]`;
    });
  }

  // 3. Credentials in URLs & sensitive query params
  if (text.includes("@") || text.includes("?")) {
    text = redactUrl(text);
  }

  // 4. Header-like cookie / set-cookie patterns
  if (/cookie|set-cookie|set_cookie/i.test(text)) {
    text = text.replace(COOKIE_HEADER_PATTERN, (_match, headerName) => {
      return `${headerName}: [REDACTED]`;
    });
  }

  // 5. QR / whatsapp_session patterns
  if (/qr|whatsapp/i.test(text)) {
    text = text.replace(QR_KEY_PATTERN, (_match, keyName, sep) => {
      if (sep.includes(":")) {
        return `${keyName}: [REDACTED]`;
      }
      return `${keyName}=[REDACTED]`;
    });
  }

  // 6. In-text key-value assignments
  if (text.includes(":") || text.includes("=")) {
    text = text.replace(
      KEY_VALUE_PATTERN,
      (match, keyQuote, key, delimiter, valQuote, quotedVal, unquotedVal) => {
        const value = valQuote ? quotedVal : unquotedVal;
        if (value === "[REDACTED]") {
          return match;
        }
        const q = valQuote ?? "";
        if (delimiter.includes(":")) {
          return `${keyQuote}${key}${keyQuote}: ${q}[REDACTED]${q}`;
        }
        return `${keyQuote}${key}${keyQuote}=${q}[REDACTED]${q}`;
      }
    );
  }

  return text;
}

export function redactAny(value: unknown): unknown {
  if (value === null || value === undefined) return value;
  if (typeof value === "string") return redactString(value);
  if (
    typeof value === "number" ||
    typeof value === "boolean" ||
    typeof value === "bigint" ||
    typeof value === "symbol"
  ) {
    return value;
  }
  if (value instanceof Error) {
    return redactString(value.message);
  }
  if (value instanceof Date) {
    return isNaN(value.getTime()) ? value.toString() : value.toISOString();
  }
  if (Array.isArray(value)) {
    return value.map((item) => redactAny(item));
  }
  if (typeof value === "object") {
    return redactObject(value as Record<string, unknown>);
  }
  return redactString(String(value));
}

export function redactObject(input: Record<string, unknown>): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  for (const [key, val] of Object.entries(input)) {
    if (isCredentialKey(key)) {
      result[key] = "[REDACTED]";
    } else {
      result[key] = redactAny(val);
    }
  }
  return result;
}
