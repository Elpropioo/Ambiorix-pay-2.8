import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import fs from 'fs';
import {defineConfig} from 'vite';

export default defineConfig(() => {
  return {
    publicDir: 'public',
    plugins: [
      react(),
      tailwindcss(),
      {
        name: 'serve-zip-endpoint',
        configureServer(server) {
          server.middlewares.use((req, res, next) => {
            if (req.url === '/AmbiorixPay-Android-Project.zip' || req.url === '/api/download-zip') {
              const zipPath = path.resolve(__dirname, 'public/AmbiorixPay-Android-Project.zip');
              if (fs.existsSync(zipPath)) {
                const stat = fs.statSync(zipPath);
                res.writeHead(200, {
                  'Content-Type': 'application/zip',
                  'Content-Length': stat.size,
                  'Content-Disposition': 'attachment; filename="AmbiorixPay-Android-Project.zip"',
                  'Cache-Control': 'no-cache',
                });
                fs.createReadStream(zipPath).pipe(res);
                return;
              }
            }
            next();
          });
        },
      },
    ],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, '.'),
      },
    },
    server: {
      // HMR is disabled in AI Studio via DISABLE_HMR env var.
      // Do not modify—file watching is disabled to prevent flickering during agent edits.
      hmr: process.env.DISABLE_HMR !== 'true',
      // Disable file watching when DISABLE_HMR is true to save CPU during agent edits.
      watch: process.env.DISABLE_HMR === 'true' ? null : {},
    },
  };
});
