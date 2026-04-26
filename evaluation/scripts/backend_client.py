"""
backend_client.py
=================

对接 Spring Boot 后端的小客户端：

1. 先用 /api/auth/login 拿 JWT accessToken；
2. 再调用 /api/rag-chat/evaluate 拿到 (answer, contexts)。

为什么要先登录？
  因为 SecurityConfig 中 "/api/rag-chat/**" 受 RAG:ACCESS 权限保护，
  评测端点 /api/rag-chat/evaluate 同样需要鉴权。

这个文件只封装 "网络通信"，不做任何评测逻辑；
跑 ragas 的主文件在 scripts/run_ragas.py。
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import List, Optional

import httpx


# ----------------------------------------------------------------
# 后端契约（与 Java 端的 DTO 一一对应）
# ----------------------------------------------------------------
@dataclass
class ContextChunk:
    """后端 RagEvaluationDTO.ContextChunkDTO 的 Python 侧映射。"""
    chunkId: Optional[str]
    knowledgeBaseId: Optional[int]
    source: Optional[str]
    score: Optional[float]
    text: str


@dataclass
class EvaluateResponse:
    """后端 RagEvaluationDTO.EvaluateResponse 的 Python 侧映射。"""
    answer: str
    contexts: List[ContextChunk] = field(default_factory=list)


# ----------------------------------------------------------------
# 客户端实现
# ----------------------------------------------------------------
class BackendClient:
    """
    线程安全的最小化客户端。

    典型用法：
        client = BackendClient.from_env()
        client.login()
        resp = client.evaluate(kb_ids=[1], question="xxx")
    """

    def __init__(
        self,
        base_url: str,
        username: str,
        password: str,
        timeout_seconds: float = 300.0,
    ) -> None:
        # Spring Boot 生成 answer 可能较慢（M2.5 同步调用），留足超时
        self._base_url = base_url.rstrip("/")
        self._username = username
        self._password = password
        self._client = httpx.Client(timeout=timeout_seconds, trust_env=True)
        self._access_token: Optional[str] = None

    # ---- 构造器 ----
    @classmethod
    def from_env(cls) -> "BackendClient":
        """从 .env 读取配置构造客户端。"""
        base_url = os.getenv("BACKEND_BASE_URL", "http://localhost:8080")
        username = os.getenv("BACKEND_USERNAME") or ""
        password = os.getenv("BACKEND_PASSWORD") or ""
        timeout_seconds = float(os.getenv("BACKEND_TIMEOUT_SECONDS", "300"))
        if not username or not password:
            raise RuntimeError(
                "缺少 BACKEND_USERNAME / BACKEND_PASSWORD，请检查 .env"
            )
        return cls(
            base_url=base_url,
            username=username,
            password=password,
            timeout_seconds=timeout_seconds,
        )

    # ---- 关闭连接 ----
    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> "BackendClient":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    # ---- 登录 ----
    def login(self) -> str:
        """调用 /api/auth/login，缓存 accessToken 并返回。"""
        url = f"{self._base_url}/api/auth/login"
        payload = {
            "username": self._username,
            "password": self._password,
            "rememberMe": False,
        }
        resp = self._client.post(url, json=payload)
        resp.raise_for_status()
        body = resp.json()
        # 后端返回结构：{ code, msg, data: AuthTokenResponse }
        if body.get("code") not in (0, 200):
            raise RuntimeError(f"登录失败: {body}")
        token = body["data"]["accessToken"]
        if not token:
            raise RuntimeError(f"登录成功但没拿到 accessToken: {body}")
        self._access_token = token
        return token

    # ---- 核心：评测端点 ----
    def evaluate(
        self,
        kb_ids: List[int],
        question: str,
        retrieval_mode: str = "HYBRID",
    ) -> EvaluateResponse:
        """
        调用 /api/rag-chat/evaluate。

        参数：
          kb_ids         : 要检索的知识库 ID 列表
          question       : 用户问题
          retrieval_mode : 检索模式，HYBRID / VECTOR

        返回：
          EvaluateResponse(answer, contexts)
        """
        if self._access_token is None:
            self.login()

        url = f"{self._base_url}/api/rag-chat/evaluate"
        headers = {"Authorization": f"Bearer {self._access_token}"}
        payload = {
            "knowledgeBaseIds": kb_ids,
            "question": question,
            "retrievalMode": retrieval_mode,
        }

        resp = self._client.post(url, headers=headers, json=payload)
        # 若 token 过期或首次尚未登录，尝试重新登录一次再重发
        if resp.status_code == 401:
            self.login()
            headers["Authorization"] = f"Bearer {self._access_token}"
            resp = self._client.post(url, headers=headers, json=payload)

        resp.raise_for_status()
        body = resp.json()
        if body.get("code") not in (0, 200):
            raise RuntimeError(f"评测接口返回失败: {body}")

        data = body.get("data") or {}
        raw_contexts = data.get("contexts") or []
        contexts = [
            ContextChunk(
                chunkId=c.get("chunkId"),
                knowledgeBaseId=c.get("knowledgeBaseId"),
                source=c.get("source"),
                score=c.get("score"),
                text=c.get("text") or "",
            )
            for c in raw_contexts
        ]
        return EvaluateResponse(
            answer=data.get("answer") or "",
            contexts=contexts,
        )
