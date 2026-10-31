package com.chatjob.web;

import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误响应。自用工具不需要 RFC 7807，只需要"前端能把这句话显示出来"。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(com.chatjob.driver.BlockedException.class)
    public ResponseEntity<Map<String, Object>> blocked(com.chatjob.driver.BlockedException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.kind() + ": " + e.getMessage());
    }

    /**
     * 静态资源备用处理器（WebConfig 里那条 /**）在找不到资源、又不属于 SPA 回落范围时
     * 抛 NoResourceFoundException。它必须排在 Exception 之前被单独接住，否则一个拼错的
     * 接口路径会被包成 500，前端提示变成"服务器错误"，排查方向直接跑偏。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "路径不存在: /" + e.getResourcePath());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
    }

    /**
     * 客户端断开。
     *
     * <p>SSE 长连接（/api/runs/{id}/events）在浏览器关页面或重连之后，服务端还会继续推送
     * 心跳，Tomcat 因此抛 {@code IOException: 你的主机中的软件中止了一个已建立的连接}。
     * 这类异常会被 Spring 的 async error dispatch 送回 MVC，落到异常处理器上——
     * 此时响应头已经是 {@code text/event-stream} 且已提交，再按常规写一段 JSON 只会抛
     * {@code HttpMessageNotWritableException}，一条断连刷出两条 ERROR，把真正的故障盖住。
     *
     * <p>所以这里只对"连接已不可用"的响应放行，其余 IOException 仍按 500 处理。
     */
    @ExceptionHandler({IOException.class, AsyncRequestNotUsableException.class})
    public ResponseEntity<Map<String, Object>> clientGone(Exception e, HttpServletResponse response) {
        if (response.isCommitted() || isEventStream(response)) {
            log.debug("客户端已断开，忽略推送失败: {}", e.getMessage());
            return null;
        }
        log.error("接口异常", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private static boolean isEventStream(HttpServletResponse response) {
        String contentType = response.getContentType();
        return contentType != null && contentType.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("接口异常", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("message", message == null ? "未知错误" : message);
        return ResponseEntity.status(status).body(out);
    }
}
