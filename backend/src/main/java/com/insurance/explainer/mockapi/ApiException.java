package com.insurance.explainer.mockapi;

import org.springframework.http.HttpStatus;

// 명세서 4장 오류 코드 표의 HTTP 전달 방식을 그대로 던지기 위한 예외.
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException notReady(String message) {
        return new ApiException(HttpStatus.CONFLICT, "NOT_READY", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
