import { request } from './request';

export interface LoginPayload {
  username: string;
  password: string;
  rememberMe?: boolean;
}

export interface LoginData {
  tokenType: string;
  accessToken: string;
  expiresIn: number;
  issuedAt: number;
  username: string;
  roles: string[];
  permissions: string[];
  rememberMe: boolean;
}

export interface MeData {
  username: string;
  roles: string[];
}

export const authApi = {
  login(body: LoginPayload): Promise<LoginData> {
    return request.post<LoginData>('/api/auth/login', {
      username: body.username,
      password: body.password,
      rememberMe: body.rememberMe === true,
    });
  },

  me(): Promise<MeData> {
    return request.get<MeData>('/api/auth/me');
  },
};
