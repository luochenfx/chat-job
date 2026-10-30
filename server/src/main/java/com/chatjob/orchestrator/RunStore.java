package com.chatjob.orchestrator;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.CombinationRunEntity;
import com.chatjob.store.entity.DailyCounterEntity;
import com.chatjob.store.entity.RunEntity;
import com.chatjob.store.entity.RunLogEntity;
import com.chatjob.store.mapper.CombinationRunMapper;
import com.chatjob.store.mapper.DailyCounterMapper;
import com.chatjob.store.mapper.RunLogMapper;
import com.chatjob.store.mapper.RunMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/** Run / 组合队列 / 日志 / 当日计数 的读写。 */
@Slf4j
@Component
public class RunStore {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RunMapper runMapper;
    private final CombinationRunMapper combinationMapper;
    private final RunLogMapper runLogMapper;
    private final DailyCounterMapper dailyCounterMapper;
    private final JdbcTemplate jdbc;

    public RunStore(RunMapper runMapper,
                    CombinationRunMapper combinationMapper,
                    RunLogMapper runLogMapper,
                    DailyCounterMapper dailyCounterMapper,
                    JdbcTemplate jdbc) {
        this.runMapper = runMapper;
        this.combinationMapper = combinationMapper;
        this.runLogMapper = runLogMapper;
        this.dailyCounterMapper = dailyCounterMapper;
        this.jdbc = jdbc;
    }

    public static String today() {
        return LocalDate.now().format(DAY);
    }

    // ---------------------------------------------------------------- Run

    public RunEntity create(String platform, String mode) {
        RunEntity run = new RunEntity();
        run.setPlatform(platform);
        run.setStatus(RunStatus.CREATED.name());
        run.setMode(mode);
        run.setCombinationIndex(0);
        run.setScrollRound(0);
        run.setLastCardIndex(0);
        run.setDeliveredCount(0);
        run.setEvaluatedCount(0);
        run.setCreatedAt(ConfigStore.now());
        runMapper.insert(run);
        return run;
    }

    /**
     * 未终结的 Run。等待值守时段的那条也算在内：它虽然没在跑，但已经占着「这一轮」，
     * 不能让人在等的时候再点一次启动造出第二条。待命态同理 —— 它不但占着这一轮，
     * 还占着浏览器档案。
     */
    public Optional<RunEntity> active(String platform) {
        List<RunEntity> list = runMapper.selectList(Wrappers.<RunEntity>lambdaQuery()
                .eq(RunEntity::getPlatform, platform)
                .in(RunEntity::getStatus, List.of(
                        RunStatus.RUNNING.name(),
                        RunStatus.PAUSED.name(),
                        RunStatus.STANDBY.name(),
                        RunStatus.WAITING_DUTY.name()))
                .orderByDesc(RunEntity::getId));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 正在等值守时段的那条（至多一条，由 uk_run_running 部分唯一索引保证） */
    public Optional<RunEntity> waiting(String platform) {
        List<RunEntity> list = runMapper.selectList(Wrappers.<RunEntity>lambdaQuery()
                .eq(RunEntity::getPlatform, platform)
                .eq(RunEntity::getStatus, RunStatus.WAITING_DUTY.name())
                .orderByAsc(RunEntity::getId));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 进程刚起来时「没有任何线程持有」的 Run：服务被杀或崩溃会把这些状态留在库里。
     * 单进程部署下，启动那一刻必然不持有任何线程，所以它们全都是孤儿。
     *
     * <p>待命态（STANDBY）也在这里：重启后浏览器是重新拉起的，页面可能停在验证码、
     * 半截导航或登录失效，自动接管的出错方式比「明确告诉你中断了」更糟。
     * 挂起态（WAITING_DUTY）不在此列 —— 它一个页面都没碰过，恢复没有风险。
     */
    public List<RunEntity> orphaned() {
        return runMapper.selectList(Wrappers.<RunEntity>lambdaQuery()
                .in(RunEntity::getStatus, List.of(
                        RunStatus.CREATED.name(),
                        RunStatus.RUNNING.name(),
                        RunStatus.PAUSED.name(),
                        RunStatus.STANDBY.name()))
                .orderByAsc(RunEntity::getId));
    }

    /**
     * 只更新「现在在干什么」与「上次动是什么时候」。
     *
     * <p>不落日志表：卡片间隔若每张卡打两行日志，跑一天就是几百行噪音，
     * 而这两列在界面上只占同一个格子。一次动作一条 UPDATE，成本可以忽略。
     */
    public void touchActivity(Long runId, String action) {
        jdbc.update("UPDATE run SET current_action = ?, last_activity_at = ? WHERE id = ?",
                action, ConfigStore.now(), runId);
    }

    /** 挂起等待值守时段：不写 startedAt（真正开跑时 markRunning 才写） */
    public void markWaiting(RunEntity run, String message) {
        run.setStatus(RunStatus.WAITING_DUTY.name());
        run.setMessage(message);
        runMapper.updateById(run);
    }

    /**
     * 进入值守待命：扫描停下，但这一轮还在、浏览器还持有。
     *
     * <p>刻意不清 {@code startedAt}、不写 {@code finishedAt}——它不是一次结束，
     * 界面上「已运行 3 小时 20 分」的计时不该因为扫描收工而清零。
     */
    public void markStandby(RunEntity run, String message) {
        run.setStatus(RunStatus.STANDBY.name());
        run.setTerminalReason(null);
        run.setMessage(message);
        runMapper.updateById(run);
    }

    /**
     * 只改状态。暂停恢复用：从 STANDBY 暂停的，恢复时必须回 STANDBY 而不是 RUNNING，
     * 否则一恢复就重新开扫，用户明明只想让值班的人先歇一下。
     */
    public void markStatus(RunEntity run, RunStatus status) {
        run.setStatus(status.name());
        runMapper.updateById(run);
    }

    public RunEntity get(Long id) {
        return runMapper.selectById(id);
    }

    public List<RunEntity> recent(int limit) {
        return runMapper.selectList(Wrappers.<RunEntity>lambdaQuery()
                .orderByDesc(RunEntity::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    public void update(RunEntity run) {
        runMapper.updateById(run);
    }

    public void markRunning(RunEntity run) {
        run.setStatus(RunStatus.RUNNING.name());
        run.setStartedAt(ConfigStore.now());
        runMapper.updateById(run);
    }

    public void finish(RunEntity run, RunStatus status, String reason, String message) {
        run.setStatus(status.name());
        run.setTerminalReason(reason);
        run.setMessage(message);
        run.setFinishedAt(ConfigStore.now());
        runMapper.updateById(run);
        log.info("Run {} 结束: {} / {} / {}", run.getId(), status, reason, message);
    }

    public void checkpoint(RunEntity run, int combinationIndex, int scrollRound, int lastCardIndex) {
        run.setCombinationIndex(combinationIndex);
        run.setScrollRound(scrollRound);
        run.setLastCardIndex(lastCardIndex);
        runMapper.updateById(run);
    }

    // ---------------------------------------------------------------- 组合队列

    public void saveCombinations(List<CombinationRunEntity> combos) {
        for (CombinationRunEntity combo : combos) {
            combinationMapper.insert(combo);
        }
    }

    public List<CombinationRunEntity> combinations(Long runId) {
        return combinationMapper.selectList(Wrappers.<CombinationRunEntity>lambdaQuery()
                .eq(CombinationRunEntity::getRunId, runId)
                .orderByAsc(CombinationRunEntity::getSeq));
    }

    public void updateCombination(CombinationRunEntity combo) {
        combinationMapper.updateById(combo);
    }

    /**
     * 把这一轮的所有组合复位成「还没扫过」。
     *
     * <p>跨自然日恢复扫描前必须调它：昨天扫完的组合队列表现在状态还是 DONE，
     * 原样再跑一遍只会全部跳过，第二天一个岗位都不投，日志上看不出任何异常。
     *
     * <p>连同 cardsSeen / startedAt / finishedAt 一起清，因为界面上每个组合都显示
     * 「几张卡、多久跑完」，留着昨天的数字会让人以为今天扫过了。
     */
    public void resetCombinations(Long runId) {
        jdbc.update("""
                UPDATE combination_run
                   SET status = 'PENDING', cards_seen = 0, evaluated = 0, delivered = 0,
                       started_at = NULL, finished_at = NULL
                 WHERE run_id = ?
                """, runId);
    }

    // ---------------------------------------------------------------- 日志

    public void log(Long runId, String level, String message) {
        RunLogEntity entity = new RunLogEntity();
        entity.setRunId(runId);
        entity.setLevel(level);
        entity.setMessage(message);
        entity.setCreatedAt(ConfigStore.now());
        runLogMapper.insert(entity);
    }

    public List<RunLogEntity> logs(Long runId, long afterId, int limit) {
        return runLogMapper.selectList(Wrappers.<RunLogEntity>lambdaQuery()
                .eq(RunLogEntity::getRunId, runId)
                .gt(RunLogEntity::getId, afterId)
                .orderByAsc(RunLogEntity::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    // ---------------------------------------------------------------- 当日计数

    public DailyCounterEntity counter(String platform) {
        DailyCounterEntity existing = dailyCounterMapper.selectOne(
                Wrappers.<DailyCounterEntity>lambdaQuery()
                        .eq(DailyCounterEntity::getPlatform, platform)
                        .eq(DailyCounterEntity::getDay, today()));
        if (existing != null) {
            return existing;
        }
        DailyCounterEntity created = new DailyCounterEntity();
        created.setPlatform(platform);
        created.setDay(today());
        created.setDelivered(0);
        created.setEvaluated(0);
        dailyCounterMapper.insert(created);
        return created;
    }

    public void incrementDelivered(String platform) {
        upsert(platform, 1, 0);
    }

    public void incrementEvaluated(String platform) {
        upsert(platform, 0, 1);
    }

    /**
     * 原子递增。不能"读-改-写" —— 控制台的查询与 Run 线程的写入会并发，
     * 读改写会丢计数，而计数直接决定配额是否生效。
     */
    private void upsert(String platform, int delivered, int evaluated) {
        jdbc.update("""
                INSERT INTO daily_counter (platform, day, delivered, evaluated)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(platform, day) DO UPDATE SET
                    delivered = delivered + excluded.delivered,
                    evaluated = evaluated + excluded.evaluated
                """, platform, today(), delivered, evaluated);
    }

    /**
     * 今日<b>主动投递</b>数 —— 只数扫描侧发起的沟通（ADR-0004）。
     *
     * <p>数据源只有 {@code daily_counter.delivered}，由 {@code RunService} 在投递确认送达后
     * {@link #incrementDelivered} 累加。<b>不要</b>改成数 {@code delivery} 表：那张表的 SKIPPED、
     * FAILED 与演练记录都不是「投出去了」，而配额只该被真正的投递吃掉。
     *
     * <p>值守里应对方索要发出的简历<b>不在这里</b>，它由 {@code ReplyStore.resumeOnRequestToday()}
     * 从 {@code reply_log} 的 {@code REPLY_RESUME} 单独计数。两者的风控语义相反：
     * 主动外呼太频繁会被判成机器，被动应答是对方先开口。混成一个数字，刹车会先把自己刹死。
     *
     * <p>回归守卫见 {@code RunStoreDeliveredTodayTest}。
     */
    public int deliveredToday(String platform) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(delivered), 0) FROM daily_counter WHERE platform = ? AND day = ?",
                Integer.class, platform, today());
        return value == null ? 0 : value;
    }

    public int evaluatedToday(String platform) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(evaluated), 0) FROM daily_counter WHERE platform = ? AND day = ?",
                Integer.class, platform, today());
        return value == null ? 0 : value;
    }
}
