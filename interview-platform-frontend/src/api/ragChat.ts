import { request, getErrorMessage } from './request';
import { clearAuthSession, getAccessToken } from '../auth/storage';
import type { StreamEnvelope } from './streamTypes';
import { parseDualChannelSseResponse } from './parseDualChannelSse';

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
   * 发送消息（流式 SSE，`data:` 为 JSON：`{ type, delta }`，结束为 `[DONE]`）
   */
  async sendMessageStream(
    sessionId: number,
    question: string,
    onPart: (part: StreamEnvelope) => void,
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

      await parseDualChannelSseResponse(response, onPart, onComplete, onError);
    } catch (error) {
      onError(new Error(getErrorMessage(error)));
    }
  },
};
