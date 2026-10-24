import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

// 프론트엔드는 백엔드를 직접(localhost:8080) 부르지 않고
// 항상 상대 경로 /api 를 사용한다. 실제 대상 주소는 이 프록시가 결정한다.
// 로컬 실행: http://localhost:8080 (기본값)
// docker-compose 실행: http://backend:8080 (PROXY_TARGET 환경변수로 주입)
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const proxyTarget = env.PROXY_TARGET || "http://localhost:8080";

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
