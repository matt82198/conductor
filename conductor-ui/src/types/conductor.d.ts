/**
 * Type declarations for the conductor preload API exposed on window.conductor.
 */
export interface ConductorAPI {
  getAppVersion(): Promise<string>;
  onFocusSpawn(callback: () => void): void;
  onEscape(callback: () => void): void;
  removeAllListeners(channel: string): void;
}

declare global {
  interface Window {
    conductor?: ConductorAPI;
  }
}
