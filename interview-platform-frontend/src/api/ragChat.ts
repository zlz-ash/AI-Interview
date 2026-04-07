import { request, getErrorMessage } from './request';
import { clearAuthSession, getAccessToken } from '../auth/storage';

// 统一走同源 / Vite 代理，避免 CORS
const API_BASE_URL = '';

// ========== 类型定义 ==========

export interface RagChatSession {
  id: number;
  title: string;
  knowledgeBaseIds: number[];
  createdAt: string;
}

export interface RagChatSessionListItem {
  id: number;
  title: string;
  messageCount: number;
  knowledgeBaseNames: string[];
  updatedAt: string;
  isPinned: boolean;
}

export interface RagChatMessage {
  id: number;
  type: 'user' | 'assistant';
  content: string;
  createdAt: string;
}

export interface KnowledgeBaseItem {
  id: number;
  name: string;
  originalFilename: string;
  fileSize: number;
  contentType: string;
  uploadedAt: string;
  lastAccessedAt: string;
  accessCount: number;
  questionCount: number;
}

export interface RagChatSessionDetail {
  id: number;
  title: string;
  knowledgeBases: KnowledgeBaseItem[];
  messages: RagChatMessage[];
  createdAt: string;
  updatedAt: string;
}

// ========== API 函数 ==========

export const ragChatApi = {
  /**
   * 创建新会话
   */
  async createSession(knowledgeBaseIds: number[], title?: string): Promise<RagChatSession> {
    return request.post<RagChatSession>('/api/rag-chat/sessions', {
      knowledgeBaseIds,
      title,
    });
  },

  /**
   * 获取会话列表
   */
  async listSessions(): Promise<RagChatSessionListItem[]> {
    return request.get<RagChatSessionListItem[]>('/api/rag-chat/sessions');
  },

  /**
   * 获取会话详情
   */
  async getSessionDetail(sessionId: number): Promise<RagChatSessionDetail> {
    return request.get<RagChatSessionDetail>(`/api/rag-chat/sessions/${sessionId}`);
  },

  /**
   * 更新会话标题
   */
  async updateSessionTitle(sessionId: number, title: string): Promise<void> {
    return request.put(`/api/rag-chat/sessions/${sessionId}/title`, { title });
  },

  /**
   * 更新会话知识库
   */
  async updateKnowledgeBases(sessionId: number, knowledgeBaseIds: number[]): Promise<void> {
    return request.put(`/api/rag-chat/sessions/${sessionId}/knowledge-bases`, {
      knowledgeBaseIds,
    });
  },

  /**
   * 切换会话置顶状态
   */
  async togglePin(sessionId: number): Promise<void> {
    return request.put(`/api/rag-chat/sessions/${sessionId}/pin`);
  },

  /**
   * 删除会话
   */
  async deleteSession(sessionId: number): Promise<void> {
    return request.delete(`/api/rag-chat/sessions/${sessionId}`);
  },

  /**
   * 发送消息（流式SSE）
   */
  async sendMessageStream(
    sessionId: number,
    question: string,
    onMessage: (chunk: string) => void,
    onComplete: () => void,
    onError: (error: Error) => void
  ): Promise<void> {
    try {
      const token = getAccessToken();
      const response = await fetch(
        `${API_BASE_URL}/api/rag-chat/sessions/${sessionId}/messages/stream`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
          },
          body: JSON.stringify({ question }),
        }
      );

      if (!response.ok) {
        if (response.status === 401) {
          clearAuthSession();
          if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) {
            const next = `${window.location.pathname}${window.location.search}`;
            window.location.replace(`/login?redirect=${encodeURIComponent(next)}`);
          }
        }
        // 尝试解析错误响应
        try {
          const errorData = await response.json();
          if (errorData && errorData.message) {
            throw new Error(errorData.message);
          }
        } catch {
          // 忽略解析错误
        }
        throw new Error(`请求失败 (${response.status})`);
      }

      const reader = response.body?.getReader();
      if (!reader) {
        throw new Error('无法获取响应流');
      }

      const decoder = new TextDecoder();
      let buffer = '';

      const DONE_TOKEN = '[DONE]';

      // 从 SSE 事件中提取内容；返回 'DONE' 表示流结束，null 表示无内容
      const extractEventContent = (event: string): string | null | 'DONE' => {
        if (!event.trim()) return null;

        const lines = event.split(/\r?\n/);
        const contentParts: string[] = [];

        for (const line of lines) {
          if (line.startsWith('data:')) {
            contentParts.push(line.substring(5));
          }
        }

        if (contentParts.length === 0) return null;

        const merged = contentParts.join('')
          .replace(/\\n/g, '\n')
          .replace(/\\r/g, '\r');

        if (merged.trim() === DONE_TOKEN) {
          return 'DONE';
        }

        return merged;
      };

      let streamDone = false;

      const finishStream = () => {
        if (streamDone) return;
        streamDone = true;
        reader.cancel().catch(() => {});
        onComplete();
      };

      const processEvent = (event: string) => {
        if (streamDone) return;
        const content = extractEventContent(event);
        if (content === 'DONE') {
          finishStream();
          return;
        }
        if (content) {
          onMessage(content);
        }
      };

      while (!streamDone) {
        const { done, value } = await reader.read();

        if (done) {
          if (buffer) {
            processEvent(buffer);
          }
          if (!streamDone) finishStream();
          break;
        }

        buffer += decoder.decode(value, { stream: true });

        while (!streamDone) {
          buffer = buffer.replace(/^\r?\n+/, '');

          const idxLf = buffer.indexOf('\n\n');
          const idxCrlf = buffer.indexOf('\r\n\r\n');

          let splitIndex = -1;
          let splitLen = 0;

          if (idxLf !== -1 && idxCrlf !== -1) {
            if (idxCrlf < idxLf) {
              splitIndex = idxCrlf;
              splitLen = 4;
            } else {
              splitIndex = idxLf;
              splitLen = 2;
            }
          } else if (idxCrlf !== -1) {
            splitIndex = idxCrlf;
            splitLen = 4;
          } else if (idxLf !== -1) {
            splitIndex = idxLf;
            splitLen = 2;
          }

          if (splitIndex === -1) {
            const singleLineIndex = buffer.indexOf('\n');
            if (singleLineIndex !== -1) {
              const line = buffer.substring(0, singleLineIndex).replace(/\r$/, '');
              if (line.startsWith('data:')) {
                processEvent(line);
              }
              buffer = buffer.substring(singleLineIndex + 1);
              continue;
            }
            break;
          }

          const eventBlock = buffer.substring(0, splitIndex);
          buffer = buffer.substring(splitIndex + splitLen);

          processEvent(eventBlock);
        }
      }
    } catch (error) {
      onError(new Error(getErrorMessage(error)));
    }
  },
};
