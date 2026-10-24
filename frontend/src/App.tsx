import { useEffect, useState } from "react";
import { fetchHealth, type HealthResponse } from "./api/health";

export default function App() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchHealth()
      .then(setHealth)
      .catch((e) => setError(e.message));
  }, []);

  return (
    <main style={{ fontFamily: "sans-serif", padding: "2rem" }}>
      <h1>보험 약관 위험 조항 해설 서비스</h1>
      <h2>서버 상태</h2>
      {error && <p style={{ color: "red" }}>오류: {error}</p>}
      {!error && !health && <p>확인 중...</p>}
      {health && (
        <ul>
          <li>Backend: {health.backend}</li>
          <li>AI: {health.ai}</li>
        </ul>
      )}
    </main>
  );
}
