# =============================================================================
#  Storefront image.
#
#  One image, every environment. The API address is NOT baked in: nginx in this
#  image proxies /api, /oauth2 and /login/oauth2 to whatever `API_UPSTREAM` points
#  at when the container starts, so the bundle only ever makes same-origin calls
#  and needs to know nothing about where the backend lives.
#
#  That is also the only arrangement social sign-in works under. The refresh
#  cookie is SameSite=Strict and set by the backend — served from a different
#  hostname than the storefront, the browser will not send it back, and the user
#  completes the whole provider flow only to arrive signed out.
# =============================================================================

FROM node:22-alpine AS build
WORKDIR /build

# Dependencies first so a source-only change reuses the cached layer.
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund || npm install --no-audit --no-fund

COPY . .
# Empty on purpose. `api.ts` falls back to same-origin when this is unset, which
# is what routes every call into the nginx proxy below. Setting it to the API's
# public URL would send the browser cross-origin and break the cookie.
ARG VITE_API_BASE_URL=
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# -----------------------------------------------------------------------------
FROM nginx:1.27-alpine AS runtime

COPY --from=build /build/dist /usr/share/nginx/html

# Rendered at container start. The official image runs envsubst over everything
# in /etc/nginx/templates and writes the result into /etc/nginx/conf.d, which is
# how one image serves localhost, staging and production without a rebuild.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

# Where the API actually is. Overridden per environment — on Render this comes
# from `fromService`, under docker compose it is the `api` service by name.
# host:port, no scheme — the template supplies http://. Render's `hostport`
# property has exactly this shape, so the two line up without massaging.
ENV API_UPSTREAM=api:8080
# Extra origins for the CSP's connect-src, for a deployment that calls the API
# cross-origin instead of proxying. Empty because this image proxies.
ENV API_CONNECT_SRC=""

EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget -qO- http://localhost/ >/dev/null || exit 1
