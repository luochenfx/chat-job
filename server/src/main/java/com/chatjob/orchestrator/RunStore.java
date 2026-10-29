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

    public Optional<RunEntity> active(String platform) {
        List<RunEntity> list = runMapper.selectList(Wrappers.<RunEntity>lambdaQuery()
                .eq(RunEntity::getPlatform, platform)
                .in(RunEntity::getStatus, List.of(RunStatus.RUNNING.name(), RunStatus.PAUSED.name()))
                .orderByDesc(RunEntity::getId));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
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
