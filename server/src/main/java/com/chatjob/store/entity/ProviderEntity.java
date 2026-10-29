package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 模型提供商。api_key 永远是密文，接口层只回发 {@code keyConfigured} 布尔值。 */
@Data
@TableName("provider")
public class ProviderEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String baseUrl;
    private String model;
    private String apiKeyCipher;
    private Integer timeoutSeconds;
    /** JSON object，自定义请求头 */
    private String extraHeaders;
    private Integer enabled;
    private String createdAt;
    private String updatedAt;
}
