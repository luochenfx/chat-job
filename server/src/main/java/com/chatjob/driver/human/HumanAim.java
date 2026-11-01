package com.chatjob.driver.human;

/**
 * 点击落点，以及落点的计算方式。纯计算：输入元素外框与分布参数，输出屏幕坐标。
 *
 * <p>不直接使用 Playwright 的 {@code BoundingBox}：那个类虽然也只是四个 double，
 * 但把它引进来会让本类不再是"零依赖纯计算"，单测就得为了造一个外框而依赖 Playwright 的类型。
 * {@link Box} 是本文件里的最小替身，由 {@code Humanizer} 在取框之后转换。
 */
public record HumanAim(double x, double y) {

    /**
     * 最小可点区内缩比例：落点限制在元素内缩 10% 的矩形内。
     *
     * <p>为什么要有这个内缩：元素常带圆角或贴边，落在 1–2 像素内的点可能被相邻元素抢走，
     * 也可能落在圆角外侧。内缩 10% 让坐标始终在"看起来像中心"的区域内，
     * 同时保留足够的分散度——三个像素的内缩等于没有分散。
     */
    public static final double INSET_RATIO = 0.10;

    /**
     * 元素外框，单位像素，坐标原点为视口左上角。
     *
     * @param width  宽。为 0 或负数时表示元素没有可点面积（隐藏、塌陷）
     * @param height 高
     */
    public record Box(double x, double y, double width, double height) {

        public double centerX() {
            return x + width / 2;
        }

        public double centerY() {
            return y + height / 2;
        }

        /** 没有可点面积。此时不能做内缩（会把宽高算成负数），只能退回中心。 */
        public boolean degenerate() {
            return width <= 0 || height <= 0;
        }
    }

    public static HumanAim center(Box box) {
        return new HumanAim(box.centerX(), box.centerY());
    }

    /**
     * 围绕元素中心取一个落点。
     *
     * <p>两步：先把相对位置 {@code r} 采成截断高斯（{@code center=0.5} 即中线，
     * 超出 {@code max-deviation} 的样本丢弃重采），再映射到内缩后的矩形。
     * 因此在默认参数（{@code sigma=0.15}、{@code max-deviation=0.4}）下，
     * {@code r} 落在 {@code 0.1..0.9} 之内，落点必然落在内缩矩形里，不需要额外夹取。
     *
     * <p>仍然夹取一次是为了配置被改动的情形（例如把 {@code max-deviation} 调到 0.6）：
     * 那时超出内缩区的样本会贴到边界，而不是落到元素外面。
     *
     * <p>元素没有面积时返回中心点：此时任何偏移都可能是错的，中心至少是"最像要点的位置"。
     */
    public static HumanAim gauss(Box box, HumanizeConfig.Gauss jitter, Rng rng) {
        if (box.degenerate()) {
            return center(box);
        }
        double insetX = box.width() * INSET_RATIO;
        double insetY = box.height() * INSET_RATIO;
        double usableWidth = box.width() - 2 * insetX;
        double usableHeight = box.height() - 2 * insetY;

        double relativeX = clamp(rng.gauss(jitter));
        double relativeY = clamp(rng.gauss(jitter));

        return new HumanAim(box.x() + insetX + relativeX * usableWidth,
                box.y() + insetY + relativeY * usableHeight);
    }

    private static double clamp(double relative) {
        return Math.min(1, Math.max(0, relative));
    }
}
