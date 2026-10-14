# FlowPilot production admin console

This is the production Web management UI, built with React, TypeScript, Vite, and Ant Design. The task workspace lazy-loads Monaco Editor for writing Python source. `demo/` is a legacy prototype and is not part of this app's build or deployment.

## Local development

```bash
npm ci
npm run dev
```

Vite serves the console on port `8000` and proxies `/api/*` to `http://127.0.0.1:8001` by default. `make dev` starts it with the API port configured from `FLOWPILOT_API_PORT`.

## Validation and production build

```bash
npm run typecheck
npm test
npm run build
npm audit
```

The build output is `dist/`. `console/Dockerfile` builds the locked frontend and serves the static bundle from Nginx. Nginx serves SPA routes and proxies `/api/*` to FastAPI on the private Compose network.

The browser accepts server-authenticated administrator accounts only. The remember-me choice selects browser local storage; otherwise the bearer session stays in session storage. Do not use the console on a shared device with remember-me enabled.

The Monaco editor loads source only through the authenticated task-access API. It creates new immutable script versions; publishing calculates SHA-256 in the browser and the API verifies the object size and digest before completing an S3 upload. User scripts are never executed by the browser, API, or console host. Device execution still follows server authorization and Android system permissions.

Unpublished editor drafts are currently kept only in the current browser tab's `sessionStorage`. They are not server-backed or encrypted and are cleared by the browser when the tab session ends. Avoid putting secrets in script source; server-side durable drafts and cross-browser E2E coverage remain follow-up work.
