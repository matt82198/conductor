import { useState, useCallback } from 'react';
import { LoadingScreen } from './components/LoadingScreen';
import { WelcomeScreen } from './components/WelcomeScreen';
import { MainDashboard } from './components/MainDashboard';

/**
 * Root application layout.
 *
 * Flow: LoadingScreen → WelcomeScreen → MainDashboard
 *
 * The LoadingScreen polls for server connectivity and Brain status.
 * The WelcomeScreen is a premium splash (press any key to skip).
 * The MainDashboard is the unified workspace with single WebSocket.
 */
export default function App() {
  const [loaded, setLoaded] = useState(false);
  const [welcomed, setWelcomed] = useState(false);

  const handleReady = useCallback(() => setLoaded(true), []);
  const handleEnter = useCallback(() => setWelcomed(true), []);

  if (!loaded) {
    return <LoadingScreen onReady={handleReady} />;
  }

  if (!welcomed) {
    return <WelcomeScreen onEnter={handleEnter} />;
  }

  return <MainDashboard />;
}
