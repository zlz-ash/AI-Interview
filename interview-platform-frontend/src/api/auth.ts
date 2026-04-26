import { request } from './request';

export interface LoginPayload {
  username: string;
  password: string;
  rememberMe?: boolean;
}

export interface LoginData {
  tokenType: string;
  accessToken: string;
  accessExpiresIn: number;
  refreshToken: string;
  refreshExpiresIn: number;
  issuedAt: number;
  username: string;
  roles: string[];
  permissions: string[];
}

export interface MeData {
  username: string;
  roles: string[];
}

export interface RefreshTokenRequest {
  refreshToken: string;
}

export interface LogoutRequest {
  refreshToken: string;
}

export const authApi = {
  login(body: LoginPayload): Promise<LoginData> {
    return request.post<LoginData>('/api/auth/login', {
      username: body.username,
      password: body.password,
      rememberMe: body.rememberMe === true,
    });
  },

  refresh(body: RefreshTokenRequest): Promise<LoginData> {
    return request.post<LoginData>('/api/auth/refresh', body);
  },

  logout(body: LogoutRequest): Promise<void> {
    return request.post<void>('/api/auth/logout', body);
  },

  me(): Promise<MeData> {
    return request.get<MeData>('/api/auth/me');
  },
};
