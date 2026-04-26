import axios, {
  AxiosError,
  AxiosInstance,
  AxiosRequestConfig,
  InternalAxiosRequestConfig,
} from 'axios';
import { clearAuthSession, getAccessToken, getRefreshToken, setAccessToken, setRefreshToken } from '../auth/storage';

/**
 * 后端统一响应结构
 */
interface Result<T = unknown> {
  code: number;
  message: string;
  data: T;
}

// 开发环境走 Vite 代理（/api -> http://localhost:8080），避免 CORS
const baseURL = '';

const instance: AxiosInstance = axios.create({
  baseURL,
  timeout: 60000,
});

let isRedirectingTo401 = false;
let refreshPromise: Promise<void> | null = null;

function hasBearerAuthHeader(config: AxiosRequestConfig | undefined): boolean {
  const headers = config?.headers as Record<string, unknown> | undefined;
  const auth = headers?.Authorization ?? headers?.authorization;
  return typeof auth === 'string' && auth.startsWith('Bearer ');
}

function isAuthExcludedPath(url: string): boolean {
  return (
    url.includes('/api/auth/login') ||
    url.includes('/api/auth/refresh') ||
    url.includes('/api/auth/logout')
  );
}

function setBearerHeader(config: AxiosRequestConfig, token: string) {
  // Axios v1 的 headers 类型比较严格，这里按运行时合并即可
  config.headers = {
    ...(config.headers as unknown as Record<string, unknown> | undefined),
    Authorization: `Bearer ${token}`,
  } as unknown as AxiosRequestConfig['headers'];
}

function handleUnauthorizedRedirect() {
  if (isRedirectingTo401) return;
  isRedirectingTo401 = true;
  clearAuthSession();
  if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) {
    const next = `${window.location.pathname}${window.location.search}`;
    window.location.replace(`/login?redirect=${encodeURIComponent(next)}`);
  }
}

instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

async function refreshTokensOrThrow(): Promise<void> {
  if (refreshPromise) {
    return refreshPromise;
  }
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    throw new Error('缺少 refresh token');
  }

  refreshPromise = (async () => {
    const data = await request.post<{
      accessToken: string;
      refreshToken: string;
    }>('/api/auth/refresh', { refreshToken });
    if (!data?.accessToken || !data.accessToken.trim() || !data?.refreshToken || !data.refreshToken.trim()) {
      throw new Error('刷新登录态失败：服务端未返回有效 token');
    }
    setAccessToken(data.accessToken);
    setRefreshToken(data.refreshToken);
  })().finally(() => {
    refreshPromise = null;
  });

  return refreshPromise;
}

/**
 * 响应拦截器
 * 
 * 后端约定：所有响应都是 HTTP 200 + Result
 * - code === 200 → 成功，返回 data
 * - code !== 200 → 失败，直接显示 message
 */
instance.interceptors.response.use(
  (response) => {
    const result = response.data as Result;
    
    // 检查是否是 Result 格式
    if (result && typeof result === 'object' && 'code' in result) {
      if (result.code === 200) {
        // 成功：返回 data
        response.data = result.data;
        return response;
      }
      // 失败：直接抛出 message
      return Promise.reject(new Error(result.message || '请求失败'));
    }
    
    // 非 Result 格式，直接返回
    return response;
  },
  async (error: AxiosError) => {
    const status = error.response?.status;
    const reqUrl = String(error.config?.url ?? '');
    const config = error.config as (AxiosRequestConfig & { _retry?: boolean }) | undefined;

    // access token 失效：先 refresh 再重试一次原请求；失败再清理登录态
    if (
      status === 401 &&
      config &&
      !config._retry &&
      !isAuthExcludedPath(reqUrl) &&
      hasBearerAuthHeader(config)
    ) {
      config._retry = true;
      try {
        await refreshTokensOrThrow();
        const newAccessToken = getAccessToken();
        if (newAccessToken) {
          setBearerHeader(config, newAccessToken);
        }
        return instance.request(config);
      } catch {
        handleUnauthorizedRedirect();
        return Promise.reject(new Error('登录已过期，请重新登录'));
      }
    }

    // 有响应的情况：后端返回了结果（即使是错误）
    if (error.response) {
      const { data } = error.response;
      // 尝试解析 Result 格式
      if (data && typeof data === 'object' && 'code' in data && 'message' in data) {
        const result = data as Result;
        return Promise.reject(new Error(result.message || '请求失败'));
      }
      // 响应格式不对
      return Promise.reject(new Error('请求失败，请重试'));
    }

    // 没有响应的情况：真正的网络错误或连接被重置
    const configForNetErr = error.config;
    const isUpload = configForNetErr && (
      configForNetErr.url?.includes('/upload') ||
      (configForNetErr.headers as Record<string, unknown> | undefined)?.['Content-Type']?.toString().includes('multipart')
    );

    if (isUpload) {
      return Promise.reject(new Error('上传失败，可能是网络超时或连接中断，请重试'));
    }

    return Promise.reject(new Error('网络连接失败，请检查网络'));
  }
);

export const request = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config).then(res => res.data);
  },

  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, data, config).then(res => res.data);
  },

  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.put(url, data, config).then(res => res.data);
  },

  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.delete(url, config).then(res => res.data);
  },

  /**
   * 文件上传
   */
  upload<T>(url: string, formData: FormData, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, formData, {
      timeout: 120000,
      ...config,
    }).then(res => res.data);
  },

  /**
   * 获取原始实例（用于特殊场景如下载 Blob）
   */
  getInstance(): AxiosInstance {
    return instance;
  },
};

/**
 * 获取错误信息
 */
export function getErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return '未知错误';
}

export default request;
