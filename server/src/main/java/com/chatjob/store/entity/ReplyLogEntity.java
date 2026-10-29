package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 值守动作留痕。UNMATCHED 记录是规则库唯一的进化路径，不要省。 */
@Data
@TableName("reply_log")
public class ReplyLogEntity {

    /** 规则命中后直接发的（或 AI 失败降级用的）模板文案 */
    public static final String SOURCE_TEMPLATE = "TEMPLATE";
    /** 由 AI 生成后发出的 */
    public static final String SOURCE_AI = "AI";
    /**
     * 发简历动作。{@code reply_text} 为空 —— 这个动作不产生文字，
     * 拿一个空字符串去填充反而会让人以为「发了条空消息」。
     */
    public static final String SOURCE_RESUME = "RESUME";

    /** 动作名：发出了一份简历 */
    public static final String ACTION_REPLY_RESUME = "REPLY_RESUME";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String platformMessageId;
    private String incomingText;
    /** REPLIED | SKIPPED_SENSITIVE | SKIPPED_SELF | SKIPPED_DUPLICATE | SKIPPED_THROTTLED | UNMATCHED */
    private String action;
    private Long matchedRuleId;
    private String matchedSensitive;
    private String replyText;
    /**
     * AI 主动不回的原因，取值见 {@code AiReplier.SKIP_REASONS}。
     *
     * <p>可 {@code GROUP BY} 是这张表存在的意义：没有它，「模型在躲」只能靠翻日志数。
     * 技术性失败（{@code AI_FAILED}）<b>不</b>写这里 —— 那是不算 skip 的另一类问题。
     */
    private String reasonCode;
    /** 补充说明，≤200 字，禁止复述对方原话 */
    private String reasonNote;
    /**
     * {@code TEMPLATE} | {@code AI}。历史行为 NULL，读时按 {@code TEMPLATE} 处理。
     *
     * <p>不能省：skip 占比告警的分母只算 AI，否则会被模板回复稀释成一个永远正常的数字。
     */
    private String replySource;
    private String createdAt;
}
