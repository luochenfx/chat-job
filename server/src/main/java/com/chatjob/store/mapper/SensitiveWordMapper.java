package com.chatjob.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chatjob.store.entity.SensitiveWordEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SensitiveWordMapper extends BaseMapper<SensitiveWordEntity> {
}
