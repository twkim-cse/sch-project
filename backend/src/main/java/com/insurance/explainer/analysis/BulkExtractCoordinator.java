package com.insurance.explainer.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.insurance.explainer.analysis.ai.ExtractRequest;
import com.insurance.explainer.analysis.ai.ExtractResponse;
import com.insurance.explainer.persistence.LocalStateFiles;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Full-document lossless orchestration. Candidate plans must use their own merge.
 * Spring's existing async AnalysisRunner owns job status. No Gemini parallelism,
 * no automatic whole-job retries, no partial ExtractResponse on failure.
 * Optional atomic local snapshots survive process restart. The backend's
 * retention/deletion lifecycle owns cleanup; one writer per state directory.
 */
public final class BulkExtractCoordinator {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BulkExtractCoordinator.class);
    private static final Set<String> SAFE_CODES = Set.of(
            "REQUEST_VALIDATION_ERROR", "EXTRACT_INPUT_BUDGET_EXCEEDED", "SERVER_CONFIGURATION_ERROR",
            "GEMINI_MODEL_ERROR", "GEMINI_TIMEOUT", "GEMINI_AUTHENTICATION_ERROR", "GEMINI_RESPONSE_VALIDATION_ERROR",
            "GEMINI_API_ERROR", "INTERNAL_ERROR", "SERVICE_UNAVAILABLE", "BULK_INPUT_BUDGET_EXCEEDED",
            "BULK_UNSPLITTABLE_CLAUSE", "BULK_PLAN_MISMATCH", "BULK_INCOMPLETE", "BULK_RESULT_INVALID",
            "BULK_JOB_BUDGET_EXCEEDED");
    private static final long JOB_NANOS = Duration.ofHours(4).toNanos();
    private static final long RETENTION_NANOS = Duration.ofHours(24).toNanos();
    private static final int MAX_JOBS = 32, MAX_ATTEMPTS = 600;
    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Job> jobs = new HashMap<>();
    private final Semaphore activeJob = new Semaphore(1, true);
    private final long startIntervalNanos;
    private long lastStarted;
    private boolean hasStarted;
    private final LocalStateFiles files;

    public record Progress(String state, int totalParts, int completedParts,
                           java.util.List<String> pendingPartIds, String failedPartId,
                           java.util.List<String> remainingClauseIds, java.util.List<String> failedClauseIds,
                           String errorCode, long retryAfterSeconds, boolean resumable, boolean fullDocumentSuccess) { }

    private static final class Job {
        final String planId;
        final long created = System.nanoTime();
        final Map<String, JsonNode> completed = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, Integer> attempts = new HashMap<>();
        long elapsed;
        long checkpointBytes;
        volatile int failureStatus;
        volatile String failureCode;
        volatile String failedPartId;
        volatile java.util.List<String> partIds = java.util.List.of();
        volatile Map<String, java.util.List<String>> ranges = Map.of();
        volatile boolean merged;
        volatile boolean cancelled;
        volatile long resumeAfterNanos;
        final Object diskLock=new Object();
        ExtractRequest source;
        boolean inFlight;
        long createdMillis=System.currentTimeMillis();
        String modelIdentity;
        Job(String planId) { this.planId = planId; }
    }

    public static final class BulkFailure extends RuntimeException {
        public final int status;
        public final String code;
        long retryAfterSeconds;
        BulkFailure(int status, String code) {
            super("Bulk extraction failed: " + code);
            this.status = status;
            this.code = code;
        }
    }

    public BulkExtractCoordinator(WebClient client) {
        this(client, Duration.ofSeconds(12));
    }

    BulkExtractCoordinator(WebClient client, Duration startInterval) {
        this(client,startInterval,null);
    }

    BulkExtractCoordinator(WebClient client, Duration startInterval, LocalStateFiles files) {
        this.files=files;
        if(files!=null) {hasStarted=true;lastStarted=System.nanoTime();}
        this.startIntervalNanos = startInterval.toNanos();
        // Planning returns request bodies; the default WebClient aggregation
        // budget can be too small. This clone affects only bulk operations.
        this.client = client.mutate().codecs(c -> c.defaultCodecs()
                .maxInMemorySize(40 * 1024 * 1024)).build();
    }

    public ExtractResponse extract(ExtractRequest source) {
        boolean acquired = false;
        try {
            acquired = activeJob.tryAcquire(30, TimeUnit.SECONDS);
            if (!acquired) throw new BulkFailure(503, "BULK_BUSY");
            return extractSerial(source);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BulkFailure(502, "BULK_INTERRUPTED");
        } finally {
            if (acquired) activeJob.release();
        }
    }

    private ExtractResponse extractSerial(ExtractRequest source) {
        long planningStarted = System.nanoTime();
        String modelIdentity=files==null ? null : AiModelIdentity.read(client);
        JsonNode plan = post("/ai/extract/plan", source, Duration.ofSeconds(30));
        if (!plan.path("preservation_verified").asBoolean(false)
                || !plan.path("parts").isArray() || plan.path("parts").size() > 500
                || !plan.path("plan_id").isTextual()) {
            throw new BulkFailure(502, "BULK_PLAN_MISMATCH");
        }
        String planId = plan.path("plan_id").asText();
        Set<String> partIds = new HashSet<>();
        for (JsonNode part : plan.path("parts")) {
            JsonNode request = part.path("request");
            if (!part.path("part_id").isTextual() || !partIds.add(part.path("part_id").asText())
                    || !part.path("input_bytes").isIntegralNumber() || !part.path("input_bytes").canConvertToInt()
                    || part.path("input_bytes").asInt() > 24_000
                    || part.path("input_bytes").asInt() < 2_048
                    || !request.path("clauses").isArray() || request.path("clauses").size() > 25
                    || !request.path("analysis_id").asText().equals(source.analysisId())
                    || !request.path("check_items").equals(mapper.valueToTree(source.checkItems())))
                throw new BulkFailure(502, "BULK_PLAN_MISMATCH");
            try {
                if (mapper.writeValueAsBytes(request).length + 2_048 > 24_000)
                    throw new BulkFailure(502, "BULK_PLAN_MISMATCH");
            } catch (java.io.IOException e) { throw new BulkFailure(502, "BULK_PLAN_MISMATCH"); }
        }
        log.info("AI bulk planned source_clauses={} http_parts={}",source.clauses().size(),partIds.size());
        Job job;
        synchronized (jobs) {
            // Lazy bounded retention. Team must link explicit document deletion
            // and authorization to these checkpoints before production use.
            jobs.entrySet().removeIf(e -> {
                boolean expired=System.currentTimeMillis()-e.getValue().createdMillis>Duration.ofHours(24).toMillis();
                return expired; // F-21 deletion owns disk cleanup; expiry must not reset attempts.
            });
            load(source.analysisId());
            if (!jobs.containsKey(source.analysisId()) && jobs.size() >= MAX_JOBS)
                throw new BulkFailure(503, "BULK_CHECKPOINT_CAPACITY");
            job = jobs.computeIfAbsent(source.analysisId(), k -> new Job(planId));
        }
        synchronized (job) {
            long started = System.nanoTime();
            job.elapsed += started - planningStarted;
            try {
                if (!job.planId.equals(planId)) throw new BulkFailure(422, "BULK_PLAN_MISMATCH");
                if(job.source!=null && !mapper.valueToTree(source).equals(mapper.valueToTree(job.source)))
                    throw new BulkFailure(422,"BULK_PLAN_MISMATCH");
                job.source=source;
                if(job.modelIdentity!=null && !job.modelIdentity.equals(modelIdentity))throw new BulkFailure(422,"BULK_PLAN_MISMATCH");
                job.modelIdentity=modelIdentity;
                for(JsonNode part:plan.path("parts")) {
                    JsonNode saved=job.completed.get(part.path("part_id").asText());
                    if(saved!=null)validatePart(part.path("request"),saved);
                }
                job.partIds = plan.path("parts").findValuesAsText("part_id");
                Map<String, java.util.List<String>> ranges = new LinkedHashMap<>();
                for (JsonNode part : plan.path("parts")) {
                    Set<String> originals = new java.util.LinkedHashSet<>();
                    part.path("request").path("clauses").forEach(c -> {
                        String id = c.path("clause_id").asText();
                        originals.add(plan.path("fragments").path(id).path("original_clause_id").asText(id));
                    });
                    ranges.put(part.path("part_id").asText(),java.util.List.copyOf(originals));
                }
                job.ranges = Map.copyOf(ranges);
                job.merged = false;
                if (job.failureCode != null && !retryable(job.failureStatus, job.failureCode))
                    throw new BulkFailure(job.failureStatus, job.failureCode);
                if (System.nanoTime() < job.resumeAfterNanos) {
                    // Do not re-send a failed part while upstream cooldown is active.
                    BulkFailure cooldown = new BulkFailure(job.failureStatus,job.failureCode);
                    cooldown.retryAfterSeconds = cooldownRemaining(job);
                    throw cooldown;
                }
                job.failureCode = null;
                for (JsonNode part : plan.path("parts")) {
                    if (job.cancelled) throw new BulkFailure(409, "BULK_CANCELLED");
                    String partId = part.path("part_id").asText();
                    if (job.completed.containsKey(partId)) continue;
                    int total = job.attempts.values().stream().mapToInt(Integer::intValue).sum();
                    if (total >= MAX_ATTEMPTS || job.attempts.getOrDefault(partId, 0) >= 2)
                        throw new BulkFailure(502, "BULK_JOB_BUDGET_EXCEEDED");
                    Duration timeout = remaining(job, started, 420);
                    job.failedPartId = partId;
                    awaitStart(job, started);
                    timeout = remaining(job, started, 420);
                    job.attempts.merge(partId, 1, Integer::sum);
                    job.inFlight=true;
                    persist(source.analysisId(),job,started);
                    JsonNode response = post("/ai/extract", part.path("request"), timeout);
                    validatePart(part.path("request"), response);
                    remaining(job, started, 420); // reject responses arriving after the budget
                    long bytes = mapper.writeValueAsBytes(response).length;
                    if (job.checkpointBytes + bytes > 32_000_000)
                        throw new BulkFailure(413, "BULK_CHECKPOINT_BUDGET");
                    job.completed.put(partId, response);
                    job.checkpointBytes += bytes;
                    job.failureCode = null;
                    job.inFlight=false;
                    persist(source.analysisId(),job,started);
                    log.info("AI bulk part completed completed={} total={} input_bytes={}",job.completed.size(),job.partIds.size(),part.path("input_bytes").asInt());
                }
                job.failedPartId = null; // A merge failure is not a failed clause request.
                ObjectNode merge = mapper.createObjectNode();
                merge.set("source_request", mapper.valueToTree(source));
                merge.put("plan_id", planId);
                ArrayNode results = merge.putArray("part_results");
                for (JsonNode part : plan.path("parts")) {
                    String partId = part.path("part_id").asText();
                    if (!job.completed.containsKey(partId)) throw new BulkFailure(409, "BULK_INCOMPLETE");
                    ObjectNode entry = results.addObject();
                    entry.put("part_id", partId);
                    entry.set("response", job.completed.get(partId));
                }
                JsonNode merged = post("/ai/extract/merge", merge, remaining(job, started, 30));
                remaining(job, started, 30);
                validateMergedIds(source, merged);
                ExtractResponse result = mapper.treeToValue(merged, ExtractResponse.class);
                job.merged = true;
                job.failedPartId = null;
                job.failureCode = null;
                persist(source.analysisId(),job,started);
                return result;
            } catch (BulkFailure e) {
                job.inFlight=false;
                job.failureStatus = e.status;
                job.failureCode = e.code;
                if (e.retryAfterSeconds > 0)
                    job.resumeAfterNanos = Math.max(job.resumeAfterNanos,System.nanoTime()+java.time.Duration.ofSeconds(e.retryAfterSeconds).toNanos());
                log.warn("AI bulk failed http_code={} code={} completed={} total={}",e.status,e.code,job.completed.size(),job.partIds.size());
                throw e;
            } catch(LocalStateFiles.StorageFailure e) {
                job.failureStatus=502;job.failureCode="BULK_CHECKPOINT_STORAGE_ERROR";
                throw new BulkFailure(502,"BULK_CHECKPOINT_STORAGE_ERROR");
            } catch (Exception e) {
                // Never include raw exception messages or HTTP bodies.
                job.failureStatus = 502;
                job.failureCode = "BULK_CLIENT_ERROR";
                throw new BulkFailure(502, "BULK_CLIENT_ERROR");
            } finally {
                job.elapsed += System.nanoTime() - started;
                persist(source.analysisId(),job,0);
            }
        }
    }

    private void awaitStart(Job job, long started) {
        remaining(job, started, 270);
        if (hasStarted) {
            long wait = startIntervalNanos - (System.nanoTime() - lastStarted);
            if (wait > 0) {
                try { TimeUnit.NANOSECONDS.sleep(wait); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BulkFailure(502, "BULK_INTERRUPTED");
                }
            }
        }
        remaining(job, started, 270);
        hasStarted = true;
        lastStarted = System.nanoTime();
    }

    public Progress progress(String analysisId) {
        Job job;
        synchronized (jobs) { load(analysisId);job = jobs.get(analysisId); }
        if (job == null) return null;
        // Snapshot must not wait for a long-running job's monitor.
        synchronized (job.completed) {
            var pending = job.partIds.stream().filter(id -> !job.completed.containsKey(id)).toList();
            String state = job.merged ? "COMPLETED" : job.failureCode == null ? "RUNNING"
                    : job.completed.isEmpty() ? "FAILED" : "PARTIAL";
            return new Progress(state, job.partIds.size(), job.completed.size(), pending,
                    job.failedPartId, pending.stream().flatMap(id -> job.ranges.getOrDefault(id,java.util.List.of()).stream()).distinct().toList(),
                    job.failedPartId == null ? java.util.List.of() : job.ranges.getOrDefault(job.failedPartId,java.util.List.of()),
                    job.failureCode, cooldownRemaining(job), job.failureCode != null && cooldownRemaining(job)==0
                    && retryable(job.failureStatus, job.failureCode), job.merged);
        }
    }

    public boolean canResume(String id) {
        Progress progress = progress(id);
        return progress != null && progress.resumable();
    }

    private static long cooldownRemaining(Job job) {
        long nanos=Math.max(0,job.resumeAfterNanos-System.nanoTime());
        return (nanos+999_999_999L)/1_000_000_000L;
    }

    public void forget(String id) {
        synchronized (jobs) {
            Job job = jobs.remove(id);
            if (job != null) synchronized(job.diskLock) {
                job.cancelled = true;
                if(files!=null)files.delete(id);
            }
            else if(files!=null)files.delete(id);
        }
    }

    private void persist(String id,Job job,long started) {
        if(files==null)return;
        synchronized(job.diskLock) {
            if(job.cancelled)return;
            ObjectNode data=mapper.createObjectNode();
            data.put("plan_id",job.planId);data.set("source",mapper.valueToTree(job.source));
            data.put("model",job.modelIdentity);
            data.set("completed",mapper.valueToTree(job.completed));data.set("attempts",mapper.valueToTree(job.attempts));
            data.put("elapsed_nanos",job.elapsed+(started==0 ? 0 : System.nanoTime()-started));
            data.put("failure_status",job.failureStatus);data.put("failure_code",job.failureCode);
            data.put("failed_part_id",job.failedPartId);data.set("part_ids",mapper.valueToTree(job.partIds));
            data.set("ranges",mapper.valueToTree(job.ranges));data.put("merged",job.merged);data.put("in_flight",job.inFlight);
            data.put("created_millis",job.createdMillis);
            data.put("resume_after_millis",System.currentTimeMillis()+TimeUnit.SECONDS.toMillis(cooldownRemaining(job)));
            files.write(id,data);
        }
    }

    private void load(String id) {
        if(files==null || jobs.containsKey(id))return;
        JsonNode data=files.read(id);if(data==null)return;
        if(!data.path("source").isObject() || !data.path("model").isTextual() || !data.path("created_millis").isIntegralNumber()
                || data.path("created_millis").asLong()<=0 || !data.path("elapsed_nanos").isIntegralNumber()
                || !data.path("part_ids").isArray() || !data.path("attempts").isObject()
                || !data.path("completed").isObject() || !data.path("ranges").isObject()
                || !data.path("in_flight").isBoolean() || !data.path("merged").isBoolean())
            throw new LocalStateFiles.StorageFailure();
        if(jobs.size()>=MAX_JOBS)throw new BulkFailure(503,"BULK_CHECKPOINT_CAPACITY");
        ExtractRequest source=files.value(data.path("source"),ExtractRequest.class);
        if(source==null || !id.equals(source.analysisId()) || !data.path("plan_id").isTextual())
            throw new LocalStateFiles.StorageFailure();
        Job job=new Job(data.path("plan_id").asText());job.source=source;
        job.modelIdentity=data.path("model").asText();
        job.createdMillis=data.path("created_millis").asLong();
        job.elapsed=data.path("elapsed_nanos").asLong();
        if(job.elapsed<0)throw new LocalStateFiles.StorageFailure();
        java.util.List<String> ids=new java.util.ArrayList<>();data.path("part_ids").forEach(v->ids.add(v.asText()));
        if(ids.size()>500 || new HashSet<>(ids).size()!=ids.size())throw new LocalStateFiles.StorageFailure();
        job.partIds=java.util.List.copyOf(ids);
        data.path("completed").fields().forEachRemaining(e->{
            if(!ids.contains(e.getKey()))throw new LocalStateFiles.StorageFailure();
            job.completed.put(e.getKey(),e.getValue());
        });
        data.path("attempts").fields().forEachRemaining(e->{
            if(!ids.contains(e.getKey()) || !e.getValue().isIntegralNumber() || e.getValue().asInt()<0 || e.getValue().asInt()>2)
                throw new LocalStateFiles.StorageFailure();
            job.attempts.put(e.getKey(),e.getValue().asInt());
        });
        Map<String,java.util.List<String>> ranges=new LinkedHashMap<>();
        data.path("ranges").fields().forEachRemaining(e->{var values=new java.util.ArrayList<String>();e.getValue().forEach(v->values.add(v.asText()));ranges.put(e.getKey(),values);});
        job.ranges=ranges;job.failureStatus=data.path("failure_status").asInt();
        job.failureCode=data.path("failure_code").isNull()?null:data.path("failure_code").asText();
        job.failedPartId=data.path("failed_part_id").isNull()?null:data.path("failed_part_id").asText();
        job.merged=data.path("merged").asBoolean();
        if(job.merged && !job.completed.keySet().equals(new HashSet<>(ids)))throw new LocalStateFiles.StorageFailure();
        long millis=Math.max(0,data.path("resume_after_millis").asLong()-System.currentTimeMillis());
        job.resumeAfterNanos=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.min(millis,TimeUnit.DAYS.toMillis(365)));
        if(data.path("in_flight").asBoolean()) {job.failureStatus=502;job.failureCode="BULK_CLIENT_ERROR";}
        else if(!job.merged && job.failureCode==null) {job.failureStatus=503;job.failureCode="BULK_INTERRUPTED";}
        if(job.elapsed>JOB_NANOS || System.currentTimeMillis()-job.createdMillis>Duration.ofHours(24).toMillis()) {
            job.failureStatus=502;job.failureCode="BULK_JOB_BUDGET_EXCEEDED";
        }
        try {job.checkpointBytes=mapper.writeValueAsBytes(job.completed).length;}
        catch(Exception e){throw new LocalStateFiles.StorageFailure();}
        if(job.checkpointBytes>32_000_000)throw new LocalStateFiles.StorageFailure();
        jobs.put(id,job);
    }

    private Duration remaining(Job job, long started, int maxSeconds) {
        if (job.cancelled) throw new BulkFailure(409, "BULK_CANCELLED");
        long remaining = JOB_NANOS - job.elapsed - (System.nanoTime() - started);
        if (remaining <= 0) throw new BulkFailure(502, "BULK_JOB_BUDGET_EXCEEDED");
        return Duration.ofNanos(Math.min(Duration.ofSeconds(maxSeconds).toNanos(), remaining));
    }

    private JsonNode post(String path, Object body, Duration timeout) {
        try {
            JsonNode response = client.post().uri(path).bodyValue(body).retrieve()
                    .bodyToMono(JsonNode.class).block(timeout);
            if (response == null) throw new BulkFailure(502, "BULK_RESULT_INVALID");
            return response;
        } catch (WebClientResponseException e) {
            String code = "BULK_HTTP_ERROR";
            try {
                String candidate = mapper.readTree(e.getResponseBodyAsString()).path("code").asText();
                if (SAFE_CODES.contains(candidate)) code = candidate;
            } catch (Exception ignored) { }
            BulkFailure failure = new BulkFailure(e.getStatusCode().value(), code);
            String retryAfter = e.getHeaders().getFirst("Retry-After");
            if (retryable(failure.status,failure.code) && retryAfter != null) {
                try {
                    long seconds=Long.parseLong(retryAfter);
                    if(seconds>=0 && seconds<=2_147_483_647L)failure.retryAfterSeconds=seconds;
                } catch(NumberFormatException ignored) {
                    try {
                        var date=java.time.ZonedDateTime.parse(retryAfter,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                        long seconds=java.time.Duration.between(java.time.Instant.now(),date.toInstant()).getSeconds();
                        if(seconds>0 && seconds<=2_147_483_647L)failure.retryAfterSeconds=seconds+1;
                    } catch(java.time.DateTimeException invalid) { }
                }
            }
            throw failure;
        } catch (BulkFailure e) {
            throw e;
        } catch (Exception e) {
            // Timeout/connection outcome is ambiguous. No blind automatic retry.
            throw new BulkFailure(502, "BULK_CLIENT_ERROR");
        }
    }

    private static boolean retryable(int status, String code) {
        // A subsequent authorized call can resume; this is not an auto retry.
        return status == 429 || status == 503 || status == 504
                || (status == 502 && (code.equals("GEMINI_TIMEOUT") || code.equals("GEMINI_API_ERROR")));
    }

    private static String normalize(String s) { return s.replaceAll("(?U)\\s+", " "); }

    private static void validatePart(JsonNode request, JsonNode response) {
        if (!response.isObject() || response.size() != 1 || !response.path("extractions").isArray())
            throw new BulkFailure(502, "BULK_RESULT_INVALID");
        Map<String, String> texts = new HashMap<>();
        Set<Integer> items = new HashSet<>();
        request.path("clauses").forEach(c -> texts.put(c.path("clause_id").asText(), c.path("text").asText()));
        request.path("check_items").forEach(i -> items.add(i.asInt()));
        for (JsonNode row : response.path("extractions")) {
            String cid = row.path("clause_id").asText();
            String quote = row.path("quote").asText();
            if (row.size() != 3 || !row.path("clause_id").isTextual() || !row.path("quote").isTextual()
                    || !row.path("check_item").isIntegralNumber() || !items.contains(row.path("check_item").asInt())
                    || !texts.containsKey(cid) || quote.isBlank() || !normalize(texts.get(cid)).contains(normalize(quote)))
                throw new BulkFailure(502, "BULK_RESULT_INVALID");
        }
    }

    private void validateMergedIds(ExtractRequest source, JsonNode merged) {
        validatePart(mapper.valueToTree(source), merged);
    }
}
