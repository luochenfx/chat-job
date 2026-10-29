package com.chatjob.platform.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 岗位详情 → 已沟通确认框 → 聊天页。只负责"进会话"，不输入、不发送。
 *
 * <p>做成带超时的状态机而不是三步直线，是因为这中间有四条反直觉的规则：
 * <ol>
 *   <li>确认框只在点击后 1 秒内找，且只点一次 —— 弹框晚于 1 秒出现时点它，
 *       会点到别的元素或者点到别的岗位的按钮。</li>
 *   <li>"已向BOSS发送消息"提示框 <b>不是</b>旧会话的证据，首次沟通也会弹它。</li>
 *   <li>旧页面的输入框即使可见也不能用 —— 消息会发到错误的页面。</li>
 *   <li>每轮重建 locator，不复用导航前的 frame 引用。</li>
 * </ol>
 */
@Slf4j
public class BossChatNavigator implements AutoCloseable {

    static final String INPUT = BossSelectors.CHAT_INPUT;

    /** @param repeat 平台的按钮文案表明这是"继续沟通" */
    public record Chat(Page page, Locator input, boolean repeat) {
    }

    private final Page origin;
    private final List<Page> popups = new ArrayList<>();
    private final Consumer<Page> popupListener = popups::add;

    public BossChatNavigator(Page origin) {
        this.origin = origin;
        origin.onPopup(popupListener);
    }

    public Chat open(Locator button, boolean repeat, boolean allowRepeat, BooleanSupplier stop, long timeoutMs) {
        if (stop != null && stop.getAsBoolean()) {
            return null;
        }
        if (repeat && !allowRepeat) {
            return null;
        }
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        String entryUrl = origin.url();
        try {
            button.click(new Locator.ClickOptions().setTimeout(Math.min(timeoutMs, 5_000)));
        } catch (PlaywrightException navigationError) {
            log.debug("点击岗位按钮时发生页面切换，继续检查聊天入口: {}", navigationError.getMessage());
        }
        // 弹框只在点击后 1 秒内检测；聊天页加载仍用独立总超时
        long confirmationDeadline = Math.min(deadline, System.nanoTime() + 1_000_000_000L);
        Page confirmationPage = null;
        String confirmationUrl = null;
        boolean confirmed = false;
        while (System.nanoTime() < deadline) {
            if (stop != null && stop.getAsBoolean()) {
                return null;
            }
            List<Page> candidates = new ArrayList<>(popups);
            if (candidates.stream().allMatch(Page::isClosed)) {
                candidates.add(origin);
            }
            for (Page candidate : candidates) {
                if (candidate.isClosed()) {
                    continue;
                }
                try {
                    Locator confirmation = !confirmed && System.nanoTime() < confirmationDeadline
                            ? findConfirmation(candidate) : null;
                    if (confirmation != null) {
                        if (!confirmed) {
                            confirmationPage = candidate;
                            confirmationUrl = candidate.url();
                            confirmed = true;
                            long remainingMs = (confirmationDeadline - System.nanoTime()) / 1_000_000L;
                            if (remainingMs <= 0) {
                                continue;
                            }
                            try {
                                confirmation.click(new Locator.ClickOptions().setTimeout(remainingMs));
                            } catch (PlaywrightException clickError) {
                                log.warn("弹框确认未在 1 秒检测窗口内完成，跳过当前岗位");
                                return null;
                            }
                            log.info("已点击已沟通确认框内的【继续沟通】，等待聊天页跳转");
                        }
                        continue;
                    }
                    if (confirmed && candidate == confirmationPage
                            && candidate.url().equals(confirmationUrl)) {
                        continue;
                    }
                    if (repeat && candidate == origin && candidate.url().equals(entryUrl)) {
                        continue;
                    }
                    // 每轮新建定位器，避免导航后继续用旧 frame 的输入框
                    Locator input = candidate.locator(INPUT).first();
                    if (input.isVisible() && input.isEditable(new Locator.IsEditableOptions().setTimeout(500))) {
                        return new Chat(candidate, input, repeat);
                    }
                } catch (PlaywrightException navigationError) {
                    log.debug("聊天页正在切换，继续等待: {}", navigationError.getMessage());
                }
            }
            Page pump = !origin.isClosed() ? origin
                    : popups.stream().filter(p -> !p.isClosed()).findFirst().orElse(null);
            if (pump == null) {
                return null;
            }
            try {
                pump.waitForTimeout(100);
            } catch (PlaywrightException navigationError) {
                log.debug("等待聊天页时页面已切换: {}", navigationError.getMessage());
            }
        }
        log.warn("等待聊天页超时，未发送消息；请检查是否存在未识别的弹框");
        return null;
    }

    private Locator findConfirmation(Page page) {
        Locator greetConfirm = page.locator(BossSelectors.GREET_CONFIRM);
        if (greetConfirm.count() == 1 && greetConfirm.isVisible()
                && "继续沟通".equals(safeTrim(greetConfirm.textContent()))) {
            return greetConfirm;
        }
        Locator stays = page.locator(BossSelectors.STAY_THIS_PAGE);
        for (int i = 0; i < stays.count(); i++) {
            Locator container = stays.nth(i).locator(BossSelectors.CONFIRM_CONTAINER);
            Locator confirm = container.getByText("继续沟通", new Locator.GetByTextOptions().setExact(true));
            if (confirm.count() == 1 && confirm.isVisible()) {
                return confirm;
            }
        }
        return null;
    }

    private static String safeTrim(String text) {
        return text == null ? "" : text.trim();
    }

    @Override
    public void close() {
        origin.offPopup(popupListener);
        // 只清理由当前岗位点击产生的新窗口，不碰其他标签页
        for (Page popup : popups) {
            closeQuietly(popup);
        }
        closeQuietly(origin);
    }

    private void closeQuietly(Page page) {
        try {
            if (page != null && !page.isClosed()) {
                page.close();
            }
        } catch (PlaywrightException e) {
            log.debug("关闭本轮聊天页失败: {}", e.getMessage());
        }
    }
}
