import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { Eye, EyeOff, Loader2, Lock, Sparkles, User } from 'lucide-react';
import { authApi } from '../api/auth';
import { getErrorMessage } from '../api/request';
import { getAccessToken, setAccessToken, setStoredUsername, clearAuthSession } from '../auth/storage';

function resolveRedirectPath(fromState: unknown, search: string): string {
  if (typeof fromState === 'string' && fromState.startsWith('/')) {
    return fromState;
  }
  const q = new URLSearchParams(search).get('redirect');
  if (q && q.startsWith('/')) {
    return q;
  }
  return '/upload';
}

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const fromState = (location.state as { from?: string } | null)?.from;

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [rememberMe, setRememberMe] = useState(true);
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (getAccessToken()) {
      navigate(resolveRedirectPath(fromState, location.search), { replace: true });
    }
  }, [navigate, fromState, location.search]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const data = await authApi.login({ username: username.trim(), password, rememberMe });
      if (!data?.accessToken || !data.accessToken.trim()) {
        throw new Error('登录成功但未收到有效 token，请联系管理员检查服务端返回');
      }
      setAccessToken(data.accessToken);
      if (!getAccessToken()) {
        throw new Error('登录态保存失败，请重试');
      }
      setStoredUsername(data.username);
      navigate(resolveRedirectPath(fromState, location.search), { replace: true });
    } catch (err) {
      clearAuthSession();
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="relative flex min-h-screen overflow-hidden bg-[#fffdf7] dark:bg-stone-950">
      <div
        className="pointer-events-none absolute inset-0 opacity-[0.035] dark:opacity-[0.06]"
        style={{
          backgroundImage: `url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.8' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E")`,
        }}
        aria-hidden
      />

      <motion.aside
        className="relative hidden w-[42%] flex-col justify-between border-r border-emerald-100/70 bg-gradient-to-br from-[#fffbeb] via-[#ecfdf5] to-[#f7fee7] px-10 py-12 text-stone-800 lg:flex dark:border-emerald-900/35 dark:from-stone-900 dark:via-emerald-950/80 dark:to-stone-950 dark:text-stone-100"
        initial={{ x: -24, opacity: 0 }}
        animate={{ x: 0, opacity: 1 }}
        transition={{ duration: 0.55, ease: [0.22, 1, 0.36, 1] }}
      >
        <div
          className="pointer-events-none absolute -right-20 top-1/3 h-64 w-64 rounded-full bg-lime-300/25 blur-3xl dark:bg-primary-600/15"
          aria-hidden
        />
        <div
          className="pointer-events-none absolute -left-10 bottom-1/4 h-48 w-48 rounded-full bg-primary-300/20 blur-3xl dark:bg-primary-800/20"
          aria-hidden
        />
        <div>
          <div className="mb-10 flex items-center gap-3">
            <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-white/80 text-primary-700 shadow-sm ring-1 ring-primary-200/60 dark:bg-white/10 dark:text-primary-300 dark:ring-white/15">
              <Sparkles className="h-5 w-5" />
            </div>
            <div>
              <p className="text-[0.65rem] font-semibold uppercase tracking-[0.28em] text-primary-700/90 dark:text-primary-400">
                Spring AI
              </p>
              <p className="font-display text-2xl font-semibold tracking-tight">试炼台</p>
            </div>
          </div>
          <h1 className="font-display max-w-md text-4xl font-semibold leading-[1.15] tracking-tight">
            浅黄白纸上一笔
            <span className="text-primary-600 dark:text-primary-400">新绿</span>，串起简历与面试流。
          </h1>
          <p className="mt-6 max-w-sm text-sm leading-relaxed text-stone-600 dark:text-stone-400">
            左侧是春典色调的留白叙事，右侧只留给账号与密码——像翻开笔记本的第一页。
          </p>
        </div>
        <p className="text-xs text-stone-500 dark:text-stone-500">仅供授权用户使用 · 会话由服务端 JWT 校验</p>
      </motion.aside>

      <div className="relative flex flex-1 items-center justify-center bg-gradient-to-bl from-transparent via-white/40 to-[#ecfdf5]/30 px-6 py-14 dark:from-transparent dark:via-stone-950/50 dark:to-emerald-950/20">
        <motion.div
          className="w-full max-w-[420px]"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.08, duration: 0.45 }}
        >
          <div className="mb-8 lg:hidden">
            <p className="ui-kicker mb-2">试炼台</p>
            <h1 className="font-display text-3xl font-semibold text-stone-900 dark:text-stone-50">登录</h1>
          </div>

          <div className="mb-10 hidden lg:block">
            <p className="ui-kicker mb-2">身份验证</p>
            <h1 className="font-display text-3xl font-semibold text-stone-900 dark:text-stone-50">欢迎回来</h1>
            <p className="mt-2 text-sm text-stone-600 dark:text-stone-400">输入账号密码以继续</p>
          </div>

          <form
            onSubmit={handleSubmit}
            className="ui-glow-ring space-y-5 rounded-[1.5rem] border border-primary-200/35 bg-white/90 p-8 shadow-sm backdrop-blur-md dark:border-primary-900/40 dark:bg-stone-900/85"
          >
            <label className="block">
              <span className="mb-2 block text-xs font-semibold uppercase tracking-wider text-stone-500 dark:text-stone-400">
                用户名
              </span>
              <div className="relative">
                <User className="absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-stone-400" />
                <input
                  type="text"
                  autoComplete="username"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  className="dark-input w-full rounded-xl py-3 pl-10 pr-4 text-stone-900 focus:border-primary-500 focus:outline-none focus:ring-2 focus:ring-primary-500/25 dark:text-stone-100"
                  placeholder="用户名"
                  required
                />
              </div>
            </label>

            <label className="block">
              <span className="mb-2 block text-xs font-semibold uppercase tracking-wider text-stone-500 dark:text-stone-400">
                密码
              </span>
              <div className="relative">
                <Lock className="absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-stone-400" />
                <input
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="dark-input w-full rounded-xl py-3 pl-10 pr-12 text-stone-900 focus:border-primary-500 focus:outline-none focus:ring-2 focus:ring-primary-500/25 dark:text-stone-100"
                  placeholder="密码"
                  required
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((v) => !v)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 rounded-lg p-1 text-stone-400 hover:bg-stone-100 hover:text-stone-600 dark:hover:bg-stone-800 dark:hover:text-stone-300"
                  aria-label={showPassword ? '隐藏密码' : '显示密码'}
                >
                  {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </label>

            <label className="flex cursor-pointer items-center gap-3 select-none">
              <input
                type="checkbox"
                checked={rememberMe}
                onChange={(e) => setRememberMe(e.target.checked)}
                className="h-4 w-4 rounded border-stone-300 text-primary-600 focus:ring-primary-500 dark:border-stone-600"
              />
              <span className="text-sm text-stone-600 dark:text-stone-400">在此设备上保持更长时间登录</span>
            </label>

            {error && (
              <motion.p
                initial={{ opacity: 0, y: -6 }}
                animate={{ opacity: 1, y: 0 }}
                className="rounded-xl border border-red-200/80 bg-red-50/95 px-4 py-3 text-center text-sm text-red-700 dark:border-red-900/50 dark:bg-red-950/40 dark:text-red-300"
              >
                {error}
              </motion.p>
            )}

            <motion.button
              type="submit"
              disabled={loading}
              className="flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-primary-500 to-primary-700 py-3.5 text-sm font-semibold text-white shadow-lg shadow-primary-600/25 transition hover:shadow-primary-600/38 disabled:cursor-not-allowed disabled:opacity-60"
              whileHover={{ scale: loading ? 1 : 1.01 }}
              whileTap={{ scale: loading ? 1 : 0.99 }}
            >
              {loading ? (
                <>
                  <Loader2 className="h-5 w-5 animate-spin" />
                  登录中…
                </>
              ) : (
                '进入工作台'
              )}
            </motion.button>
          </form>

          <p className="mt-8 text-center text-xs text-stone-500 dark:text-stone-500">
            登录遇到问题？请确认后端已启动且账号已在服务端配置。
          </p>
        </motion.div>
      </div>
    </div>
  );
}
