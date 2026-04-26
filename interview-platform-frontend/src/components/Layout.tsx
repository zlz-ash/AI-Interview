import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import {
  ChevronRight,
  Database,
  FileStack,
  LogOut,
  MessageSquare,
  Moon,
  Sparkles,
  Sun,
  Upload,
  Users,
} from 'lucide-react';
import { useTheme } from '../hooks/useTheme';
import { authApi } from '../api/auth';
import { clearAuthSession, getRefreshToken, getStoredUsername } from '../auth/storage';

interface NavItem {
  id: string;
  path: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  description?: string;
}

interface NavGroup {
  id: string;
  title: string;
  items: NavItem[];
}

export default function Layout() {
  const location = useLocation();
  const navigate = useNavigate();
  const currentPath = location.pathname;
  const { theme, toggleTheme } = useTheme();
  const displayName = getStoredUsername() ?? '已登录';

  const navGroups: NavGroup[] = [
    {
      id: 'career',
      title: '简历与面试',
      items: [
        { id: 'upload', path: '/upload', label: '上传简历', icon: Upload, description: 'AI 分析简历' },
        { id: 'resumes', path: '/history', label: '简历库', icon: FileStack, description: '管理所有简历' },
        { id: 'interviews', path: '/interviews', label: '面试记录', icon: Users, description: '查看面试历史' },
      ],
    },
    {
      id: 'knowledge',
      title: '知识库',
      items: [
        { id: 'kb-manage', path: '/knowledgebase', label: '知识库管理', icon: Database, description: '管理知识文档' },
        { id: 'chat', path: '/knowledgebase/chat', label: '问答助手', icon: MessageSquare, description: '基于知识库问答' },
      ],
    },
  ];

  const isActive = (path: string) => {
    if (path === '/upload') {
      return currentPath === '/upload' || currentPath === '/';
    }
    if (path === '/knowledgebase') {
      return currentPath === '/knowledgebase' || currentPath === '/knowledgebase/upload';
    }
    return currentPath.startsWith(path);
  };

  return (
    <div className="relative flex min-h-screen">
      {/* 侧栏左侧强调条 */}
      <div
        className="pointer-events-none fixed left-0 top-0 z-[60] h-full w-1 bg-gradient-to-b from-primary-500 via-lime-400 to-primary-700 opacity-[0.92]"
        aria-hidden
      />

      <aside className="fixed left-0 top-0 z-50 flex h-screen w-[17.5rem] flex-col border-r border-emerald-100/80 bg-[#fffdf9]/82 shadow-[4px_0_36px_-10px_rgba(49,111,82,0.12)] backdrop-blur-xl dark:border-emerald-900/30 dark:bg-stone-950/72 dark:shadow-[4px_0_48px_-8px_rgba(0,0,0,0.45)]">
        <div className="relative overflow-hidden border-b border-emerald-100/70 px-5 pb-5 pt-7 dark:border-stone-800/80">
          <div
            className="pointer-events-none absolute -right-8 -top-10 h-32 w-32 rounded-full bg-primary-500/15 blur-2xl dark:bg-primary-400/10"
            aria-hidden
          />
          <Link to="/upload" className="relative flex items-start gap-3.5">
            <motion.div
              className="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-primary-500 to-primary-700 text-white shadow-lg shadow-primary-600/35 ring-2 ring-white/30 dark:ring-stone-800/50"
              whileHover={{ scale: 1.04, rotate: -2 }}
              transition={{ type: 'spring', stiffness: 400, damping: 22 }}
            >
              <Sparkles className="h-6 w-6" />
            </motion.div>
            <div className="min-w-0 pt-0.5">
              <p className="ui-kicker mb-1">Spring AI</p>
              <span className="font-display block text-2xl font-semibold leading-tight tracking-tight text-stone-900 dark:text-stone-50">
                试炼台
              </span>
              <span className="mt-1 block text-xs text-stone-500 dark:text-stone-400">智能面试与简历分析</span>
            </div>
          </Link>
        </div>

        <div className="px-4 pb-2 pt-4">
          <button
            type="button"
            onClick={toggleTheme}
            className="flex w-full items-center justify-center gap-2 rounded-xl border border-emerald-100/90 bg-white/70 px-3 py-2.5 text-sm font-medium text-stone-600 transition-colors hover:border-primary-200 hover:bg-[#fffef8] dark:border-stone-700 dark:bg-stone-900/80 dark:text-stone-300 dark:hover:border-primary-700/50 dark:hover:bg-stone-800"
          >
            {theme === 'dark' ? (
              <>
                <Sun className="h-4 w-4 text-lime-300" />
                浅色模式
              </>
            ) : (
              <>
                <Moon className="h-4 w-4 text-primary-600" />
                深色模式
              </>
            )}
          </button>
        </div>

        <nav className="flex-1 overflow-y-auto px-3 py-2 scrollbar-thin">
          <div className="space-y-8">
            {navGroups.map((group, gi) => (
              <motion.div
                key={group.id}
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: 0.05 * gi, duration: 0.35 }}
              >
                <div className="mb-2 px-2">
                  <span className="text-[0.65rem] font-semibold uppercase tracking-[0.2em] text-stone-400 dark:text-stone-500">
                    {group.title}
                  </span>
                </div>
                <div className="space-y-1">
                  {group.items.map((item) => {
                    const active = isActive(item.path);
                    return (
                      <Link key={item.id} to={item.path} className="group block rounded-xl focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-500/60">
                        <div
                          className={`relative flex items-center gap-3 overflow-hidden rounded-xl px-2.5 py-2.5 transition-all duration-200 ${
                            active
                              ? 'bg-gradient-to-r from-primary-500/12 to-transparent text-primary-800 dark:from-primary-500/20 dark:text-primary-200'
                              : 'text-stone-600 hover:bg-stone-100/90 dark:text-stone-400 dark:hover:bg-stone-800/80'
                          }`}
                        >
                          {active && (
                            <span
                              className="absolute bottom-2 left-0 top-2 w-0.5 rounded-full bg-gradient-to-b from-primary-400 to-lime-400"
                              aria-hidden
                            />
                          )}
                          <div
                            className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl transition-colors ${
                              active
                                ? 'bg-primary-500/15 text-primary-700 dark:bg-primary-500/25 dark:text-primary-300'
                                : 'bg-stone-100 text-stone-500 group-hover:bg-stone-200 group-hover:text-stone-800 dark:bg-stone-800 dark:text-stone-400 dark:group-hover:bg-stone-700 dark:group-hover:text-stone-100'
                            }`}
                          >
                            <item.icon className="h-5 w-5" />
                          </div>
                          <div className="min-w-0 flex-1">
                            <span className={`block text-sm ${active ? 'font-semibold' : 'font-medium'}`}>{item.label}</span>
                            {item.description && (
                              <span className="block truncate text-xs text-stone-400 dark:text-stone-500">{item.description}</span>
                            )}
                          </div>
                          {active && <ChevronRight className="h-4 w-4 shrink-0 text-primary-500/80" />}
                        </div>
                      </Link>
                    );
                  })}
                </div>
              </motion.div>
            ))}
          </div>
        </nav>

        <div className="border-t border-emerald-100/70 p-4 dark:border-stone-800/80">
          <div className="rounded-2xl border border-primary-200/55 bg-gradient-to-br from-[#f7fee7]/95 via-primary-50/90 to-white/80 px-4 py-3 dark:border-primary-900/40 dark:from-primary-950/45 dark:via-stone-900/80 dark:to-stone-900/90">
            <p className="truncate text-xs font-semibold text-primary-800 dark:text-primary-300">{displayName}</p>
            <p className="mt-0.5 text-[0.7rem] text-stone-500 dark:text-stone-400">试炼台 · 工作台</p>
            <button
              type="button"
              onClick={async () => {
                try {
                  const refreshToken = getRefreshToken();
                  if (refreshToken) {
                    await authApi.logout({ refreshToken });
                  }
                } finally {
                  clearAuthSession();
                  navigate('/login', { replace: true });
                }
              }}
              className="mt-3 flex w-full items-center justify-center gap-2 rounded-xl border border-stone-200/80 bg-white/60 py-2 text-xs font-medium text-stone-600 transition hover:bg-white dark:border-stone-600 dark:bg-stone-900/50 dark:text-stone-300 dark:hover:bg-stone-800"
            >
              <LogOut className="h-3.5 w-3.5" />
              退出登录
            </button>
          </div>
        </div>
      </aside>

      <main className="ml-[17.5rem] min-h-screen flex-1 overflow-y-auto px-6 py-10 sm:px-10 lg:px-14">
        <motion.div
          key={currentPath}
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.35, ease: [0.22, 1, 0.36, 1] }}
        >
          <Outlet />
        </motion.div>
      </main>
    </div>
  );
}
