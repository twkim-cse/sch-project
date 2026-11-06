import react from "@vitejs/plugin-react";
import { defineConfig, loadEnv } from "vite";
import { cwd } from "node:process";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, cwd(), "");

  const proxyTarget =
    env.PROXY_TARGET || env.VITE_API_PROXY_TARGET || "http://localhost:8080";

  return {
    plugins: [react()],

    server: {
      host: true,
      port: 5173,

      proxy: {
        "/api": {
          target: proxyTarget,
          changeOrigin: true,
        },
      },
    },
  };
});
