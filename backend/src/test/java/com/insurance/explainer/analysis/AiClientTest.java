package com.insurance.explainer.analysis;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 호출 대기 시간이 고정 5분이 아니라 배치 수에 비례해서 늘어나는지 확인한다
 * (명세서 5장 "호출 규칙" 보강).
 */
class AiClientTest {

    @Test
    void 배치가_적으면_최소_5분을_그대로_쓴다() {
        assertThat(AiClient.batchTimeout(1)).isEqualTo(Duration.ofMinutes(5));
        assertThat(AiClient.batchTimeout(5)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void 배치가_많아지면_배치당_1분씩_늘어난다() {
        assertThat(AiClient.batchTimeout(11)).isEqualTo(Duration.ofMinutes(11));
        assertThat(AiClient.batchTimeout(30)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void 배치가_너무_많으면_상한선에서_멈춘다() {
        assertThat(AiClient.batchTimeout(1000)).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void 배치_수가_0이어도_최소한_1배치로_취급한다() {
        assertThat(AiClient.batchTimeout(0)).isEqualTo(Duration.ofMinutes(5));
    }
}
