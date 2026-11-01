package com.chatjob.driver.human;

/**
 * 输入的节奏与错字位置。纯计算：给定要输入的文字与参数，输出每个字符前该等多久、要不要打错。
 *
 * <p>三条必须知道的限制：
 *
 * <p><b>1. 中文只有 {@code input}，没有 {@code keydown}。</b>中文经
 * {@code Keyboard.insertText} 一次性插入，Playwright 只有这条路能把任意 Unicode 字符打得出来。
 * 所以中文的节奏只能做到"字与字之间停顿"，做不到每个字都产生按键事件。看到本层输出
 * "间隔 55–165ms" 不要理解成"每个字都有一次按键"——按 {@code keydown} 全链路去评估这一层
 * 是否完整，会得出错误结论。
 *
 * <p><b>2. 错字只对能用单键打出的字符生效。</b>错字的意义是"打到了一个相邻的键"，ASCII 字母与
 * 数字有"相邻键"可言；汉字没有（中文走 {@link #NEIGHBOURS} 之外的路，见第 1 条）。
 * 对不含可错字符的文本，本类返回 {@code null}，不假装打错。
 *
 * <p><b>3. 长停顿的位置由 {@code 8–15} 字的窗口决定，不是每 10 个字卡一次。</b>窗口长度每次重抽，
 * 因为"每 10 个字停一次"这种整除关系本身就比随机更容易被统计。
 */
public final class HumanTiming {

    /** 长停顿窗口的下界（字）。{@code 8} 与上界一起决定"多久出现一次长停顿"。 */
    static final int MIN_LONG_PAUSE_WINDOW = 8;

    /** 长停顿窗口的上界（字）。 */
    static final int MAX_LONG_PAUSE_WINDOW = 15;

    /**
     * 相邻键的近似表。它不追求覆盖整个键盘：错字是"偶发的一次手误"，只需要一个
     * 在屏幕上确实离得近、且方向上说得通的字符，不需要精确的键位距离模型。
     */
    private static final String[] NEIGHBOURS = {
            "qwertyuiop", "asdfghjkl", "zxcvbnm",
            "1234567890"
    };

    private HumanTiming() {
    }

    /**
     * 一次错字。
     *
     * @param index 要打成错字的位置（{@code text} 的下标）
     * @param wrong 先打出去的那个错字符，随后会被 Backspace 删掉
     */
    public record Mistype(int index, char wrong) {
    }

    /**
     * 算出每个字符之前的等待时间。
     *
     * <p>返回长度与 {@code length} 相同的数组：下标 {@code i} 的值 = 输入第 {@code i} 个字符之前
     * 要等多久。用数组而不是"给定下标返回间隔"，是因为长停顿需要跨字符的状态
     * （下一个长停顿落在第几个字），每次单独算就只能退化成整除关系。
     *
     * @param length 字符数
     * @param config 间隔参数
     */
    public static int[] delays(int length, HumanizeConfig.Keyboard config, Rng rng) {
        int[] delays = new int[Math.max(0, length)];
        if (delays.length == 0) {
            return delays;
        }
        int nextLongPause = nextWindow(rng);
        for (int i = 0; i < delays.length; i++) {
            int delay = rng.uniformInt(config.charDelayMs());
            if (i == nextLongPause) {
                // 长停顿是"加上去"而不是"替换掉"：替换会让该字符的间隔反而比相邻字符短，
                // 出现一次孤立的速度变化，比不加还显眼
                delay += rng.uniformInt(config.thinkingPauseMs());
                nextLongPause += nextWindow(rng);
            }
            delays[i] = delay;
        }
        return delays;
    }

    /**
     * 决定本次输入要不要打错、错在哪个位置、错成什么字符。
     *
     * @return {@code null} 表示这次不打错（概率未命中，或文本里没有可错的字符）
     */
    public static Mistype mistype(String text, HumanizeConfig.Keyboard config, Rng rng) {
        if (text == null || text.isEmpty() || !rng.chance(config.mistypeChance())) {
            return null;
        }
        int[] candidates = eligiblePositions(text);
        if (candidates.length == 0) {
            return null;
        }
        int index = candidates[rng.uniformInt(HumanizeConfig.Range.of(0, candidates.length - 1))];
        return new Mistype(index, neighbour(text.charAt(index), rng));
    }

    /**
     * 哪些位置可以打错：ASCII 字母与数字，且不是最后一个字符。
     *
     * <p>排除末字符的理由：错字之后要 Backspace 再补上正确字符，末字符出错时补字与
     * "输入结束"挨得太近，一旦补字失败，发到对话里的就是一句意思被改过的话——投递场景里
     * 这个代价比"少一次错字"高得多。
     */
    private static int[] eligiblePositions(String text) {
        // 末字符不可错，所以上限是 length-1；length==1 时为空
        int[] buffer = new int[text.length() - 1];
        int size = 0;
        for (int i = 0; i < text.length() - 1; i++) {
            if (isSingleKeyTypable(text.charAt(i))) {
                buffer[size++] = i;
            }
        }
        int[] result = new int[size];
        System.arraycopy(buffer, 0, result, 0, size);
        return result;
    }

    static boolean isSingleKeyTypable(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /**
     * 取一个相邻字符。同一行内的左右邻格优先，行首行尾退化为另一侧，
     * 大小写跟随原字符——打成大小写不一致的字符会让人一眼看出是"程序打的"。
     */
    static char neighbour(char original, Rng rng) {
        char lower = Character.toLowerCase(original);
        for (String row : NEIGHBOURS) {
            int at = row.indexOf(lower);
            if (at < 0) {
                continue;
            }
            int step = rng.chance(0.5) ? 1 : -1;
            int next = at + step;
            if (next < 0 || next >= row.length()) {
                next = at - step;
            }
            char picked = row.charAt(next);
            return Character.isUpperCase(original) ? Character.toUpperCase(picked) : picked;
        }
        // 不可错的字符不会走到这里（eligiblePositions 已经过滤）
        return original;
    }

    private static int nextWindow(Rng rng) {
        return rng.uniformInt(HumanizeConfig.Range.of(MIN_LONG_PAUSE_WINDOW, MAX_LONG_PAUSE_WINDOW));
    }
}
