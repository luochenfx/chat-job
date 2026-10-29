package com.chatjob.config;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 项目根目录（运行时数据的锚点）。
 *
 * <p><b>为什么需要它</b>：{@code application.yaml} 里所有运行时路径（数据库、日志、浏览器档案、
 * patchright driver）都是相对路径，相对的是 JVM 的「工作目录」。而工作目录取决于谁怎么启动：
 * <ul>
 *   <li>{@code start.bat} → {@code cd} 到仓库根再 {@code java -jar}，cwd = 仓库根；</li>
 *   <li>{@code mvn -f server/pom.xml spring-boot:run} → Maven 的 {@code workingDirectory}
 *       是 File 类型参数，<b>按模块 basedir（server/）解析相对路径</b>，所以连
 *       {@code -Dspring-boot.run.workingDirectory=.} 都等于 {@code server/}，cwd = server/；</li>
 *   <li>IDE 直接 Run → cwd 通常是模块目录或仓库根，取决于运行配置。</li>
 * </ul>
 * 三种入口因此会读写<b>不同的</b> SQLite 文件，表现为「改完配置重启没生效」「历史记录凭空消失」，
 * 而且两边都不报错。曾真实发生过：仓库根 {@code data/chat-job.db} 停在 V5 且零业务数据，
 * {@code server/data/chat-job.db} 跑到 V10 且带着全部投递记录。
 *
 * <p><b>怎么锚定</b>：不去猜 cwd，而是从自身的 code source 反查——无论 jar 还是 classes，
 * 它们一定落在 {@code <项目根>/server/target/...} 下。沿父目录向上找到名为 {@code server}
 * 的那一级，它的父目录就是项目根。这样源码运行、jar 运行、IDE 运行三种方式得到同一个答案。
 *
 * <p><b>优先级</b>：{@code -Dchat-job.home} → 环境变量 {@code CHAT_JOB_HOME} → code source 探测
 * → 退化到 cwd。前两条是逃生门：部署到非标准目录时用它们显式指定。
 */
public final class ProjectHome {

    public static final String PROPERTY = "chat-job.home";
    public static final String ENV = "CHAT_JOB_HOME";

    private ProjectHome() {
    }

    /** 解析当前进程应使用的项目根目录（绝对路径，无尾部分隔符）。 */
    public static Path resolve() {
        Path codeSource = codeSourceLocation();
        Path workDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        String explicit = System.getProperty(PROPERTY);
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getenv(ENV);
        }
        if (explicit == null || explicit.isBlank()) {
            Path testHome = testHome(workDir);
            if (testHome != null) {
                return testHome;
            }
        }
        return resolveFrom(codeSource, workDir, explicit);
    }

    /**
     * 测试进程的隔离数据目录。
     *
     * <p>锚定到项目根之后，{@code @SpringBootTest} 默认会连上<b>真实</b>数据库 —— 在没有隔离之前
     * 它连的只是一个空的模块库，所以这个变化把「测试跑一跑」变成了「测试能改生产数据」。
     * 这里把测试的 home 改指到 {@code target/test-home}，测试进程与真库彻底分开。
     *
     * <p>判断依据是 Surefire 注入的 {@code surefire.test.class.path}（Failsafe 同理）。
     * 需要连真库的测试请自己显式传 {@code -Dchat-job.home=...} 覆盖。
     */
    private static Path testHome(Path workDir) {
        boolean underTest = System.getProperty("surefire.test.class.path") != null
                || System.getProperty("failsafe.test.class.path") != null;
        return underTest ? workDir.resolve("target").resolve("test-home").normalize() : null;
    }

    /**
     * 纯函数版本，便于单测。
     *
     * @param codeSource 本类自身 class 文件或 jar 的位置，可为 null
     * @param workDir    当前工作目录
     * @param explicit   显式指定的项目根，可为 null/空
     */
    static Path resolveFrom(Path codeSource, Path workDir, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit.trim()).toAbsolutePath().normalize();
        }
        Path fromCodeSource = walkUpToRepoRoot(codeSource);
        if (fromCodeSource != null) {
            return fromCodeSource;
        }
        // codeSource 不可用（某些启动器会把它置空）时的第二依据。
        // cwd 落在 server/ 时就向上找到项目根 —— 这正是 dev.bat / spring-boot:run 的处境。
        Path fromWorkDir = walkUpToRepoRoot(workDir);
        if (fromWorkDir != null) {
            return fromWorkDir;
        }
        return workDir;
    }

    /** 从 start 起逐级向上找第一个「看起来是项目根」的目录；start 自身也算。 */
    private static Path walkUpToRepoRoot(Path start) {
        Path dir = start;
        for (int depth = 0; dir != null && depth < 6; depth++) {
            if (looksLikeRepoRoot(dir)) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /** 项目根的判据：有 CONTEXT.md 或启动脚本。宁可退回 cwd，也不认一个陌生目录当根。 */
    private static boolean looksLikeRepoRoot(Path dir) {
        return Files.isRegularFile(dir.resolve("CONTEXT.md"))
                || Files.isRegularFile(dir.resolve("start.bat"))
                || Files.isDirectory(dir.resolve(".git"));
    }

    private static Path codeSourceLocation() {
        try {
            URI uri = ProjectHome.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Paths.get(uri).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }
}
