import { useEffect, useRef, useCallback } from 'react';
import { useConductorStore } from '../stores/conductorStore';
import type { ServerWsMessage } from '../types/events';

const WS_URL = 'ws://localhost:8090/ws/events';
const RECONNECT_DELAY_MS = 3000;
const MAX_RECONNECT_DELAY_MS = 30000;

export function useWebSocket(): void {
  const processWsMessage = useConductorStore((s) => s.processWsMessage);
  const setConnected = useConductorStore((s) => s.setConnected);

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
        setConnected(true);
        reconnectDelayRef.current = RECONNECT_DELAY_MS;
      };

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          // Original format: { agentId, eventType, event }
          if (data.agentId && data.eventType) {
            processWsMessage(data as ServerWsMessage);
          }
          // New format: { type: "human_input_needed" | "queued_message" | "brain_response" | "brain_escalation" }
          else if (
            data.type === 'human_input_needed' ||
            data.type === 'queued_message' ||
            data.type === 'brain_response' ||
            data.type === 'brain_escalation'
          ) {
            processWsMessage(data as ServerWsMessage);
          }
        } catch {
          // skip malformed messages
        }
      };

      ws.onclose = () => {
        setConnected(false);
        wsRef.current = null;
        scheduleReconnect();
      };

      ws.onerror = () => {
        setConnected(false);
      };
    } catch {
      setConnected(false);
      scheduleReconnect();
    }
  }, [processWsMessage, setConnected]);

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
