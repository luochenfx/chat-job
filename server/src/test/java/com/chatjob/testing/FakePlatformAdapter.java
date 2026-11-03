package com.chatjob.testing;

import com.chatjob.platform.Models.ChatMessage;
import com.chatjob.platform.Models.DeliveryOptions;
import com.chatjob.platform.Models.DeliveryResult;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.platform.Models.LoginState;
import com.chatjob.platform.Models.ResumeResult;
import com.chatjob.platform.Models.ScrollOutcome;
import com.chatjob.platform.Models.SearchQuery;
import com.chatjob.platform.Models.UnreadThread;
import com.chatjob.platform.PlatformAdapter;
import com.chatjob.platform.ThreadTargetUnsafeException;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * {@link PlatformAdapter} 的共用测试替身（hand-written fake，项目不用 mock 框架）。
 *
 * <p>它原来是 {@code UnreadWatcherTest} 里的私有内部类，只服务值守链路。扫描段抽取之后
 * 有两个测试类需要同一个替身，复制一份的代价是「适配器接口加方法时总有一处漏改」——
 * 所以提出来放在这里。
 *
 * <p>两边需要的面不一样，所以字段分两组：值守侧（未读、读消息、发文本、发简历）与
 * 扫描侧（开搜索页、滚动结果、抓详情）。各自只读自己那组，互不干扰。
 *
 * <p><b>默认值刻意做成「什么都看不出发生」</b>：{@code fetchDetail} 返回 {@code null}
 * 表示这张卡没抓到，{@code scrollUntilEnd} 返回 0 张卡。测试想让某事发生就必须显式设置，
 * 免得断言在「默认值恰好等于预期」时假绿。
 */
public class FakePlatformAdapter implements PlatformAdapter {

    // ------------------------------------------------------------------ 值守侧

    public final List<UnreadThread> unread = new ArrayList<>();
    public final List<String> events = new ArrayList<>();
    public final List<String> readCalls = new ArrayList<>();
    public final List<String> readTitles = new ArrayList<>();
    public final List<String> sendCalls = new ArrayList<>();
    public final List<String> resumeCalls = new ArrayList<>();
    public final List<String> digests = new ArrayList<>();
    public List<ChatMessage> messages = List.of(new ChatMessage("mid-1", "你好", false, false));
    public boolean emptyMessages = false;
    public boolean sendOk = true;
    public ResumeResult resumeResult = ResumeResult.sent("简历卡片已出现");
    @Nullable
    public ThreadTargetUnsafeException throwOnRead;

    // ------------------------------------------------------------------ 扫描侧

    /** 开过的搜索页，按顺序。用来钉「关掉扫描之后不得再导航」 */
    public final List<SearchQuery> searches = new ArrayList<>();
    /** 抓过详情的卡片下标，按顺序。用来钉「断点从第几张继续」 */
    public final List<Integer> detailCalls = new ArrayList<>();
    /** {@code scrollUntilEnd} 报告的卡片数 */
    public int cards = 0;
    public int rounds = 1;
    public boolean loadedAll = true;
    /** 按下标给详情；默认全 null，即「每张卡都没抓到」 */
    public Function<Integer, JobDetail> details = index -> null;

    @Override
    public String platform() {
        return "boss";
    }

    @Override
    public LoginState probeLogin() {
        return null;
    }

    @Override
    public void openLogin() {
    }

    @Override
    public void openSearch(SearchQuery query) {
        searches.add(query);
    }

    @Override
    public ScrollOutcome scrollUntilEnd(int roundLimit, BooleanSupplier stop) {
        return new ScrollOutcome(loadedAll, rounds, cards);
    }

    @Override
    public int cardCount() {
        return cards;
    }

    @Override
    public JobDetail fetchDetail(int cardIndex) {
        detailCalls.add(cardIndex);
        return details.apply(cardIndex);
    }

    @Override
    public DeliveryResult deliver(JobDetail job, String greeting, DeliveryOptions options, BooleanSupplier stop) {
        return null;
    }

    @Override
    public List<UnreadThread> listUnreadThreads() {
        return unread;
    }

    @Override
    public List<ChatMessage> readMessages(UnreadThread thread) {
        readCalls.add("read:" + thread.title());
        readTitles.add(thread.title());
        digests.add(thread.expectedDigest());
        events.add("read:" + thread.title());
        if (throwOnRead != null) {
            throw throwOnRead;
        }
        return emptyMessages ? List.of() : messages;
    }

    @Override
    public boolean sendText(UnreadThread thread, String text) {
        sendCalls.add("send:" + thread.title() + ":" + text);
        events.add("send:" + thread.title());
        return sendOk;
    }

    @Override
    public ResumeResult sendResume(UnreadThread thread) {
        resumeCalls.add("resume:" + thread.title());
        events.add("resume:" + thread.title());
        return resumeResult;
    }

    @Override
    public void closeThread() {
    }
}
