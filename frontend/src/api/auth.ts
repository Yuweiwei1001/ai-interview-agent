import request from '../utils/request';

export interface LoginVO {
  accessToken: string;
  refreshToken: string;
  userId: number;
  username: string;
}

export function register(data: { username: string; password: string; email?: string }) {
  return request.post<{ code: number; msg: string; data: LoginVO }>('/auth/register', data);
}

export function login(data: { username: string; password: string }) {
  return request.post<{ code: number; msg: string; data: LoginVO }>('/auth/login', data);
}

export function refreshToken() {
  return request.post<{ code: number; msg: string; data: LoginVO }>('/auth/refresh');
}
