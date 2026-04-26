const ACCESS_TOKEN_KEY = 'interview_platform_access_token';
const REFRESH_TOKEN_KEY = 'interview_platform_refresh_token';
const USERNAME_KEY = 'interview_platform_username';

function decodeJwtPayload(token: string): Record<string, unknown> | null {
  const segments = token.split('.');
  if (segments.length !== 3) {
    return null;
  }
  try {
    const normalized = segments[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = normalized + '='.repeat((4 - (normalized.length % 4)) % 4);
    const json = atob(padded);
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function isTokenExpired(token: string): boolean {
  const payload = decodeJwtPayload(token);
  const exp = payload?.exp;
  if (typeof exp !== 'number') {
    // 没有 exp 时交给服务端判断，不在前端误杀
    return false;
  }
  const now = Math.floor(Date.now() / 1000);
  return exp <= now;
}

export function getAccessToken(): string | null {
  const raw = localStorage.getItem(ACCESS_TOKEN_KEY);
  if (!raw) {
    return null;
  }
  const token = raw.trim();
  if (!token || token.split('.').length !== 3 || isTokenExpired(token)) {
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    return null;
  }
  return token;
}

export function setAccessToken(token: string): void {
  const normalized = token?.trim?.() ?? '';
  if (!normalized || normalized.split('.').length !== 3) {
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    return;
  }
  localStorage.setItem(ACCESS_TOKEN_KEY, normalized);
}

export function clearAccessToken(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
}

export function getRefreshToken(): string | null {
  const raw = localStorage.getItem(REFRESH_TOKEN_KEY);
  if (!raw) {
    return null;
  }
  const token = raw.trim();
  if (!token) {
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    return null;
  }
  // refresh token 的格式/过期交给服务端判断，前端不做结构校验，避免误删
  return token;
}

export function setRefreshToken(token: string): void {
  const normalized = token?.trim?.() ?? '';
  if (!normalized) {
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    return;
  }
  localStorage.setItem(REFRESH_TOKEN_KEY, normalized);
}

export function clearRefreshToken(): void {
  localStorage.removeItem(REFRESH_TOKEN_KEY);
}

export function getStoredUsername(): string | null {
  return localStorage.getItem(USERNAME_KEY);
}

export function setStoredUsername(username: string): void {
  localStorage.setItem(USERNAME_KEY, username);
}

export function clearStoredUsername(): void {
  localStorage.removeItem(USERNAME_KEY);
}

export function clearAuthSession(): void {
  clearAccessToken();
  clearRefreshToken();
  clearStoredUsername();
}
