import type { StreamEnvelope } from './streamTypes';

const DONE_TOKEN = '[DONE]';

/**
 * 解析双通道 JSON SSE（与后端 `DualChannelSse` + 末尾 `[DONE]` 一致）。
 * 调用方需在 `response.ok` 为 true 后再调用。
 */
export async function parseDualChannelSseResponse(
  response: Response,
  onPart: (part: StreamEnvelope) => void,
  onDone: () => void,
  onError: (error: Error) => void
): Promise<void> {
  try {
    const reader = response.body?.getReader();
    if (!reader) {
      onError(new Error('无法获取响应流'));
      return;
    }

    const decoder = new TextDecoder();
    let buffer = '';
    let streamDone = false;

    const mergeDataPayload = (event: string): string | null => {
      if (!event.trim()) return null;
      const lines = event.split(/\r?\n/);
      const contentParts: string[] = [];
      for (const line of lines) {
        if (line.startsWith('data:')) {
          contentParts.push(line.substring(5).startsWith(' ') ? line.substring(6) : line.substring(5));
        }
      }
      if (contentParts.length === 0) return null;
      return contentParts.join('');
    };

    const finishStream = () => {
      if (streamDone) return;
      streamDone = true;
      reader.cancel().catch(() => {});
      onDone();
    };

    const processEvent = (event: string) => {
      if (streamDone) return;
      const merged = mergeDataPayload(event);
      if (merged === null) return;
      const trimmed = merged.trim();
      if (trimmed === DONE_TOKEN) {
        finishStream();
        return;
      }
      try {
        const obj = JSON.parse(merged) as { type?: string; delta?: string };
        if (
          (obj.type === 'reasoning' || obj.type === 'content') &&
          typeof obj.delta === 'string'
        ) {
          onPart({ type: obj.type, delta: obj.delta });
        } else {
          console.warn('[SSE] skip frame: invalid envelope', merged.slice(0, 120));
        }
      } catch {
        console.warn('[SSE] skip frame: JSON parse error', merged.slice(0, 120));
      }
    };

    while (!streamDone) {
      const { done, value } = await reader.read();

      if (done) {
        if (buffer.trim()) {
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
    onError(error instanceof Error ? error : new Error(String(error)));
  }
}
