package com.chatjob.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 文本摘要。唯一用途是「这个会话列表项的最后一句话有没有变」（spec 0002 §3.4 二次校验）。
 *
 * <p>取 SHA-256 前 16 位十六进制：碰撞概率对「同一会话前后两条摘要对比」这件事已经足够，
 * 又不至于把 HR 的原话明文再落一份 —— 原话在 {@code reply_log.incoming_text} 里已经有了，
 * 这里只需要一个能对上的指纹。
 */
public final class Digest {

    private static final int HEX_CHARS = 16;

    private Digest() {
    }

    /**
     * @return 摘要；输入为 {@code null} 或空白时返回 {@code null}，
     *         语义是「这条会话没有可校验的摘要」（例如最后一条是职位卡片），
     *         调用方此时只做标题精确匹配，不做摘要比对。
     */
    public static String of(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，这里不可能发生；真发生了也不能让值守崩掉。
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
