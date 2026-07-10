import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'node:path'
import fs from 'node:fs'
import type { ProxyOptions } from 'vite'

const backendTarget = process.env.OAT_BACKEND_TARGET || 'http://localhost:8899'

const htmlNoStoreHeaders = {
  'Cache-Control': 'no-store, max-age=0, must-revalidate',
  Pragma: 'no-cache',
  Expires: '0',
}

function hasLocalPublicAsset(url?: string) {
  if (!url) return false
  const requestPath = url.split('?')[0]
  if (!requestPath.startsWith('/css/') && !requestPath.startsWith('/js/') && !requestPath.startsWith('/images/')) {
    return false
  }
  const publicPath = path.resolve(__dirname, 'public', requestPath.slice(1))
  const publicRoot = path.resolve(__dirname, 'public')
  return publicPath.startsWith(publicRoot) && fs.existsSync(publicPath) && fs.statSync(publicPath).isFile()
}

const backendProxy: ProxyOptions = {
  target: backendTarget,
  changeOrigin: true,
  bypass(req) {
    const accept = req.headers.accept || ''
    const method = req.method || 'GET'

    if (method === 'GET' && hasLocalPublicAsset(req.url)) {
      return req.url
    }

    // Vue owns browser page navigations. Only API/form/download calls should be
    // proxied to the Spring Boot backend in dev mode.
    if (method === 'GET' && accept.includes('text/html')) {
      return req.url
    }
    return undefined
  },
}

const backendProxyMap = {
  '/api': backendProxy,
  '/share/api': backendProxy,
  '^/p/[^/]+/map/(home|app|code)/data(/|\\?|$)': backendProxy,
  '^/p/[^/]+/app/[^/]+/oAT\\.key$': backendProxy,
  '^/p/[^/]+/app/probe-alerts/(recent|stream)(/|\\?|$)': backendProxy,
  '^/p/[^/]+/[^/]+/version/(doAdd|setCurrent|delete|report/delete|file/delete|checkGitPull|git/commits|git/commit|git/pull|git/status|git/deleteCode|package/verifyCommit)(/|\\?|$)': backendProxy,
  '/css': backendProxy,
  '/js': backendProxy,
  '/images': backendProxy,
  '/user': backendProxy,
  '/r': backendProxy,
}

function distAssetFallbackPlugin() {
  const contentTypes: Record<string, string> = {
    '.css': 'text/css; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.map': 'application/json; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif': 'image/gif',
    '.webp': 'image/webp',
    '.woff': 'font/woff',
    '.woff2': 'font/woff2',
  }

  return {
    name: 'oat-dist-asset-fallback',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const requestPath = (req.url || '').split('?')[0]
        if (!requestPath.startsWith('/assets/')) {
          next()
          return
        }

        const filePath = path.resolve(__dirname, 'dist', requestPath.slice(1))
        if (!filePath.startsWith(path.resolve(__dirname, 'dist', 'assets')) || !fs.existsSync(filePath)) {
          next()
          return
        }

        res.statusCode = 200
        res.setHeader('Cache-Control', 'no-cache')
        res.setHeader('Content-Type', contentTypes[path.extname(filePath)] || 'application/octet-stream')
        if (req.method === 'HEAD') {
          res.end()
          return
        }
        fs.createReadStream(filePath).pipe(res)
      })
    },
  }
}

export default defineConfig({
  plugins: [vue(), distAssetFallbackPlugin()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    emptyOutDir: false,
    rollupOptions: {
      output: {
        entryFileNames: 'assets/[name].js',
        chunkFileNames: 'assets/[name].js',
        assetFileNames: 'assets/[name][extname]',
        manualChunks(id) {
          if (!id.includes('node_modules')) {
            return undefined
          }
          if (id.includes('/vue-router/') || id.includes('/pinia/')) {
            return 'router-pinia'
          }
          return 'vendor'
        },
      },
    },
  },
  preview: {
    port: 5176,
    headers: htmlNoStoreHeaders,
    proxy: backendProxyMap,
  },
  server: {
    port: 5176,
    headers: htmlNoStoreHeaders,
    proxy: backendProxyMap,
  },
})
