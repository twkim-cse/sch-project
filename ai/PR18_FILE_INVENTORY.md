# PR18 exact file ownership inventory

Original PR18 commit: `8d7c92748f2932be6ad63b98b585f9b83b75d3a1` (49 files).

This document is an inventory, not a deletion plan. Original PR18 and its worktree remain intact.

## Retain: AI implementation, AI tests and tools (18)

- `ai/.dockerignore`
- `ai/Dockerfile`
- `ai/README_candidate_filter.md`
- `ai/README_extract_resilience.md`
- `ai/app.py`
- `ai/bulk_http.py`
- `ai/bulk_parallel.py`
- `ai/candidate_filter.py`
- `ai/extract_planning.py`
- `ai/extract_resilience.py`
- `ai/test_candidate_refinement.py`
- `ai/test_explain.py`
- `ai/test_explain_stability.py`
- `ai/test_extract_batching.py`
- `ai/test_extract_recheck.py`
- `ai/test_extract_resilience.py`
- `ai/tools/mock_integration_server.py`
- `ai/tools/offline_bulk_audit.py`

## Do not transfer: backend-owned implementation and tests (25)

- `backend/.dockerignore`
- `backend/src/main/java/com/insurance/explainer/analysis/AiClient.java`
- `backend/src/main/java/com/insurance/explainer/analysis/AiModelIdentity.java`
- `backend/src/main/java/com/insurance/explainer/analysis/AnalysisController.java`
- `backend/src/main/java/com/insurance/explainer/analysis/AnalysisRunner.java`
- `backend/src/main/java/com/insurance/explainer/analysis/AnalysisService.java`
- `backend/src/main/java/com/insurance/explainer/analysis/AnalysisStore.java`
- `backend/src/main/java/com/insurance/explainer/analysis/BulkExtractCoordinator.java`
- `backend/src/main/java/com/insurance/explainer/analysis/DurableExplainCalls.java`
- `backend/src/main/java/com/insurance/explainer/document/DocumentStore.java`
- `backend/src/main/java/com/insurance/explainer/persistence/LocalStateFiles.java`
- `backend/src/main/java/com/insurance/explainer/persistence/LocalStateLease.java`
- `backend/src/main/resources/application.yml`
- `backend/src/test/java/com/insurance/explainer/analysis/AnalysisControllerIntegrationTest.java`
- `backend/src/test/java/com/insurance/explainer/analysis/AnalysisDeletionTest.java`
- `backend/src/test/java/com/insurance/explainer/analysis/AnalysisServiceTest.java`
- `backend/src/test/java/com/insurance/explainer/analysis/BulkExtractCoordinatorTest.java`
- `backend/src/test/java/com/insurance/explainer/analysis/DentalLiveSmallIntegrationTest.java`
- `backend/src/test/java/com/insurance/explainer/analysis/DentalOfflineIntegrationTest.java`
- `backend/src/test/java/com/insurance/explainer/analysis/DentalPdfUploadTest.java`
- `backend/src/test/java/com/insurance/explainer/analysis/DurableExplainCallsTest.java`
- `backend/src/test/java/com/insurance/explainer/analysis/DurableStoresTest.java`
- `backend/src/test/java/com/insurance/explainer/analysis/SpringRestartRecoveryTest.java`
- `backend/src/test/java/com/insurance/explainer/analysis/StateProcessProbe.java`
- `backend/src/test/resources/application.properties`

## Coordinate separately: shared runtime settings (2)

- `.gitignore`
- `docker-compose.yml`

## Preserve in PR18 only; replace with AI contract documentation (4)

- `integration/spring-bulk/DURABLE_RECOVERY.md`
- `integration/spring-bulk/EXTRACT_413_GUIDE.md`
- `integration/spring-bulk/PR_VALIDATION.md`
- `integration/spring-bulk/SPRING_INTEGRATION.md`

## Additional AI-only preparation files

- `ai/.gitignore` (AI-scoped privacy rules only; existing exclusions preserved)
- `ai/test_ai_scope_contract.py`
- `ai/README_extract_api_contract.md`
- `ai/PR18_FILE_SCOPE.md`
- `ai/PR18_FILE_INVENTORY.md`

No backend/frontend source, root .gitignore or Compose changes are transferred.
