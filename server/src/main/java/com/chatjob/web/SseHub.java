package com.chatjob.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 上行通道。
 *
 * <p>只推<b>状态与计数</b>，日志走独立接口 —— 混在一起会让进度面板在日志密集时
 * 抖动、重连后还要回放大量文本。
 *
 * <p>重连靠 {@code Last-Event-ID} + 内存 ring buffer（最近 500 条），不做长期补发：
 * 需要长期追溯的日志本来就已经落库（run_log）。
 */
@Slf4j
@Component
public class SseHub {

    private static final int BUFFER_SIZE = 500;

    public record Event(long id, Long runId, String type, Object data) {
    }

    private final Deque<Event> buffer = new ArrayDeque<>(BUFFER_SIZE);
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    public SseEmitter subscribe(Long runId, Long lastEventId) {
        SseEmitter emitter = new SseEmitter(0L); // 永不超时
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);

        // 补发：只补该 run 在断点之后的事件
        long from = lastEventId == null ? Long.MAX_VALUE : lastEventId;
        if (lastEventId != null) {
            synchronized (buffer) {
                for (Event event : buffer) {
                    if (event.id() > from && match(event, runId)) {
                        send(emitter, event);
                    }
                }
            }
        }
        try {
            emitter.send(SseEmitter.event().name("ping").data(Map.of("ts", System.currentTimeMillis())));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public void publish(Long runId, String type, Object data) {
        Event event = new Event(seq.incrementAndGet(), runId, type, data);
        synchronized (buffer) {
            buffer.addLast(event);
            while (buffer.size() > BUFFER_SIZE) {
                buffer.removeFirst();
            }
        }
        for (SseEmitter emitter : new ArrayList<>(emitters)) {
            send(emitter, event);
        }
    }

    /** 全局事件（登录态、driver 状态），不进 ring buffer */
    public void publishGlobal(String type, Object data) {
        Event event = new Event(seq.incrementAndGet(), null, type, data);
        for (SseEmitter emitter : new ArrayList<>(emitters)) {
            send(emitter, event);
        }
    }

    private void send(SseEmitter emitter, Event event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.id()))
                    .name(event.type())
                    .data(event.data()));
        } catch (Exception e) {
            // 客户端断开是常态，静默移除即可
            emitters.remove(emitter);
        }
    }

    private static boolean match(Event event, Long runId) {
        return runId == null || event.runId() == null || runId.equals(event.runId());
    }

    @Scheduled(fixedDelay = 30_000)
    public void heartbeat() {
        for (SseEmitter emitter : new ArrayList<>(emitters)) {
            try {
                emitter.send(SseEmitter.event().name("ping").data(Map.of("ts", System.currentTimeMillis())));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    public int subscriberCount() {
        return emitters.size();
    }
}
