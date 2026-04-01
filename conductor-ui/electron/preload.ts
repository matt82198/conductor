import { contextBridge, ipcRenderer } from 'electron';

/**
 * Preload script exposes a safe subset of Electron APIs to the renderer
 * via contextBridge. The renderer accesses these through window.conductor.
 */
contextBridge.exposeInMainWorld('conductor', {
  /** Get the app version string */
  getAppVersion: (): Promise<string> => ipcRenderer.invoke('get-app-version'),

  /** Listen for keyboard shortcuts from the main process */
  onFocusSpawn: (callback: () => void): void => {
    ipcRenderer.on('shortcut:focus-spawn', () => callback());
  },

  onEscape: (callback: () => void): void => {
    ipcRenderer.on('shortcut:escape', () => callback());
  },

  /** Remove all listeners for a channel */
  removeAllListeners: (channel: string): void => {
    ipcRenderer.removeAllListeners(channel);
  },
});
