<template>
  <!--
    7 日走势：一天一个点，横向按 offset 摆，缺的那天不占位置也不连线。
    纯展示组件 —— 数据由概览卡整份下发，这里不取数、不轮询。
  -->
  <div class="spark-wrap">
    <svg
      v-if="points.length"
      class="spark"
      viewBox="0 0 100 18"
      preserveAspectRatio="none"
      role="img"
      :aria-label="`近 ${days} 天${label}走势`"
    >
      <!-- 一段一天边界的折线：相邻两天才连，隔天就断开（ADR-0018 第 6 条） -->
      <path v-for="(d, i) in segments" :key="i" class="spark-line" :d="d" />
      <circle
        v-for="p in dots"
        :key="`dot-${p.day}`"
        class="spark-dot"
        :class="{ warn: p.warn, last: p.last }"
        :cx="p.x"
        :cy="p.y"
        :r="p.last ? 1.5 : 1.1"
      />
      <!-- 悬停读数：透明命中区比 1px 的点好点中 -->
      <rect
        v-for="p in dots"
        :key="`hit-${p.day}`"
        class="spark-hit"
        :x="p.x - slotWidth / 2"
        y="0"
        :width="slotWidth"
        height="18"
      >
        <title>{{ p.text }}</title>
      </rect>
    </svg>
    <!-- 从没跑过的平台画成空槽位，不是一条贴着 0 的直线 -->
    <div v-else class="spark-empty">还没有记录</div>
  </div>
</template>

<script setup lang="ts">
/**
 * 概览卡里的 7 日走势（ADR-0018）。
 *
 * <p>只回答「这几天投递量是升是降」，所以不画坐标轴、不画刻度、不显示百分比：
 * 绝对数加一个窗口，够做「今天要不要调配额」这个判断。
 *
 * <p>纵向以今天的上限（配额 / 评估上限）为框高，历史某天超过它时按历史最大值取框高
 * —— 不做截断，因为把超出的点压平会把「那天投得比现在还多」画成「和今天一样」。
 */
interface CounterPoint {
  day: string
  offset: number
  delivered: number
  evaluated: number
}

const props = withDefaults(defineProps<{
  points: CounterPoint[]
  /** 窗口长度，用于横向位置；来自接口的 series.days */
  days?: number
  metric: 'delivered' | 'evaluated'
  /** 今天这一项的上限（配额 / 评估上限），没有就不给「到顶」这一档色 */
  ceiling?: number | null
  /** 读数的单位说明，只进 title 与 aria-label */
  label: string
}>(), { days: 7, ceiling: null })

const H = 18
const PAD = 2
const TOP = 3
const BOTTOM = 3

const maxOffset = computed(() => Math.max(1, props.days - 1))
const plotWidth = computed(() => 100 - PAD * 2)
const slotWidth = computed(() => plotWidth.value / maxOffset.value)

const values = computed(() => props.points.map(p => p[props.metric] || 0))
const yMax = computed(() => {
  const cap = props.ceiling && props.ceiling > 0 ? props.ceiling : 0
  return Math.max(cap, ...values.value, 1)
})

/** offset 6 在最左、0 在最右：横轴是「几天前」，不是数组下标 */
function xOf(offset: number) {
  return PAD + (maxOffset.value - offset) * slotWidth.value
}

function yOf(value: number) {
  return H - BOTTOM - (value / yMax.value) * (H - TOP - BOTTOM)
}

function round(n: number) {
  return Math.round(n * 100) / 100
}

/**
 * 折线按「日期是否连续」切段。
 *
 * <p>不连续就另起一段 `M`：缺口是「那天没跑」，不是「那天投了 0 个」；
 * 连过去会在图上凭空多出一段线性增长。
 */
const segments = computed(() => {
  const ordered = [...props.points].sort((a, b) => b.offset - a.offset)
  const out: string[] = []
  let run: string[] = []
  let prev: number | null = null
  for (const p of ordered) {
    const x = round(xOf(p.offset))
    const y = round(yOf(p[props.metric] || 0))
    if (prev !== null && prev - p.offset === 1) {
      run.push(`L${x} ${y}`)
    } else {
      if (run.length > 1) out.push(run.join(' '))
      run = [`M${x} ${y}`]
    }
    prev = p.offset
  }
  if (run.length > 1) out.push(run.join(' '))
  return out
})

/** 每个点都要画：只有一天有记录时没有折线可画，那颗点就是全部信息 */
const dots = computed(() => {
  const ceiling = props.ceiling && props.ceiling > 0 ? props.ceiling : 0
  // 最近的那个点：平台今天还没跑时它是昨天的，不该强行留在最右边
  const last = props.points.length ? Math.min(...props.points.map(p => p.offset)) : -1
  return props.points.map(p => {
    const value = p[props.metric] || 0
    return {
      day: p.day,
      offset: p.offset,
      x: round(xOf(p.offset)),
      y: round(yOf(value)),
      last: p.offset === last,
      warn: ceiling > 0 && p.offset === last && value >= ceiling,
      text: `${p.day.slice(5)} · ${props.label} ${value}`
    }
  })
})
</script>

<style scoped>
.spark-wrap {
  /* 与 .pstat-value 的基线留一点距离，同时把卡片高度固定在下发时说的 ~24px 内 */
  margin-top: 6px;
}

.spark {
  display: block;
  width: 100%;
  height: 18px;
}

/* 横向被 viewBox 拉伸，线宽靠 non-scaling-stroke 保持 1.25px 不跟着变粗 */
.spark-line {
  fill: none;
  stroke: var(--muted);
  stroke-width: 1.25;
  stroke-linecap: round;
  stroke-linejoin: round;
  vector-effect: non-scaling-stroke;
}

.spark-dot {
  fill: var(--muted);
}

/* 一整张图只有这一档语义色：最后一点到顶 */
.spark-dot.warn {
  fill: var(--warn);
}

.spark-hit {
  fill: transparent;
}

.spark-empty {
  height: 18px;
  line-height: 18px;
  font-size: 11.5px;
  color: var(--muted);
}
</style>
