package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 岗位。platformJobId 唯一，去重与投递状态都挂在它身上。 */
@Data
@TableName("job_posting")
public class JobPostingEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    private String platformJobId;
    private String jobName;
    private String companyName;
    private String salary;
    private String area;
    private String experience;
    private String degree;
    private String description;
    private String hrName;
    private String hrTitle;
    private String activeLevel;
    private String activeRaw;
    private String rawJson;
    private String firstSeenAt;
    private String lastSeenAt;
}
