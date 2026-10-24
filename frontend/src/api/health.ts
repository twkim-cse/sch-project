export interface HealthResponse {
  backend: "UP" | "DOWN";
  ai: "UP" | "DOWN";
}

// 항상 상대 경로로 호출한다. 실제 백엔드 주소는 Vite 프록시(vite.config.ts)가 결정한다.
export async function fetchHealth(): Promise<HealthResponse> {
  const res = await fetch("/api/health");
  if (!res.ok) {
    throw new Error(`health check failed: ${res.status}`);
  }
  return res.json();
}
