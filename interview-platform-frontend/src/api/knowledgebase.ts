import {getErrorMessage, request} from './request';
import { clearAuthSession, getAccessToken } from '../auth/storage';
import type { StreamEnvelope } from './streamTypes';
import { parseDualChannelSseResponse } from './parseDualChannelSse';

// 向量化状态
export type VectorStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface KnowledgeBaseItem {
  id: number;
  name: string;
  category: string | null;
  originalFilename: string;
  fileSize: number;
  contentType: string;
  uploadedAt: string;
  lastAccessedAt: string;
  accessCount: number;
  questionCount: number;
  vectorStatus: VectorStatus;
  vectorError: string | null;
  chunkCount: number;
}

// 统计信息
export interface KnowledgeBaseStats {
  totalCount: number;
  totalQuestionCount: number;
  totalAccessCount: number;
  completedCount: number;
  processingCount: number;
}

export type SortOption = 'time' | 'size' | 'access' | 'question';

export interface UploadKnowledgeBaseResponse {
  knowledgeBase: {
    id: number;
    name: string;
    category: string;
    fileSize: number;
    contentLength: number;
  };
  storage: {
    fileKey: string;
    fileUrl: string;
  };
  duplicate: boolean;
}

export interface QueryRequest {
  knowledgeBaseIds: number[];  // 支持多个知识库
  question: string;
}

export interface QueryResponse {
  answer: string;
  knowledgeBaseId: number;
  knowledgeBaseName: string;
}

export const knowledgeBaseApi = {
  /**
   * 上传知识库文件
   */
  async uploadKnowledgeBase(file: File, name?: string, category?: string): Promise<UploadKnowledgeBaseResponse> {
    const formData = new FormData();
    formData.append('file', file);
    if (name) {
      formData.append('name', name);
    }
    if (category) {
      formData.append('category', category);
    }
    return request.upload<UploadKnowledgeBaseResponse>('/api/knowledgebase/upload', formData);
  },

    /**
     * 下载知识库文件
     */
    async downloadKnowledgeBase(id: number): Promise<Blob> {
        const response = await request.getInstance().get(`/api/knowledgebase/${id}/download`, {
            responseType: 'blob',
        });
        return response.data;
    },

  /**
   * 获取所有知识库列表
   */
  async getAllKnowledgeBases(sortBy?: SortOption, vectorStatus?: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'): Promise<KnowledgeBaseItem[]> {
    const params = new URLSearchParams();
    if (sortBy) {
      params.append('sortBy', sortBy);
    }
    if (vectorStatus) {
      params.append('vectorStatus', vectorStatus);
    }
    const queryString = params.toString();
    return request.get<KnowledgeBaseItem[]>(`/api/knowledgebase/list${queryString ? `?${queryString}` : ''}`);
  },

  /**
   * 获取知识库详情
   */
  async getKnowledgeBase(id: number): Promise<KnowledgeBaseItem> {
    return request.get<KnowledgeBaseItem>(`/api/knowledgebase/${id}`);
  },

  /**
   * 删除知识库
   */
  async deleteKnowledgeBase(id: number): Promise<void> {
    return request.delete(`/api/knowledgebase/${id}`);
  },

  // ========== 分类管理 ==========

  /**
   * 获取所有分类
   */
  async getAllCategories(): Promise<string[]> {
    return request.get<string[]>('/api/knowledgebase/categories');
  },

  /**
   * 根据分类获取知识库
   */
  async getByCategory(category: string): Promise<KnowledgeBaseItem[]> {
    return request.get<KnowledgeBaseItem[]>(`/api/knowledgebase/category/${encodeURIComponent(category)}`);
  },

  /**
   * 获取未分类的知识库
   */
  async getUncategorized(): Promise<KnowledgeBaseItem[]> {
    return request.get<KnowledgeBaseItem[]>('/api/knowledgebase/uncategorized');
  },

  /**
   * 更新知识库分类
   */
  async updateCategory(id: number, category: string | null): Promise<void> {
    return request.put(`/api/knowledgebase/${id}/category`, { category });
  },

  // ========== 搜索 ==========

  /**
   * 搜索知识库
   */
  async search(keyword: string): Promise<KnowledgeBaseItem[]> {
    return request.get<KnowledgeBaseItem[]>(`/api/knowledgebase/search?keyword=${encodeURIComponent(keyword)}`);
  },

  // ========== 统计 ==========

  /**
   * 获取知识库统计信息
   */
  async getStatistics(): Promise<KnowledgeBaseStats> {
    return request.get<KnowledgeBaseStats>('/api/knowledgebase/stats');
  },

  // ========== 向量化管理 ==========

  /**
   * 重新向量化知识库（手动重试）
   */
  async revectorize(id: number): Promise<void> {
    return request.post(`/api/knowledgebase/${id}/revectorize`);
  },

  /**
   * 基于知识库回答问题
   */
  async queryKnowledgeBase(req: QueryRequest): Promise<QueryResponse> {
    return request.post<QueryResponse>('/api/knowledgebase/query', req, {
      timeout: 180000, // 3分钟超时
    });
  },

  /**
   * 基于知识库回答问题（流式 SSE，JSON 信封 + `[DONE]`）
   */
  async queryKnowledgeBaseStream(
    req: QueryRequest,
    onPart: (part: StreamEnvelope) => void,
    onComplete: () => void,
    onError: (error: Error) => void
  ): Promise<void> {
    try {
      const token = getAccessToken();
      const response = await fetch(`/api/knowledgebase/query/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify(req),
      });

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
