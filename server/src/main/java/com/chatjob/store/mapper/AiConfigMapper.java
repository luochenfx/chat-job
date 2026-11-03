package com.chatjob.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chatjob.store.entity.AiConfigEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiConfigMapper extends BaseMapper<AiConfigEntity> {

    /**
     * 把 {@code provider_id} 置空。
     *
     * <p>为什么不用 {@code updateById}：它的字段策略是 {@code NOT_NULL}，把字段设成 null
     * 会被整列跳过 —— 「解绑模型」因此永远写不进去，而接口仍然返回成功。
     * 一条显式 UPDATE 比给实体加 {@code updateStrategy = ALWAYS} 便宜：后者会让今后每一次
     * {@code updateById} 都带上这一列，包括那些只想改别的字段的调用。
     */
    @Update("UPDATE ai_config SET provider_id = NULL, updated_at = #{updatedAt} WHERE id = #{id}")
    int clearProviderId(@Param("id") Long id, @Param("updatedAt") String updatedAt);
}
