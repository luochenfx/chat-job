package com.chatjob.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台开关的判定（spec 0004 阶段 3、spec 0005）。
 *
 * <p>这里只测纯逻辑：缺省、拒绝时抛什么、文案里有没有平台名，以及扫描与值守不能同时关。
 * 「读到库里那一列」这件事不在这里测 —— 那是 {@code ConfigStore} 的活，
 * 打桩读配置只会把断言退化成「自己塞进去的值又读回来」。
 */
class PlatformSwitchesTest {

    private static PlatformSwitches of(boolean enabled) {
        return new PlatformSwitches(p -> enabled);
    }

    @Test
    void 启用的平台放行() {
        assertThat(of(true).isEnabled("boss")).isTrue();
        of(true).require("zhaopin"); // 不抛即通过
    }

    @Test
    void 停用的平台拒绝并点名是哪个平台() {
        PlatformSwitches switches = of(false);
        assertThat(switches.isEnabled("zhaopin")).isFalse();
        assertThatThrownBy(() -> switches.require("zhaopin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("智联招聘")
                .hasMessageContaining("已停用");
    }

    @Test
    void 停用文案带上平台名_否则收到告警不知道关的是哪个() {
        assertThat(PlatformSwitches.disabledMessage("boss")).contains("BOSS 直聘");
        assertThat(PlatformSwitches.disabledMessage("zhaopin")).contains("智联招聘");
    }

    @Test
    void 平台标识大小写与空格归一() {
        // 前端可能带着 "BOSS " 过来；不归一的话「停用了 BOSS」和「BOSS 还在跑」会同时成立
        assertThatThrownBy(() -> of(false).require(" BOSS "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BOSS 直聘");
    }

    @Test
    void 扫描与值守都关_拒绝并指向平台开关() {
        // 两者都关的平台启动后什么都不做，与「停用」只差浏览器没被关掉；
        // 报错要能指向真正的处置动作（整体停用），否则收到报错的人只会去再调这两个开关
        assertThatThrownBy(() -> PlatformSwitches.requireSomethingOn("zhaopin", false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("智联招聘")
                .hasMessageContaining("同时关闭")
                .hasMessageContaining("平台开关");
    }

    @Test
    void 只关一个_放行() {
        // 三种合法组合都不该抛：只值守、只扫描、都开
        PlatformSwitches.requireSomethingOn("boss", false, true);
        PlatformSwitches.requireSomethingOn("boss", true, false);
        PlatformSwitches.requireSomethingOn("boss", true, true);
    }
}
