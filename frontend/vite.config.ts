import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    /*
     * OAuth is the one flow that cannot go through `VITE_API_BASE_URL`.
     *
     * Ordinary API calls are absolute — `api.ts` prefixes every path with the API
     * host — but the "Continue with Google" control is a plain anchor to a *relative*
     * `/oauth2/authorization/...`, and deliberately so: sending the browser straight
     * at the API origin sets the refresh cookie on that origin, where the storefront
     * cannot read it, and the sign-in appears to succeed while leaving the customer
     * signed out. Staying first-party is the whole point.
     *
     * In production the host proxies these two prefixes. In development nothing did,
     * so the anchor resolved against the Vite server, hit the SPA fallback, and the
     * button 404'd even with both providers correctly configured on the backend.
     *
     * `changeOrigin: false` with `xfwd: true` is the load-bearing pair. Spring builds
     * its own `redirect_uri` from `{baseUrl}`, resolved through the forwarded headers
     * (`server.forward-headers-strategy: framework`). Rewriting the Host would make it
     * announce `http://localhost:8080/login/oauth2/code/google` to Google, which is not
     * the URL the browser is on and not the one registered in the console — so the
     * provider would reject the callback. Keeping the origin and forwarding the real
     * host makes it announce `http://localhost:5173/...`, which is both.
     */
    proxy: {
      '/oauth2': {
        target: 'http://localhost:8080',
        changeOrigin: false,
        xfwd: true,
      },
      '/login/oauth2': {
        target: 'http://localhost:8080',
        changeOrigin: false,
        xfwd: true,
      },
    },
  },
  build: {
    target: 'es2020',
    sourcemap: false,
    // Two vendor chunks rather than one: React changes rarely, the router more
    // often, and the app constantly. Splitting them means a routine deploy does
    // not invalidate the largest cached file a returning visitor already has.
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom'],
          router: ['react-router-dom'],
        },
      },
    },
    chunkSizeWarningLimit: 700,
  },
})
