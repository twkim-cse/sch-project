package com.insurance.explainer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

// F-02/F-04: 업로드 후 글자 추출·구조 분석을 비동기로 돌리기 위해 @Async를 쓴다.
@EnableAsync
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
