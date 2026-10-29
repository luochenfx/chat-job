package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 当日计数。配额与评估上限都从这里读，重启不归零。 */
@Data
@TableName("daily_counter")
public class DailyCounterEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    /** yyyy-MM-dd */
    private String day;
    private Integer delivered;
    private Integer evaluated;
}
