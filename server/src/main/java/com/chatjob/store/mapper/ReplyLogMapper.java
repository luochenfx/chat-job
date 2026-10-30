package com.chatjob.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chatjob.store.entity.ReplyLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReplyLogMapper extends BaseMapper<ReplyLogEntity> {

    /**
     * 「值守发简历数」当日条数（ADR-0004 第 4 条：唯一数据源是 {@code reply_log} 的 {@code REPLY_RESUME}）。
     *
     * <p>这里必须 JOIN 会话取平台，而不是只按时间数：两平台并行时同一个自然日会有两份
     * 「值守发简历数」，只按时间数会让两边读到同一个数字。{@code conversation_id} 为空的行
     * 只出现在「找不到会话记录」的异常路径上，宁可少算也不把它错记到某个平台名下。
     *
     * <p>时间用左闭右开区间而不是 {@code date(created_at) = ...}：后者对列做函数运算，
     * {@code idx_reply_log_action} 用不上。
     */
    @Select("SELECT COUNT(*) FROM reply_log l JOIN conversation c ON c.id = l.conversation_id "
            + "WHERE c.platform = #{platform} AND l.action = 'REPLY_RESUME' "
            + "AND l.created_at >= #{from} AND l.created_at < #{to}")
    long countResumeOnRequest(@Param("platform") String platform,
                              @Param("from") String from,
                              @Param("to") String to);
}
