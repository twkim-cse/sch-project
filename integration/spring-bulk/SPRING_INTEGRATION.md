# Spring lossless extraction integration

Review branch: `fix/spring-lossless-extract`, based on main `49126ec` (PR #17 merged).
This branch has not been deployed to teammates.

## Exact 413 cause and fix

Previously Spring sent the whole clauses array to one `/ai/extract` HTTP request. New Dental's 1,472-clause actual parsed JSON reproduces `single_clause_exceeds_input_budget` (37,356-byte single input). Requests can also fail `request_exceeds_batch_limit` when internal batches exceed40. Candidate filtering alone cannot solve either case.

Spring AiClient now orchestrates `/ai/extract/plan` -> each `parts[].request` to `/ai/extract` -> `/ai/extract/merge` after all required parts succeed. This is FULL lossless mode, not candidate-plan/merge. It preserves source text and metadata, uses planner fragments for oversized clauses, validates identities/byte limits, and removes the old six-whole-source fallback that could hide failure as NOT_FOUND.

AI limits remain25 clauses/batch,24,000 input bytes (including reserve2,048),40 internal batches/request. Spring checks each part before dispatch. Source reconstruction, original IDs and fragment mapping are validated by the AI planner/merger.

## Retry, time and status

Spring retains async AnalysisRunner/status polling. One active extraction job; sequential parts; HTTP start interval12s. Plan/merge30s, part420s, extraction cumulative4h, max600 HTTP attempts, max2 HTTP attempts/part including explicit resume,32MB successful result cache,32 jobs. These limits do not cover all teammates sharing the same Gemini project.

413/422/quote validation failures are not replayed with identical input. Merge409 requires missing-part inspection. Transient429/503/504 and selected502 codes allow operator resume after cooldown; no immediate whole-request retry. Unknown network/in-flight outcomes do not auto-replay. AI extract disables SDK retries, caps generation attempts at3, uses exponential backoff+jitter, Retry-After and503 cooldown. Existing explain stabilization remains intact.

Existing QUEUED/RUNNING/DONE/FAILED status contract is unchanged. `/api/analyses/{id}/progress` exposes PARTIAL, completed/failed ranges and resumable; POST `/resume` is operator-only workflow, with no new frontend resume UI. `/result` returns409 before DONE. Unprocessed ranges are not NOT_FOUND or risk-free.

Explain HTTP calls contain two items sequentially, with separate generation/review. Review fail or final number mismatch remains QUOTE_ONLY. Validated explain responses are persisted and reused. See [durable recovery](DURABLE_RECOVERY.md).

## Runtime settings

- Deployment targets Java21 and Python3.12. Local Java build/testing uses JDK23 with release21; actual Java21 runtime remains unverified.
- Compose backend uses `AI_BASE_URL=http://ai:8001`; local backend uses `http://127.0.0.1:8001`.
- Real parsing requires `MOCK_DOCUMENTS=false`; Compose defaults true.
- Set `GEMINI_MODEL=gemini-3.6-flash` and existing credentials in the AI runtime environment, never commit .env. B recheck stays OFF.
- `AI_CHECKPOINT_DIR` defaults to `.local-state`. Compose backend mounts `analysis-state:/app/.local-state`. One Spring writer per directory. Parsed source/quotes are private plaintext: restrict storage permissions.
- Review/merge must precede rebuilding and restarting BOTH AI and Spring. No deployment/merge is performed by this work.

## Offline reproduction (zero Gemini)

AI directory:

```powershell
python -m unittest discover -p "test_*.py" -q
python tools/mock_integration_server.py --port 18081
```

Use `--port 18081` (a separate argument) when starting the server.
In a second terminal, backend directory:

```powershell
$env:DENTAL_EXTRACT_JSON = 'C:/local-only/spring_generated_extract_request.json'
$env:DENTAL_MOCK_AI_URL = 'http://127.0.0.1:18081'
./gradlew.bat test bootJar
```

Without local DENTAL_EXTRACT_JSON, the opt-in real-data Mock test skips; synthetic fixtures still run. Live tests require their separate explicit opt-in; do not set live flags during offline regression. Keep HTTP body debug logging disabled.

## Evidence and remaining limitations

- Previous actual PDF upload/parsing:256 pages,1,472 clauses. Actual small extract/explain/review succeeded; this is not a full77-part success.
- Lossless plan:1,479 fragments,77parts,max25 clauses/23,945bytes. Primary reconstruction, metadata and source SHA verified.
- Offline full JSON: all77 Mock parts200,merge200,missing merge409; actual Spring HTTP -> Mock extract/review -> storage/result verified. No real generation in PR preparation.
- User-confirmed free quota: RPM5,TPM250,000,RPD20. Extract77 alone exceeds RPD. Bulk live run is deferred; no billing/model change.
- Normal call minimum is77+2E (E extractions), plus retries/regeneration/number retries. Start spacing alone needs912s; model time adds more.
- Full77 Gemini run, final React live E2E, Docker build/run, actual Java21 runtime remain unverified.
- Parsing quality still needs human review:91 identical-normalized-text groups,87 missing titles. No source deduplication or correction is performed; in-range page metadata does not prove correct article/rider assignment.
