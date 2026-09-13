import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/postcss';
import path from 'node:path';

// Standalone frontend. /api is reverse-proxied to the independently running Java service.
export default defineConfig({
  root: __dirname,
  plugins: [react()],
  css: { postcss: { plugins: [tailwindcss()] } },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 3000,
    strictPort: true,
    // Editors and tooling leave atomic-replace temp dirs (".<name>.<pid>.<uuid>.tmpdir") in src/;
    // watching them crashes the dev server with EBUSY on Windows.
    watch: { ignored: ['**/.*.tmpdir/**', '**/.*.tmp'] },
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: path.resolve(__dirname, 'dist'),
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500,
  },
  preview: {
    port: 3000, strictPort: true,
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } },
  },
});
