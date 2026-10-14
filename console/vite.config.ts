import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  base: "/",
  server: {
    port: 8000,
    strictPort: true,
    proxy: {
      "/api": {
        target: process.env.FLOWPILOT_API_PROXY || "http://127.0.0.1:8001",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ""),
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes("/node_modules/monaco-editor/")) return "monaco";
          if (id.includes("/node_modules/antd/") || id.includes("/node_modules/@ant-design/")) return "antd";
          if (id.includes("/node_modules/react/") || id.includes("/node_modules/react-dom/")) return "react";
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test-setup.ts",
  },
});
