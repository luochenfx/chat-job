package com.chatjob.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chatjob.store.entity.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
}
