package com.chatjob.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chatjob.store.entity.QualityAssessmentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QualityAssessmentMapper extends BaseMapper<QualityAssessmentEntity> {

    /**
     * 「重点关注」的取数：每段会话只取最新一次评估，只要分数过线的。
     *
     * <p>用子查询取每段会话的最大 id，而不是「把全部读出来再去重」—— 后者在评估次数多了之后
     * 会把整张表读进内存。判断「最新」用 id 而不是时刻：同一秒内的两次评估用时刻分不出先后。
     *
     * <p>写成 {@code SELECT q.*} 而不是 {@code selectMaps}：后者返回的 map 键名受
     * {@code map-underscore-to-camel-case} 影响，读回来的列名会随配置变，是很容易踩空的一处。
     */
    @Select("SELECT q.* FROM quality_assessment q "
            + "JOIN (SELECT conversation_id, MAX(id) AS max_id FROM quality_assessment "
            + "      GROUP BY conversation_id) t ON t.max_id = q.id "
            + "WHERE q.score >= #{scoreLine} "
            + "ORDER BY q.score DESC, q.assessed_at DESC LIMIT #{limit}")
    List<QualityAssessmentEntity> latestAbove(@Param("scoreLine") int scoreLine,
                                              @Param("limit") int limit);
}

