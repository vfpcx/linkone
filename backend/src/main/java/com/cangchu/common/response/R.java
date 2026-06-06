package com.cangchu.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 统一响应体 { code, message, data, traceId, timestamp }
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class R<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;
    private String timestamp;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss+08:00");

    private R() {}

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = 0;
        r.message = "ok";
        r.data = data;
        r.timestamp = LocalDateTime.now().format(FMT);
        return r;
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> error(int code, String message, Object details, String traceId) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        r.data = (T) details;
        r.traceId = traceId;
        r.timestamp = LocalDateTime.now().format(FMT);
        return r;
    }

    public static <T> R<T> error(int code, String message) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        r.timestamp = LocalDateTime.now().format(FMT);
        return r;
    }
}
