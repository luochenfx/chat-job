package com.chatjob.driver.human;

import java.util.ArrayList;
import java.util.List;

/**
 * 鼠标轨迹：从当前位置到目标落点的点集。纯计算，不发任何事件。
 *
 * <p>轨迹为什么不是直线：直达目标的直线在每一步上都是"朝向目标的固定方向、固定步长"，
 * 这个规律比落点分散更容易被统计——只要记录各步的位移向量就能得到一条完美的直线。
 * 这里用二次贝塞尔曲线，控制点在中点沿垂直方向偏移，得到一条先偏离再回正的弧。
 *
 * <p>为什么偏移量有上限（{@link #MAX_OFFSET_RATIO}）：偏移太大时曲线会先越过目标再折回，
 * 距离目标的值不再单调减小。真实移动也不会这样——所以 <b>"到目标的距离单调不增"是本类的
 * 不变式</b>，由单测钉住，偏移上限就是为它服务的。
 */
public final class HumanMotion {

    /**
     * 控制点相对位移的最大偏离比例（占起终两点距离）。
     *
     * <p>0.25 是使"距离单调不增"成立的上限量级：弧的横向分量约为 {@code offset·π·(1-t)}，
     * 纵向分量约为 {@code distance·(1-t)}，只要横向小于纵向，靠近的过程就不会反转。
     */
    public static final double MAX_OFFSET_RATIO = 0.25;

    /** 最小偏离比例。比它更小的偏移在屏幕上只有一两个像素，等于直线。 */
    public static final double MIN_OFFSET_RATIO = 0.05;

    /** 起终距离小于这个值（像素）时不生成轨迹，直接返回目标点。 */
    private static final double NEGLIGIBLE_DISTANCE = 1.0;

    private HumanMotion() {
    }

    /**
     * 生成轨迹点。
     *
     * @param from  当前位置。指针位置由本层自己记录，因为 Playwright 不暴露当前坐标
     * @param to    目标落点
     * @param steps 轨迹点个数（不含起点）。{@code <=0} 按 1 处理
     * @return 长度为 {@code steps} 的点集，最后一点与 {@code to} 完全相等
     */
    public static List<HumanAim> path(HumanAim from, HumanAim to, int steps, Rng rng) {
        int count = Math.max(1, steps);
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double distance = Math.hypot(dx, dy);
        if (distance < NEGLIGIBLE_DISTANCE) {
            return List.of(to);
        }

        // 控制点：中点 + 垂直方向偏移。垂直方向由 (dx, dy) 旋转 90° 得到，符号随机——
        // 方向固定会让所有轨迹朝同一侧弯，那本身就是规律。
        double offsetRatio = MIN_OFFSET_RATIO + rng.nextDouble() * (MAX_OFFSET_RATIO - MIN_OFFSET_RATIO);
        double offset = distance * offsetRatio * (rng.chance(0.5) ? 1 : -1);
        double controlX = from.x() + dx / 2 - dy / distance * offset;
        double controlY = from.y() + dy / 2 + dx / distance * offset;

        List<HumanAim> points = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            double t = smoothstep((double) i / count);
            double oneMinusT = 1 - t;
            double x = oneMinusT * oneMinusT * from.x() + 2 * oneMinusT * t * controlX + t * t * to.x();
            double y = oneMinusT * oneMinusT * from.y() + 2 * oneMinusT * t * controlY + t * t * to.y();
            points.add(new HumanAim(x, y));
        }
        // 浮点累加会让末点差几个 1e-13。点击落在差一点的位置上没有影响，但"末点等于目标"
        // 是调用方与测试共同的假设，直接对齐，省掉后面所有的容差判断。
        points.set(points.size() - 1, to);
        return points;
    }

    /**
     * 首尾慢、中间快。
     *
     * <p>均匀的 {@code t} 会让轨迹点等距分布，而等距分布意味着速度恒定。
     * 真实移动有起步和收尾的加减速，代价为零，加在这里。
     */
    private static double smoothstep(double t) {
        return t * t * (3 - 2 * t);
    }
}
