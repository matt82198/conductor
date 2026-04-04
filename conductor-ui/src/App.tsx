import { useState, useCallback } from 'react';
import { LoadingScreen } from './components/LoadingScreen';
import { TaskMainLayout } from './components/TaskMainLayout';
import { useWebSocket } from './hooks/useWebSocket';

/**
 * Root application layout.
 *
 * On first load, shows a LoadingScreen that polls for server connectivity
 * and checks Brain status. Once ready, transitions to the main dashboard
 * with task decomposition support.
 */
export default function App() {
  const [loaded, setLoaded] = useState(false);
  const handleReady = useCallback(() => setLoaded(true), []);

  // Establish WebSocket connection to Conductor server
  useWebSocket();

  if (!loaded) {
    return <LoadingScreen onReady={handleReady} />;
  }

  return <TaskMainLayout />;
}
