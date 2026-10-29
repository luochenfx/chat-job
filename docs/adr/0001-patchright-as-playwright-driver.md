# 用 patchright 的 driver 替换 Playwright 官方 driver

## Status

accepted。降级策略取 **fail-closed**：装配产物缺失或版本不匹配时拒绝启动任务，不在降级后的官方 driver 上继续投递。

## Context

目标站点会检测 Playwright 的控制痕迹（`Runtime.enable` 调用、`navigator.webdriver`、启动参数特征）。patchright 仅提供 Python / Node 绑定，Java 明确在 TODO 中未实现，但 `patchright-core` 本身就是被 patch 过的 `playwright-core`（driver 层），因此可以在保留「执行层内嵌在 Spring Boot JVM 内」的前提下，把 patchright-core 装配成 driver bundle，替换 Playwright Java 自带的官方 driver。

## 实现机制

`playwright-java` 只是壳，真正干活的是它拉起的 Node driver 进程。注入方式（已在参考实现 `get_jobs` 中验证）：

1. 构建期：`npm install patchright-core@1.62.1 --prefix build/patchright-driver`，把 `node_modules/patchright-core` 拷到 `build/patchright-driver/package`，以 `package/cli.js` 存在为就绪判据。
2. 运行时：设 `-Dplaywright.cli.dir=<driverDir>`；driver 目录内没有 node，再用 `PLAYWRIGHT_NODEJS_PATH` 指向本机 node；最后 `Playwright.create(new CreateOptions().setEnv(env))`。
3. 版本组合：`playwright-java 1.62.0` + `patchright-core 1.62.1`。不得降级到 1.51（该版本 `locator.count()` 存在缺陷）。

## Considered Options

- **官方 Playwright Java + 手写 init script 去 webdriver**：压不住 `Runtime.enable` 泄漏（这是 2026 年最主要的检测点），等于没防。
- **拆出独立 Node 执行器，直接用 npm 的 patchright**：实现成本最低，但要引入跨进程编排、状态同步与故障恢复，与「单进程、同库落状态」的设计目标冲突。
- **放弃 stealth，接受更高封号风险**：家庭 IP + 单账号低频的风险可控，但一旦掉登录或被打回匿名，整条链路停摆。

## Consequences

- driver 版本必须与 playwright-java 处于同一 minor（当前锁 1.62.x），否则 run-driver 协议不兼容；patchright 发版落后于 Playwright 时，升级通道会被卡住。
- 每次升级 playwright-java 都需要重打一次 driver bundle，这是长期维护成本。
- patchright 整体禁用 Console API，页面侧 `console` 日志不可用。
- patchright 的 init script 通过 Routes 注入，业务侧不宜再用 route 拦截做别的事。
- patchright 内部已加 `--disable-blink-features=AutomationControlled`、移除 `--enable-automation`，并移除 `--disable-popup-blocking`、`--disable-extensions`、`--disable-component-update`、`--disable-default-apps`；自传 `args` 需核对这些改写是否被抵消。
- 启动即自检：浏览器起来后先断言 driver 类型与 `navigator.webdriver === false`，断言失败则任务不可启动，把「以为装上了其实没装上」变成启动即失败。
