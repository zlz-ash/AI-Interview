import { useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { AlertTriangle, Loader2, RefreshCw } from 'lucide-react';
import { getErrorMessage } from '../api/request';
import { interviewApi } from '../api/interview';
import { getScoreColor } from '../utils/score';
import type { InterviewDetail } from '../api/history';

interface InterviewDetailPanelProps {
  interview: InterviewDetail;
  /** 重新拉取详情并同步列表（如评估状态轮询后） */
  onInterviewUpdated?: () => void | Promise<void>;
}

function interviewFinished(status: string): boolean {
  return status === 'COMPLETED' || status === 'EVALUATED';
}

function isEvaluatingFlow(evaluateStatus?: string): boolean {
  return evaluateStatus === 'PENDING' || evaluateStatus === 'PROCESSING';
}

function canRequestReevaluation(interview: InterviewDetail): boolean {
  if (!interviewFinished(interview.status)) return false;
  if (isEvaluatingFlow(interview.evaluateStatus)) return false;
  return true;
}

/**
 * 面试详情面板组件
 */
export default function InterviewDetailPanel({ interview, onInterviewUpdated }: InterviewDetailPanelProps) {
  const [expandedQuestions, setExpandedQuestions] = useState<Set<number>>(() => {
    const allIndices = new Set<number>();
    if (interview.answers) {
      interview.answers.forEach((_, idx) => allIndices.add(idx));
    }
    return allIndices;
  });

  const [reevaluating, setReevaluating] = useState(false);
  const [reevaluateError, setReevaluateError] = useState('');

  const toggleQuestion = (index: number) => {
    setExpandedQuestions((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(index)) {
        newSet.delete(index);
      } else {
        newSet.add(index);
      }
      return newSet;
    });
  };

  const handleReevaluate = async () => {
    setReevaluateError('');
    setReevaluating(true);
    try {
      await interviewApi.requestReevaluation(interview.sessionId);
      await onInterviewUpdated?.();
    } catch (e) {
      setReevaluateError(getErrorMessage(e));
    } finally {
      setReevaluating(false);
    }
  };

  const showRetryBanner = interview.evaluateStatus === 'FAILED';
  const allowButton = canRequestReevaluation(interview);
  const failed = interview.evaluateStatus === 'FAILED';

  return (
    <motion.div className="space-y-6" initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }}>
      {showRetryBanner && (
        <motion.div
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          className="dark-card flex flex-col gap-4 border-red-200/70 bg-gradient-to-br from-red-50/95 to-amber-50/40 p-5 dark:border-red-900/40 dark:from-red-950/35 dark:to-stone-900/80 sm:flex-row sm:items-center sm:justify-between"
        >
          <div className="flex gap-3">
            <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-red-100 text-red-600 dark:bg-red-950/60 dark:text-red-400">
              <AlertTriangle className="h-5 w-5" />
            </div>
            <div>
              <p className="font-display text-lg font-semibold text-red-900 dark:text-red-200">评估未能完成</p>
              <p className="mt-1 text-sm text-red-800/90 dark:text-red-300/90">
                {interview.evaluateError?.trim() || '生成面试评估时出错，可重试；服务端可能对重试次数有限制。'}
              </p>
            </div>
          </div>
          {allowButton && (
            <motion.button
              type="button"
              disabled={reevaluating}
              onClick={handleReevaluate}
              className="inline-flex shrink-0 items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-primary-600 to-primary-700 px-5 py-3 text-sm font-semibold text-white shadow-md shadow-primary-700/20 transition hover:shadow-lg disabled:cursor-not-allowed disabled:opacity-60"
              whileHover={{ scale: reevaluating ? 1 : 1.02 }}
              whileTap={{ scale: reevaluating ? 1 : 0.98 }}
            >
              {reevaluating ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" />
                  提交中…
                </>
              ) : (
                <>
                  <RefreshCw className="h-4 w-4" />
                  重试面试评估
                </>
              )}
            </motion.button>
          )}
        </motion.div>
      )}

      {reevaluateError && (
        <p className="rounded-xl border border-red-200/80 bg-red-50/90 px-4 py-3 text-center text-sm text-red-700 dark:border-red-900/50 dark:bg-red-950/40 dark:text-red-300">
          {reevaluateError}
        </p>
      )}

      <ScoreCard
        score={interview.overallScore}
        feedback={interview.overallFeedback}
        circumference={2 * Math.PI * 54}
        strokeDashoffset={
          2 * Math.PI * 54 -
          ((interview.overallScore !== null ? interview.overallScore : 0) / 100) * (2 * Math.PI * 54)
        }
        showSecondaryReevaluate={!failed && allowButton}
        onReevaluate={handleReevaluate}
        reevaluating={reevaluating}
        evaluateStatus={interview.evaluateStatus}
      />

      {interview.strengths && interview.strengths.length > 0 && <StrengthsSection strengths={interview.strengths} />}

      {interview.improvements && interview.improvements.length > 0 && (
        <ImprovementsSection improvements={interview.improvements} />
      )}

      <QuestionsSection
        answers={interview.answers || []}
        expandedQuestions={expandedQuestions}
        toggleQuestion={toggleQuestion}
      />
    </motion.div>
  );
}

function ScoreCard({
  score,
  feedback,
  circumference,
  strokeDashoffset,
  showSecondaryReevaluate,
  onReevaluate,
  reevaluating,
  evaluateStatus,
}: {
  score: number | null;
  feedback: string | null;
  circumference: number;
  strokeDashoffset: number;
  showSecondaryReevaluate: boolean;
  onReevaluate: () => void;
  reevaluating: boolean;
  evaluateStatus?: string;
}) {
  return (
    <div className="relative overflow-hidden rounded-[1.35rem] bg-gradient-to-br from-primary-600 via-emerald-700 to-primary-800 p-8 text-white shadow-lg shadow-primary-900/15 ring-1 ring-white/10">
      <div
        className="pointer-events-none absolute -right-16 -top-16 h-48 w-48 rounded-full bg-lime-300/20 blur-3xl"
        aria-hidden
      />
      <div className="relative flex flex-col items-center text-center">
        <div className="relative mb-6 h-32 w-32">
          <svg className="h-32 w-32 -rotate-90 transform" viewBox="0 0 120 120">
            <circle cx="60" cy="60" r="54" stroke="rgba(255,255,255,0.18)" strokeWidth="8" fill="none" />
            <motion.circle
              cx="60"
              cy="60"
              r="54"
              stroke="white"
              strokeWidth="8"
              fill="none"
              strokeLinecap="round"
              strokeDasharray={circumference}
              initial={{ strokeDashoffset: circumference }}
              animate={{ strokeDashoffset }}
              transition={{ duration: 1.5, ease: 'easeOut' }}
            />
          </svg>
          <div className="absolute inset-0 flex flex-col items-center justify-center">
            <motion.span
              className="font-display text-4xl font-bold"
              initial={{ opacity: 0, scale: 0.5 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ delay: 0.5 }}
            >
              {score ?? '-'}
            </motion.span>
            <span className="text-sm text-white/75">总分</span>
          </div>
        </div>

        <h3 className="font-display mb-2 text-2xl font-bold">面试评估</h3>
        {evaluateStatus === 'COMPLETED' && (
          <span className="mb-3 rounded-full bg-white/15 px-3 py-0.5 text-[0.65rem] font-semibold uppercase tracking-wider text-lime-100">
            评估已完成
          </span>
        )}
        <p className="max-w-2xl leading-relaxed text-white/90">
          {feedback || '表现良好，展示了扎实的技术基础。'}
        </p>

        {showSecondaryReevaluate && (
          <motion.button
            type="button"
            disabled={reevaluating}
            onClick={onReevaluate}
            className="mt-6 flex items-center gap-2 rounded-xl border border-white/35 bg-white/10 px-4 py-2.5 text-sm font-medium text-white backdrop-blur-sm transition hover:bg-white/20 disabled:cursor-not-allowed disabled:opacity-60"
            whileHover={{ scale: reevaluating ? 1 : 1.02 }}
            whileTap={{ scale: reevaluating ? 1 : 0.98 }}
          >
            {reevaluating ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
            重新评估
          </motion.button>
        )}
      </div>
    </div>
  );
}

function StrengthsSection({ strengths }: { strengths: string[] }) {
  return (
    <motion.div
      className="dark-card p-6"
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: 0.1 }}
    >
      <h4 className="mb-4 flex items-center gap-2 font-semibold text-primary-700 dark:text-primary-400">
        <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none">
          <path
            d="M22 11.08V12a10 10 0 1 1-5.93-9.14"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <polyline
            points="22,4 12,14.01 9,11.01"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
        表现优势
      </h4>
      <ul className="space-y-3">
        {strengths.map((s: string, i: number) => (
          <li key={i} className="flex items-start gap-3 text-stone-700 dark:text-stone-300">
            <span className="mt-2 h-2 w-2 flex-shrink-0 rounded-full bg-primary-500" />
            <span>{s}</span>
          </li>
        ))}
      </ul>
    </motion.div>
  );
}

function ImprovementsSection({ improvements }: { improvements: string[] }) {
  return (
    <motion.div
      className="dark-card p-6"
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: 0.2 }}
    >
      <h4 className="mb-4 flex items-center gap-2 font-semibold text-amber-600 dark:text-amber-400">
        <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none">
          <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2" />
          <line x1="12" y1="8" x2="12" y2="12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          <line x1="12" y1="16" x2="12.01" y2="16" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
        </svg>
        改进建议
      </h4>
      <ul className="space-y-3">
        {improvements.map((s: string, i: number) => (
          <li key={i} className="flex items-start gap-3 text-stone-700 dark:text-stone-300">
            <span className="mt-2 h-2 w-2 flex-shrink-0 rounded-full bg-amber-500" />
            <span>{s}</span>
          </li>
        ))}
      </ul>
    </motion.div>
  );
}

function QuestionsSection({
  answers,
  expandedQuestions,
  toggleQuestion,
}: {
  answers: any[];
  expandedQuestions: Set<number>;
  toggleQuestion: (index: number) => void;
}) {
  return (
    <div>
      <h4 className="mb-4 flex items-center gap-2 font-semibold text-stone-800 dark:text-white">
        <svg className="h-5 w-5 text-primary-500" viewBox="0 0 24 24" fill="none">
          <path
            d="M21 15C21 15.5304 20.7893 16.0391 20.4142 16.4142C20.0391 16.7893 19.5304 17 19 17H7L3 21V5C3 4.46957 3.21071 3.96086 3.58579 3.58579C3.96086 3.21071 4.46957 3 5 3H19C19.5304 3 20.0391 3.21071 20.4142 3.58579C20.7893 3.96086 21 4.46957 21 5V15Z"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
        问答记录详情
      </h4>

      <div className="space-y-4">
        {answers.map((answer, idx) => (
          <QuestionCard
            key={idx}
            answer={answer}
            index={idx}
            isExpanded={expandedQuestions.has(idx)}
            onToggle={() => toggleQuestion(idx)}
          />
        ))}
      </div>
    </div>
  );
}

function QuestionCard({
  answer,
  index,
  isExpanded,
  onToggle,
}: {
  answer: any;
  index: number;
  isExpanded: boolean;
  onToggle: () => void;
}) {
  return (
    <motion.div
      className="dark-card overflow-hidden"
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: 0.1 + index * 0.05 }}
    >
      <div
        className="flex cursor-pointer items-center justify-between px-5 py-4 transition-colors hover:bg-stone-50/80 dark:hover:bg-stone-800/50"
        onClick={onToggle}
      >
        <div className="flex items-center gap-3">
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-stone-100 text-sm font-semibold text-stone-600 dark:bg-stone-700 dark:text-stone-300">
            {answer.questionIndex + 1}
          </span>
          <span className="rounded-full bg-primary-50 px-3 py-1 text-xs font-medium text-primary-700 dark:bg-primary-950/50 dark:text-primary-300">
            {answer.category || '综合'}
          </span>
          <span className={`font-semibold ${getScoreColor(answer.score, [80, 60])}`}>得分: {answer.score}</span>
        </div>
        <motion.svg
          className="h-5 w-5 text-stone-400"
          animate={{ rotate: isExpanded ? 180 : 0 }}
          transition={{ duration: 0.2 }}
          viewBox="0 0 24 24"
          fill="none"
        >
          <polyline
            points="6,9 12,15 18,9"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </motion.svg>
      </div>

      <div className="px-5 pb-2">
        <p className="font-medium leading-relaxed text-stone-800 dark:text-white">{answer.question}</p>
      </div>

      <AnimatePresence>
        {isExpanded && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.3 }}
            className="overflow-hidden"
          >
            <div className="space-y-4 px-5 pb-5">
              <div className="rounded-xl bg-stone-50/90 p-4 dark:bg-stone-800/60">
                <p className="mb-2 flex items-center gap-1 text-sm text-stone-500 dark:text-stone-400">
                  <svg className="h-4 w-4" viewBox="0 0 24 24" fill="none">
                    <path
                      d="M21 15C21 15.5304 20.7893 16.0391 20.4142 16.4142C20.0391 16.7893 19.5304 17 19 17H7L3 21V5C3 4.46957 3.21071 3.96086 3.58579 3.58579C3.96086 3.21071 4.46957 3 5 3H19C19.5304 3 20.0391 3.21071 20.4142 3.58579C20.7893 3.96086 21 4.46957 21 5V15Z"
                      stroke="currentColor"
                      strokeWidth="2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                  你的回答
                </p>
                <p
                  className={`leading-relaxed ${
                    !answer.userAnswer || answer.userAnswer === '不知道'
                      ? 'font-medium text-red-500'
                      : 'text-stone-700 dark:text-stone-300'
                  }`}
                >
                  "{answer.userAnswer || '(未回答)'}"
                </p>
              </div>

              {answer.feedback && (
                <div>
                  <p className="mb-2 flex items-center gap-2 text-sm font-medium text-stone-600 dark:text-stone-400">
                    <svg className="h-4 w-4 text-primary-500" viewBox="0 0 24 24" fill="none">
                      <path d="M3 3V21H21" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                      <path d="M18 9L12 15L9 12L3 18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                    AI 深度评价
                  </p>
                  <p className="pl-6 leading-relaxed text-stone-700 dark:text-stone-300">{answer.feedback}</p>
                </div>
              )}

              {answer.referenceAnswer && (
                <div className="rounded-xl border border-emerald-100/80 bg-stone-50/90 p-4 dark:border-stone-600 dark:bg-stone-800/50">
                  <p className="mb-3 flex items-center gap-2 text-sm font-medium text-stone-600 dark:text-stone-400">
                    <svg className="h-4 w-4 text-primary-500" viewBox="0 0 24 24" fill="none">
                      <rect x="3" y="3" width="18" height="18" rx="2" stroke="currentColor" strokeWidth="2" />
                      <path d="M9 12H15" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
                      <path d="M12 9V15" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
                    </svg>
                    参考答案
                  </p>
                  <div className="whitespace-pre-line leading-relaxed text-stone-700 dark:text-stone-300">
                    {answer.referenceAnswer}
                  </div>
                </div>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}
