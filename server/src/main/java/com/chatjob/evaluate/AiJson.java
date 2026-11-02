package com.chatjob.evaluate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 模型输出的 JSON 读取：先提取，再解析，必要时补齐末尾。
 *
 * <p>补齐只处理一种情况：<b>结构没写完，内容完整</b>。索引到的真实样本
 * （{@code delivery} 表里唯一一次「无法识别」）长这样：
 *
 * <pre>
 * {"shouldApply": true, "score": 72, ..., "greeting": "您好……期待沟通证券项目。")
 * </pre>
 *
 * 所有字段都在、字符串都正常闭合，只是对象少了最后的 {@code }}，末尾多了个 {@code )}。
 * 把它整条丢掉，等于因为一个括号放弃一个 72 分、原本判定要投的岗位。
 *
 * <p><b>断在字符串里的绝不补。</b>那意味着招呼语可能是半句话（"您好，我有 6 年
 * Java 经"），补上闭合引号反而会让半句话通过校验并被真的发出去 —— 对收件方是伤害，
 * 而且很难被发现。这种情况一律判失败，由调用方跳过或重试。
 *
 * <p>补齐不改变任何已有字段的取值，只在末尾补 {@code }} / {@code ]}；
 * 补齐后仍要经过一次正常解析，解析不过即视为失败。
 */
public final class AiJson {

    private AiJson() {
    }

    /**
     * @param node     解析结果；失败时为 null
     * @param repaired 是否经过末尾补齐（用于告警与复盘，不代表内容有问题）
     * @param error    非空表示失败，内容为可直接写进日志的原因
     */
    public record Read(JsonNode node, boolean repaired, String error) {

        static Read ok(JsonNode node, boolean repaired) {
            return new Read(node, repaired, null);
        }

        static Read failed(String error) {
            return new Read(null, false, error);
        }

        public boolean isFailure() {
            return error != null;
        }
    }

    public static Read read(ObjectMapper mapper, String rawContent) {
        String content = ChatClient.extractJson(rawContent);
        String firstError;
        try {
            return Read.ok(mapper.readTree(content), false);
        } catch (Exception e) {
            firstError = detail(e);
        }
        Tail tail = closeUnclosed(content);
        if (tail.text() == null) {
            return Read.failed(firstError + "；" + tail.blocked());
        }
        try {
            return Read.ok(mapper.readTree(tail.text()), true);
        } catch (Exception e) {
            return Read.failed(firstError + "；补齐末尾后仍不合法（" + detail(e) + "）");
        }
    }

    /**
     * 补齐结果：要么给出可解析的文本，要么给出「为什么不能补」。
     *
     * <p>失败原因必须如实区分 —— 都写成「格式不对」的话，拿到日志的人无法判断
     * 该去查模型输出上限、查编码问题，还是查 prompt 被改坏。
     */
    record Tail(String text, String blocked) {

        static Tail closed(String text) {
            return new Tail(text, null);
        }

        static Tail blocked(String reason) {
            return new Tail(null, reason);
        }
    }

    /**
     * 把「所有值都完整、只差收尾」的 JSON 补成一个可解析的对象。
     */
    static Tail closeUnclosed(String content) {
        Scan scan = scan(content);
        if (scan.insideString()) {
            // 补上引号等于把半句话当成完整招呼语放行，宁可失败
            return Tail.blocked("断在字符串内，不补齐");
        }
        if (scan.depth() == 0) {
            return Tail.blocked("不是未闭合的对象，补齐不适用");
        }
        if (scan.lastValueEnd() <= 0) {
            return Tail.blocked("没有可用的完整值，不补齐");
        }
        String head = stripDanglingTail(content.substring(0, scan.lastValueEnd()));
        // 以冒号结尾说明键还没有值，以逗号结尾说明后面还等着一个字段；两种都不是"只差收尾"
        if (head.isEmpty() || head.endsWith(":") || head.endsWith(",")) {
            return Tail.blocked("最后一个字段没写完，不补齐");
        }
        StringBuilder closers = new StringBuilder();
        if (!unclosedClosers(head, closers)) {
            return Tail.blocked("结构不配对，不补齐");
        }
        return Tail.closed(head + closers);
    }

    /**
     * @param lastValueEnd 最后一个完整的值之后的偏移
     * @param insideString 文本是否结束在字符串内部（即被截断在中途）
     * @param depth        扫描结束时还未闭合的容器层数
     */
    private record Scan(int lastValueEnd, boolean insideString, int depth) {
    }

    private static Scan scan(String content) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int lastValueEnd = -1;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                    lastValueEnd = i + 1;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> depth++;
                case '}', ']' -> {
                    depth--;
                    if (depth < 0) {
                        return new Scan(-1, false, 0);
                    }
                    lastValueEnd = i + 1;
                }
                default -> {
                    if (isTokenChar(c)) {
                        lastValueEnd = i + 1;
                    }
                }
            }
        }
        return new Scan(lastValueEnd, inString, depth);
    }

    /** 数字与 true/false/null 的字符；散在结构外的其它字符（例如多出来的 {@code )}）不算 */
    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '+';
    }

    /** 去掉尾部空白与悬空的逗号 */
    private static String stripDanglingTail(String head) {
        int end = head.length();
        while (end > 0) {
            char c = head.charAt(end - 1);
            if (!Character.isWhitespace(c) && c != ',') {
                break;
            }
            end--;
        }
        return head.substring(0, end);
    }

    /**
     * 按 head 重算未闭合的容器，并按逆序写进 closers。
     *
     * @return head 不平衡（多出闭合符、配对错误、结束在字符串内）或已经闭合时返回 false
     */
    private static boolean unclosedClosers(String head, StringBuilder closers) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> stack.push(c);
                case '}' -> {
                    if (stack.isEmpty() || stack.pop() != '{') {
                        return false;
                    }
                }
                case ']' -> {
                    if (stack.isEmpty() || stack.pop() != '[') {
                        return false;
                    }
                }
                default -> {
                }
            }
        }
        if (inString || stack.isEmpty()) {
            return false;
        }
        while (!stack.isEmpty()) {
            closers.append(stack.pop() == '{' ? '}' : ']');
        }
        return true;
    }

    /**
     * Jackson 的异常消息会带上整段原文（有几十 KB），日志里只留原因那一句。
     */
    private static String detail(Exception e) {
        if (e instanceof JsonProcessingException jpe && jpe.getOriginalMessage() != null) {
            return jpe.getOriginalMessage();
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
