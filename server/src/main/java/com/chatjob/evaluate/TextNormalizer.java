package com.chatjob.evaluate;

import java.util.Locale;

/**
 * 文本归一化：trim → 全角转半角 → 小写。
 *
 * <p>不做这一步，"阿里" 就匹配不到 "阿里巴巴 "，"ＢＹＴＥＤＡＮＣＥ" 也匹配不到 "bytedance"。
 * 归一化只用于匹配，展示仍然用原文。
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        String trimmed = text.strip();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\u3000') {
                sb.append(' ');
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
