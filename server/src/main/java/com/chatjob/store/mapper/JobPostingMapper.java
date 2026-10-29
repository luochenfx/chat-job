package com.chatjob.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chatjob.store.entity.JobPostingEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface JobPostingMapper extends BaseMapper<JobPostingEntity> {
}
