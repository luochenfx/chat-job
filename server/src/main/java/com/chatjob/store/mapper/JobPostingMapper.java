package com.chatjob.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chatjob.store.entity.JobPostingEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface JobPostingMapper extends BaseMapper<JobPostingEntity> {

    /**
     * 用会话标题（= HR 名字）反查最近一次投递成功的岗位，供岗位质量判断取岗位信息。
     *
     * <p>这条查询本身就是个折中：{@code conversation.job_posting_id} 至今恒为空，
     * 聊天面板也没有岗位名可读（ADR-0016 的 Context）。同一位 HR 挂了多个岗位时它取最近投递的
     * 那一个；取错了的后果是「列表里显示的岗位信息是另一个」，不影响任何自动动作。
     *
     * <p>按 {@code d.id} 排序而不是 {@code finished_at}：刚投出去还没收尾的行 {@code finished_at}
     * 为空，按时间排会把它排到最后，而它恰恰是最该命中的一条。
     */
    @Select("SELECT j.* FROM job_posting j JOIN delivery d ON d.job_posting_id = j.id "
            + "WHERE j.platform = #{platform} AND j.hr_name = #{hrName} AND d.status = 'DELIVERED' "
            + "ORDER BY d.id DESC LIMIT 1")
    JobPostingEntity latestDeliveredByHr(@Param("platform") String platform, @Param("hrName") String hrName);
}
