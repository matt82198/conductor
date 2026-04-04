import { useEffect, useRef, useCallback } from 'react';
import { useConductorStore } from '../stores/conductorStore';
import { useTaskStore } from '../stores/taskStore';
import { useBrainDecisionStore } from '../stores/brainDecisionStore';
import type { ServerWsMessage } from '../types/events';
import type { TaskProgressWsMessage } from '../types/taskTypes';
import type { BrainDecisionEntry } from '../types/brainDecisionTypes';

const WS_URL = 'ws://localhost:8090/ws/events';
const RECONNECT_DELAY_MS = 3000;
const MAX_RECONNECT_DELAY_MS = 30000;

let decisionCounter = 0;
const makeDecisionId = () => `bd-${++decisionCounter}-${Date.now()}`;

/**
 * Unified WebSocket hook that handles ALL event types over a single connection.
 *
 * Replaces the three-connection pattern (useWebSocket + useTaskWebSocket +
 * useBrainDecisionWebSocket) with one connection that routes messages to the
 * appropriate store based on message type:
 *
 * - Agent stream events (agentId + eventType) -> conductorStore.processWsMessage
 * - human_input_needed / queued_message      -> conductorStore.processWsMessage
 * - brain_response / brain_escalation        -> conductorStore.processWsMessage
 *                                               + brainDecisionStore.addBrainDecision
 * - task_progress                            -> taskStore.processTaskProgress
 *
 * Connection lifecycle: exponential backoff reconnect (3s -> 30s cap).
 */
export function useUnifiedWebSocket(): void {
  const processWsMessage = useConductorStore((s) => s.processWsMessage);
  const setConnected = useConductorStore((s) => s.setConnected);
  const processTaskProgress = useTaskStore((s) => s.processTaskProgress);
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
        setConnected(true);
        reconnectDelayRef.current = RECONNECT_DELAY_MS;
      };

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);

          // ---- Task progress: route to taskStore only ----
          if (data.type === 'task_progress') {
            processTaskProgress(data as TaskProgressWsMessage);
            return;
          }

          // ---- Brain decisions: route to BOTH conductorStore AND brainDecisionStore ----
          if (data.type === 'brain_response') {
            // conductorStore handles event feed + human input removal
            processWsMessage(data as ServerWsMessage);

            // brainDecisionStore tracks the decision for the panel
            const agents = useConductorStore.getState().agents;
            const agentName = agents.get(data.agentId)?.name ?? data.agentId.slice(0, 8);
            const entry: BrainDecisionEntry = {
              id: makeDecisionId(),
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
            return;
          }

          if (data.type === 'brain_escalation') {
            // conductorStore handles event feed
            processWsMessage(data as ServerWsMessage);

            // brainDecisionStore tracks the decision for the panel
            const agents = useConductorStore.getState().agents;
            const agentName = agents.get(data.agentId)?.name ?? data.agentId.slice(0, 8);
            const entry: BrainDecisionEntry = {
              id: makeDecisionId(),
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
            return;
          }

          // ---- Agent stream events (original format) ----
          if (data.agentId && data.eventType) {
            processWsMessage(data as ServerWsMessage);
            return;
          }

          // ---- Other typed messages (human_input_needed, queued_message) ----
          if (
            data.type === 'human_input_needed' ||
            data.type === 'queued_message'
          ) {
            processWsMessage(data as ServerWsMessage);
            return;
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
  }, [processWsMessage, setConnected, processTaskProgress, addBrainDecision]);

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
