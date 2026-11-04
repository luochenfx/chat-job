package com.chatjob.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chatjob.store.entity.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    /**
     * 交还 AI：清掉接管标记，并把接手时刻置回 NULL。
     *
     * <p>不能走 {@code updateById} —— 它的字段策略是 NOT_NULL，「设成 null 再存」会整列跳过。
     * 表现是界面点了「交还 AI」、库里 {@code taken_over_at} 还留着，看起来像操作没生效。
     * 与 {@code AiConfigMapper.clearProviderId} 同一处理。
     */
    @Update("UPDATE conversation SET auto_reply_paused = 0, taken_over_at = NULL WHERE id = #{id}")
    int releaseTakeover(@Param("id") Long conversationId);
}
