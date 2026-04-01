import { app, BrowserWindow, globalShortcut, ipcMain } from 'electron';
import * as path from 'path';

let mainWindow: BrowserWindow | null = null;

const isDev = !app.isPackaged;

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 800,
    minHeight: 600,
    title: 'Conductor',
    backgroundColor: '#0a0a0f',
    titleBarStyle: 'default',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  if (isDev) {
    mainWindow.loadURL('http://localhost:5173');
    mainWindow.webContents.openDevTools({ mode: 'detach' });
  } else {
    mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'));
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

app.whenReady().then(() => {
  createWindow();

  // Ctrl+N to focus spawn form
  globalShortcut.register('CommandOrControl+N', () => {
    mainWindow?.webContents.send('shortcut:focus-spawn');
  });

  // Escape to clear/blur
  globalShortcut.register('Escape', () => {
    mainWindow?.webContents.send('shortcut:escape');
  });

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  globalShortcut.unregisterAll();
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

// IPC handlers for renderer requests
ipcMain.handle('get-app-version', () => {
  return app.getVersion();
});
