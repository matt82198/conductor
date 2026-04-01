/** @type {import('tailwindcss').Config} */
export default {
  content: ['./src/**/*.{ts,tsx,html}', './src/index.html'],
  theme: {
    extend: {
      colors: {
        surface: {
          0: '#0a0a0f',
          1: '#12121a',
          2: '#1a1a25',
          3: '#222230',
        },
        accent: {
          green: '#22c55e',
          yellow: '#eab308',
          red: '#ef4444',
          blue: '#3b82f6',
          gray: '#6b7280',
        },
      },
      fontFamily: {
        mono: ['"JetBrains Mono"', '"Fira Code"', 'Consolas', 'monospace'],
      },
    },
  },
  plugins: [],
};
