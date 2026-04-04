import { useEffect, useRef, useCallback } from 'react';
import { useBrainDecisionStore } from '../stores/brainDecisionStore';
import { useConductorStore } from '../stores/conductorStore';
import type { BrainDecisionEntry } from '../types/brainDecisionTypes';

/**
 * Tertiary WebSocket hook that listens for brain_response and brain_escalation
 * messages and populates the BrainDecisionStore.
 *
 * This opens its own connection to the same server WS endpoint. The server
 * broadcasts all events to every connected client, so this connection receives
 * all messages but only processes brain_response and brain_escalation types.
 * The main useWebSocket hook also handles these events (for event feed and
 * human input removal), so both stores get updated independently.
 *
 * This design avoids modifying the existing useWebSocket or processWsMessage
 * code while still capturing brain decision events for the dedicated panel.
 */
const WS_URL = 'ws://localhost:8090/ws/events';
const RECONNECT_DELAY_MS = 3000;
const MAX_RECONNECT_DELAY_MS = 30000;

let decisionCounter = 0;
const makeId = () => `bd-${++decisionCounter}-${Date.now()}`;

export function useBrainDecisionWebSocket(): void {
  const addBrainDecision = useBrainDecisionStore((s) => s.addBrainDecision);

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

          if (data.type === 'brain_response') {
            const agents = useConductorStore.getState().agents;
            const agentName = agents.get(data.agentId)?.name ?? data.agentId.slice(0, 8);
            const entry: BrainDecisionEntry = {
              id: makeId(),
              requestId: data.requestId,
              agentId: data.agentId,
              agentName,
              action: 'RESPOND',
              response: data.response,
              reasoning: data.reasoning,
              confidence: data.confidence,
              timestamp: new Date(),
              feedback: null,
              correction: null,
            };
            addBrainDecision(entry);
          }

          if (data.type === 'brain_escalation') {
            const agents = useConductorStore.getState().agents;
            const agentName = agents.get(data.agentId)?.name ?? data.agentId.slice(0, 8);
            const entry: BrainDecisionEntry = {
              id: makeId(),
              requestId: data.requestId,
              agentId: data.agentId,
              agentName,
              action: 'ESCALATE',
              response: null,
              reasoning: data.reason,
              confidence: data.confidence,
              timestamp: new Date(),
              feedback: null,
              correction: null,
            };
            addBrainDecision(entry);
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
  }, [addBrainDecision]);

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
