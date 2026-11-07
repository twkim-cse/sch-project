package com.insurance.explainer.analysis;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.insurance.explainer.analysis.ai.*;
import com.insurance.explainer.persistence.LocalStateFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.*;
import org.springframework.http.*;
import reactor.core.publisher.Mono;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class DurableExplainCallsTest {
    @TempDir Path directory;
    final AtomicReference<String> model=new AtomicReference<>("offline-model");
    WebClient client(){return WebClient.builder().exchangeFunction(r -> Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type","application/json").body("{\"ai_configured\":true,\"model\":\""+model.get()+"\"}").build())).build();}
    LocalStateFiles files(){return new LocalStateFiles(directory);}
    DurableExplainCalls cache(){return new DurableExplainCalls(client(),files());}
    ExplainRequest request(){return new ExplainRequest("a",List.of(new ExplainItemDto("i",1,"synthetic",null,"Synthetic clause.",null)));}
    ExplainResponse response(){return new ExplainResponse(List.of(new CardDto("i","Neutral title","Neutral description.","pass",null)));}
    void expireCooldown() {
        ObjectNode state=(ObjectNode)files().read("a");state.path("entries").forEach(e->((ObjectNode)e).put("retry_after_millis",0));files().write("a",state);
    }
    @Test void successful_explanation_and_review_are_reused_after_restart() {
        AtomicInteger calls=new AtomicInteger();
        cache().call(request(),()->{calls.incrementAndGet();return response();});
        assertThat(cache().call(request(),()->{calls.incrementAndGet();return response();})).isEqualTo(response());
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void different_model_never_reuses_cached_cards() {
        cache().call(request(),this::response);model.set("other-model");
        assertThatThrownBy(()->cache().call(request(),()->{throw new AssertionError("must not send");})).isInstanceOf(AiCallFailedException.class);
    }
    @Test void hint_change_is_a_distinct_generation_request() {
        AtomicInteger calls=new AtomicInteger();var saved=cache();saved.call(request(),()->{calls.incrementAndGet();return response();});
        var hint=new ExplainRequest("a",List.of(new ExplainItemDto("i",1,"synthetic",null,"Synthetic clause.",new RetryHint(List.of("60")))));
        saved.call(hint,()->{calls.incrementAndGet();return response();});assertThat(calls.get()).isEqualTo(2);
    }
    @Test void interrupted_inflight_response_is_not_assumed_failed_or_passed() {
        cache().call(request(),this::response);
        ObjectNode state=(ObjectNode)files().read("a");state.path("entries").forEach(e->((ObjectNode)e).put("state","in_flight"));files().write("a",state);
        assertThat(cache().canResume("a")).isFalse();
        assertThatThrownBy(()->cache().call(request(),()->{throw new AssertionError("must not send");})).isInstanceOf(AiCallFailedException.class);
    }
    @Test void network_outcome_unknown_blocks_repetition_and_does_not_save_exception_text() {
        assertThatThrownBy(()->cache().call(request(),()->{throw new IllegalStateException("SECRET");})).isInstanceOf(AiCallFailedException.class).hasMessageNotContaining("SECRET");
        assertThat(cache().canResume("a")).isFalse();assertThat(files().read("a").toString()).doesNotContain("SECRET");
    }
    @Test void known_transient_error_waits_for_cooldown_and_stays_bounded_across_restart() {
        AtomicInteger count=new AtomicInteger();
        java.util.function.Supplier<ExplainResponse> action=()->{count.incrementAndGet();throw WebClientResponseException.create(503,"SECRET",HttpHeaders.EMPTY,new byte[0],null);};
        assertThatThrownBy(()->cache().call(request(),action)).isInstanceOf(AiCallFailedException.class);
        assertThat(cache().canResume("a")).isFalse();expireCooldown();assertThat(cache().canResume("a")).isTrue();
        assertThatThrownBy(()->cache().call(request(),action)).isInstanceOf(AiCallFailedException.class);
        expireCooldown();assertThat(cache().canResume("a")).isFalse();
        assertThatThrownBy(()->cache().call(request(),action)).isInstanceOf(AiCallFailedException.class);assertThat(count.get()).isEqualTo(2);
    }
    @Test void review_fail_remains_fail_in_cached_response() {
        ExplainResponse fail=new ExplainResponse(List.of(new CardDto("i","Neutral","Neutral.","fail","Important condition missing.")));
        cache().call(request(),()->fail);assertThat(cache().call(request(),this::response).cards().get(0).review()).isEqualTo("fail");
    }
    @Test void retry_after_is_preserved_across_restart_without_sleep() {
        HttpHeaders headers=new HttpHeaders();headers.add("Retry-After","120");long before=System.currentTimeMillis();
        assertThatThrownBy(()->cache().call(request(),()->{throw WebClientResponseException.create(503,"unavailable",headers,new byte[0],null);}))
                .isInstanceOf(AiCallFailedException.class);
        files().read("a").path("entries").forEach(e->assertThat(e.path("retry_after_millis").asLong()).isGreaterThanOrEqualTo(before+120_000));
        assertThat(cache().canResume("a")).isFalse();
    }
    @Test void invalid_cards_never_become_a_cached_pass() {
        ExplainResponse invalid=new ExplainResponse(List.of(new CardDto("wrong","Neutral","Neutral.","pass",null)));
        assertThatThrownBy(()->cache().call(request(),()->invalid)).isInstanceOf(AiCallFailedException.class);
        assertThat(cache().canResume("a")).isFalse();
    }
}
