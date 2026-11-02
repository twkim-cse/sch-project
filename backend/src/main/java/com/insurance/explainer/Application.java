package com.insurance.explainer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// F-02/F-04: 업로드 후 글자 추출·구조 분석을 비동기로 돌리기 위해 @Async를 쓴다.
// F-21: 보관 기한이 지난 문서를 주기적으로 지우기 위해 @Scheduled를 쓴다.
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
