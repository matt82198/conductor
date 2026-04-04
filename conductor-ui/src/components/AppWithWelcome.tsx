import { useState, useCallback } from 'react';
import { LoadingScreen } from './LoadingScreen';
import { WelcomeScreen } from './WelcomeScreen';
import { BrainDecisionsMainLayout } from './BrainDecisionsMainLayout';
import { useWebSocket } from '../hooks/useWebSocket';

/**
 * Enhanced root application component with a welcome screen.
 *
 * Drop-in replacement for the default App component.  The lifecycle is:
 *
 *   1. LoadingScreen  -- polls server, checks Brain status
 *   2. WelcomeScreen  -- atmospheric landing, dismisses on any key
 *   3. BrainDecisionsMainLayout -- full dashboard
 *
 * The welcome screen is shown every launch (it is a brief "vibe check",
 * not an onboarding wall) but dismisses instantly on any key/click.
 *
 * Integration: In the Electron entry or Vite entry point, swap the
 * default <App /> import with <AppWithWelcome />.
 *
 * ```tsx
 * import AppWithWelcome from './components/AppWithWelcome';
 * // ... render <AppWithWelcome /> instead of <App />
 * ```
 */
export default function AppWithWelcome() {
  const [loaded, setLoaded] = useState(false);
  const [welcomed, setWelcomed] = useState(false);

  const handleReady = useCallback(() => setLoaded(true), []);
  const handleEnter = useCallback(() => setWelcomed(true), []);

  // Establish WebSocket connection to Conductor server
  useWebSocket();

  if (!loaded) {
    return <LoadingScreen onReady={handleReady} />;
  }

  if (!welcomed) {
    return <WelcomeScreen onEnter={handleEnter} />;
  }

  return <BrainDecisionsMainLayout />;
}
