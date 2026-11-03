package com.chatjob.time;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 生产实现：系统时钟 + 启动时钉死的时区。
 *
 * <p>时区为什么在启动时取一次而不是每次读 {@code ZoneId.systemDefault()}：
 * 一条值守期可能连着跑十几个小时，库里已有的 {@code scan_day}、{@code daily_counter}
 * 是按旧时区写下的分区键。进程中途改时区（或夏令时切换）会让「今天」突然跳变，
 * 表现为计数忽然归零或跨日复位提前触发，而日志里看不出因果。
 * 钉死之后进程内不再自变，代价是「改了系统时区要重启」—— 对单机自用工具这是更安全的一侧。
 *
 * <p>时区会打进启动日志：真出现跨日问题时有据可查。
 */
@Slf4j
@Component
public class SystemTimeSource implements TimeSource {

    /** 启动时钉死的时区，见类注释 */
    private final ZoneId zone;

    public SystemTimeSource() {
        this(ZoneId.systemDefault());
    }

    /** 供测试与「显式指定时区」使用 */
    SystemTimeSource(ZoneId zone) {
        this.zone = zone;
        log.info("时间源就绪：时区 {}（启动时钉死，改系统时区需重启）", zone);
    }

    @Override
    public LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    @Override
    public String stamp() {
        return now().format(TimeFormats.STAMP);
    }

    @Override
    public String format(LocalDateTime moment) {
        return moment.format(TimeFormats.STAMP);
    }

    @Override
    public String today() {
        return now().toLocalDate().format(TimeFormats.DAY);
    }

    @Override
    public Optional<LocalDateTime> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(text, TimeFormats.STAMP));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
