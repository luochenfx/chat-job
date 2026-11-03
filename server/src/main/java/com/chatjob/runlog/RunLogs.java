package com.chatjob.runlog;

import com.chatjob.store.entity.RunEntity;
import com.chatjob.time.TimeSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 运行日志的装配点：{@code RunLogs.of(run)} 拿到绑定这条 Run 的 {@link RunLog}。
 *
 * <p>为什么每次 {@code of()} 都新建一个而不缓存：它只持有 runId、平台与两个无状态依赖，
 * 创建成本可以忽略；反过来做成单例字段或 {@code ThreadLocal}，会在「给挂起中的 Run 记心跳」
 * 这条路径上写错行 —— {@code RunService.wakeIfWaiting} 此刻手里的 Run 与 worker 线程
 * 正在跑的那条不是同一条，一个隐式的「当前 Run」在这种地方必然出错。
 *
 * <p>因此也不提供「没有 Run 的日志」：{@code run_log.run_id} 允许为空是 V1 的历史形状，
 * 现在的每一次写入都属于某条 Run（值守也只在 Run 内被调用）。真需要一个全局日志出口时，
 * 那是一个新问题，不该由这里的 no-op 变体提前埋下。
 *
 * <p><b>为什么用 {@code JdbcTemplate} 而不是 mapper</b>：一条固定列顺序的 INSERT，没有条件拼装，
 * mapper 只会让这个包为了「造一个假实现」去实现 {@code BaseMapper} 的一整套接口
 * （{@code UnreadTaskMapper} 的注释里记着同一个理由）。走 jdbc 之后这条路径的单测可以用
 * 真实 SQLite 验「到底写进去什么」，与 {@code RunStoreDeliveredTodayTest} 同一种做法。
 */
@Component
public class RunLogs {

    private final JdbcTemplate jdbc;
    private final TimeSource time;

    public RunLogs(JdbcTemplate jdbc, TimeSource time) {
        this.jdbc = jdbc;
        this.time = time;
    }

    /**
     * @throws IllegalArgumentException Run 还没落库（没有 id）或平台缺失。这两种情况下写出来的行
     *                                  要么挂不到任何 Run 上，要么没有平台前缀，都是排查时的假信息。
     */
    public RunLog of(RunEntity run) {
        if (run == null || run.getId() == null) {
            throw new IllegalArgumentException("运行日志必须绑定一条已落库的 Run");
        }
        if (run.getPlatform() == null || run.getPlatform().isBlank()) {
            throw new IllegalArgumentException("运行日志必须带平台，run " + run.getId());
        }
        return new BoundRunLog(run.getId(), run.getPlatform(), jdbc, time);
    }
}
