import { useEffect, useRef, useCallback } from 'react';
import { useTaskStore } from '../stores/taskStore';
import type { TaskProgressWsMessage } from '../types/taskTypes';

/**
 * Secondary WebSocket hook that listens for task_progress messages.
 *
 * This opens its own connection to the same server WS endpoint. The server
 * broadcasts all events to every connected client, so this connection receives
 * all messages but only processes `task_progress` type. The main useWebSocket
 * hook ignores task_progress (it falls through its if/switch chain silently),
 * so there is no duplicate processing.
 *
 * This design avoids modifying the existing useWebSocket or processWsMessage
 * code while still capturing task-related events in real time.
 */
const WS_URL = 'ws://localhost:8090/ws/events';
const RECONNECT_DELAY_MS = 3000;
const MAX_RECONNECT_DELAY_MS = 30000;

export function useTaskWebSocket(): void {
  const processTaskProgress = useTaskStore((s) => s.processTaskProgress);

  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectDelayRef = useRef(RECONNECT_DELAY_MS);
  const unmountedRef = useRef(false);

  const connect = useCallback(() => {
    if (unmountedRef.current) return;
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }

    try {
      const ws = new WebSocket(WS_URL);
      wsRef.current = ws;

      ws.onopen = () => {
        reconnectDelayRef.current = RECONNECT_DELAY_MS;
      };

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          // Only handle task_progress messages; ignore everything else
          if (data.type === 'task_progress') {
            processTaskProgress(data as TaskProgressWsMessage);
          }
        } catch {
          // skip malformed messages
        }
      };

      ws.onclose = () => {
        wsRef.current = null;
        scheduleReconnect();
      };

      ws.onerror = () => {
        // error handled by onclose
      };
    } catch {
      scheduleReconnect();
    }
  }, [processTaskProgress]);

  const scheduleReconnect = useCallback(() => {
    if (unmountedRef.current) return;
    if (reconnectTimeoutRef.current) return;

    reconnectTimeoutRef.current = setTimeout(() => {
      reconnectTimeoutRef.current = null;
      connect();
    }, reconnectDelayRef.current);

    reconnectDelayRef.current = Math.min(
      reconnectDelayRef.current * 1.5,
      MAX_RECONNECT_DELAY_MS,
    );
  }, [connect]);

  useEffect(() => {
    unmountedRef.current = false;
    connect();
    return () => {
      unmountedRef.current = true;
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
      }
      if (wsRef.current) {
        wsRef.current.close();
      }
    };
  }, [connect]);
}
