<template>
  <div>
    <div class="page-head">
      <div>
        <h1>配置</h1>
        <p>模型提供商、AI 判定、平台搜索条件与值守规则</p>
      </div>
    </div>

    <div class="tabs">
      <button v-for="t in tabs" :key="t.key" class="tab" :class="{ active: tab === t.key }" @click="tab = t.key">
        {{ t.label }}
      </button>
    </div>

    <!-- ---------------------------------------------------- 模型提供商 -->
    <section v-show="tab === 'provider'">
      <div class="card">
        <div class="row" style="justify-content: space-between; align-items: flex-start">
          <div>
            <h2>模型提供商</h2>
            <p class="hint">配置的是提供商，不是单个模型。API Key 经 DPAPI 加密落库，接口只回发"是否已配置"</p>
          </div>
          <button class="btn sm" @click="openProvider()">新增</button>
        </div>

        <table v-if="providers.length">
          <thead>
            <tr>
              <th>名称</th>
              <th>Base URL</th>
              <th>模型</th>
              <th style="width: 90px">Key</th>
              <th style="width: 120px"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in providers" :key="p.id">
              <td>{{ p.name }}</td>
              <td class="hint-inline">{{ p.baseUrl }}</td>
              <td>{{ p.model }}</td>
              <td>
                <span class="tag" :class="p.keyConfigured ? 'ok' : 'danger'">
                  {{ p.keyConfigured ? '已配置' : '缺失' }}
                </span>
              </td>
              <td>
                <div class="row" style="gap: 6px; justify-content: flex-end">
                  <button class="btn sm" @click="openProvider(p)">编辑</button>
                  <button class="btn sm danger" @click="deleteProvider(p)">删除</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty">还没有模型提供商，先新增一个（DeepSeek 的 base URL 填 https://api.deepseek.com/v1）</div>
      </div>

      <!-- 新增与编辑共用同一个弹层：点遮罩或按 Esc 即为放弃，不落库 -->
      <Modal
        :open="providerDialog"
        :title="providerForm.id ? '编辑提供商' : '新增提供商'"
        hint="主键 ID 不展示；编辑时留空 API Key 表示不修改"
        width="620px"
        @close="closeProviderDialog"
      >
        <div class="grid cols-2">
          <div class="field">
            <label>名称</label>
            <input v-model="providerForm.name" type="text" placeholder="DeepSeek" />
          </div>
          <div class="field">
            <label>模型</label>
            <input v-model="providerForm.model" type="text" placeholder="deepseek-chat" />
          </div>
        </div>
        <div class="field">
          <label>Base URL</label>
          <input v-model="providerForm.baseUrl" type="text" placeholder="https://api.deepseek.com/v1" />
        </div>
        <div class="grid cols-2">
          <div class="field">
            <label>API Key</label>
            <input v-model="providerForm.apiKey" type="password" :placeholder="providerForm.id ? '留空则不修改' : 'sk-...'" />
          </div>
          <div class="field">
            <label>超时（秒）</label>
            <input v-model.number="providerForm.timeoutSeconds" type="number" min="5" max="300" />
          </div>
        </div>
        <div class="field">
          <label>自定义请求头</label>
          <div v-for="(h, i) in providerHeaders" :key="i" class="kv-row">
            <input v-model="h.key" type="text" placeholder="Header 名" />
            <input v-model="h.value" type="text" placeholder="值" />
            <button class="btn sm danger" @click="providerHeaders.splice(i, 1)">删除</button>
          </div>
          <button class="btn sm" @click="providerHeaders.push({ key: '', value: '' })">添加请求头</button>
        </div>

        <template #footer>
          <button class="btn" @click="closeProviderDialog">取消</button>
          <button class="btn primary" :disabled="saving" @click="saveProvider">保存提供商</button>
        </template>
      </Modal>
    </section>

    <!-- ---------------------------------------------------- AI 配置 -->
    <section v-show="tab === 'ai'">
      <div class="card">
        <h2>AI 配置</h2>
        <p class="hint">一次调用同时产出「投不投」与「招呼语」；判定失败即跳过该岗位（fail-closed）</p>

        <div class="grid cols-2">
          <div class="field">
            <label>使用哪个提供商</label>
            <select v-model.number="ai.providerId">
              <option :value="null">未选择</option>
              <option v-for="p in providers" :key="p.id" :value="p.id">{{ p.name }} · {{ p.model }}</option>
            </select>
          </div>
          <div class="field">
            <label>质量开关</label>
            <label class="switch" style="margin-top: 6px">
              <input v-model="ai.qualityGateEnabled" type="checkbox" />
              <span>开启时由 AI 判断该不该投；关闭时硬规则通过即用模板招呼语</span>
            </label>
          </div>
        </div>

        <div class="field">
          <label>个人介绍</label>
          <textarea v-model="ai.introduce" placeholder="描述技能、经验与专业背景，AI 用它生成个性化招呼语" />
        </div>

        <div class="field">
          <label>判定提示词</label>
          <textarea v-model="ai.promptTemplate" style="min-height: 220px" />
          <div class="desc">
            支持占位符：<code v-for="p in placeholders" :key="p">{{ p }}</code>。不要用 %s —— JD 里出现百分号会直接抛异常
          </div>
        </div>

        <div class="field">
          <label>模板招呼语（质量开关关闭时使用）</label>
          <input v-model="ai.templateGreeting" type="text" />
        </div>

        <div class="grid cols-3">
          <div class="field">
            <label>JD 截断字符数</label>
            <input v-model.number="ai.jdMaxChars" type="number" min="500" max="20000" />
          </div>
          <div class="field">
            <label>判定单次超时（秒）</label>
            <input v-model.number="ai.aiTimeoutSeconds" type="number" min="5" max="120" />
            <div class="desc">投递判定与招呼语的调用超时，与下方值守回复的超时是两件事</div>
          </div>
          <div class="field">
            <label>效果采集</label>
            <label class="switch" style="margin-top: 6px">
              <input v-model="ai.collectEffectMetrics" type="checkbox" />
              <span>记录首响时长与回复条数（仅统计）</span>
            </label>
          </div>
        </div>

      </div>

      <div class="card">
        <div class="row" style="justify-content: space-between; align-items: flex-start">
          <div>
            <h2>值守 AI 回复</h2>
            <p class="hint">
              HR 发来新消息时由 AI 生成回复。关闭时这部分整条链路不动作，值守行为与改造前完全一致
            </p>
          </div>
          <span class="tag" :class="ai.aiReplyEnabled ? 'ok' : ''">
            {{ ai.aiReplyEnabled ? '运行中' : '已关闭' }}
          </span>
        </div>

        <label class="switch">
          <input v-model="ai.aiReplyEnabled" type="checkbox" />
          <span>开启值守 AI 回复</span>
        </label>

        <div class="field" style="margin-top: 16px">
          <label>回复风格约束</label>
          <textarea
            v-model="ai.aiReplyStyle"
            :placeholder="aiStyleDefault"
            style="min-height: 150px"
          />
          <div class="desc">
            <template v-if="ai.aiReplyStyle && ai.aiReplyStyle.trim()">
              当前使用自定义风格（{{ ai.aiReplyStyle.trim().length }} 字）。改风格不等于收紧了出口，
              能不能发仍由出站校验决定
            </template>
            <template v-else>
              留空则使用默认风格。默认内容已显示在输入框的灰字里，共 {{ aiStyleDefault.length }} 字
            </template>
            <button class="btn sm" style="margin-left: 8px" @click="restoreStyle">填入默认</button>
          </div>
        </div>

        <div class="grid cols-2">
          <div class="field">
            <label>单次生成超时（秒）</label>
            <input v-model.number="ai.aiReplyTimeoutSeconds" type="number" min="1" max="120" />
            <div class="desc">
              模型迟迟不回时，等多久就放弃这条。超时会进重试链，不是静默跳过。
              <template v-if="effectiveReplyTimeout && effectiveReplyTimeout < ai.aiReplyTimeoutSeconds">
                <b>实际生效 {{ effectiveReplyTimeout }} 秒</b> —— 当前提供商的超时更短，会先断开
              </template>
            </div>
          </div>
          <div class="field">
            <label>单轮 AI 回复上限（条）</label>
            <input v-model.number="ai.aiReplyMaxPerSweep" type="number" min="1" max="10" />
            <div class="desc">
              一轮值守最多花几次模型调用。超出的未读退回待处理，不消耗重试次数，下一轮继续
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="row" style="justify-content: space-between; align-items: flex-start">
          <div>
            <h2>出站校验与告警</h2>
            <p class="hint">
              发出去之前的最后一道闸。AI 味检查管「像不像人话」，告警管「它是不是一直在躲」
            </p>
          </div>
          <span class="tag" :class="ai.aiReplyToneCheckEnabled ? 'ok' : 'danger'">
            {{ ai.aiReplyToneCheckEnabled ? '检查中' : '已停止' }}
          </span>
        </div>

        <label class="switch">
          <input v-model="ai.aiReplyToneCheckEnabled" type="checkbox" />
          <span>开启 AI 味检查（套话、客服腔、小标题一律拦下）</span>
        </label>

        <div class="field" style="margin-top: 16px">
          <label>AI 味词表（一行一条）</label>
          <textarea
            v-model="ai.aiReplyToneTerms"
            :placeholder="aiToneTermsDefault"
            style="min-height: 160px"
          />
          <div class="desc">
            <template v-if="ai.aiReplyToneTerms && ai.aiReplyToneTerms.trim()">
              当前使用自定义词表，生效 {{ toneTermsEffective }} 条。整条回复里出现任一条就会被拦下
            </template>
            <template v-else>
              留空则使用默认词表（{{ aiToneTermsEffectiveCount }} 条），内容已显示在输入框的灰字里。
              自定义词表会<b>整体替换</b>默认词表，不是追加
            </template>
            <button class="btn sm" style="margin-left: 8px" @click="restoreToneTerms">填入默认</button>
          </div>
          <div v-if="riskyToneHits.length" class="desc" style="color: var(--warn)">
            ⚠ 这几行是日常口语本身：{{ riskyToneHits.join('、') }}。
            词表按「整条回复含该词就拦」匹配，加了它会连「感谢您的回复」「方便发份简历吗」一起拦掉，
            建议删掉 —— 但仍可保存
          </div>
        </div>

        <div class="grid cols-2">
          <div class="field">
            <label>skip 占比告警阈值（%）</label>
            <input v-model.number="ai.aiReplySkipAlertPct" type="number" min="0" max="100" />
            <div class="desc">
              近 7 天 AI 参与判定里，主动不回的比例超过它就在系统页提示。<b>填 0 = 关闭告警</b>，
              关闭状态照样会显示出来，不会假装一切正常
            </div>
          </div>
          <div class="field">
            <label>告警最小样本数</label>
            <input v-model.number="ai.aiReplySkipAlertMinSample" type="number" min="1" max="1000" />
            <div class="desc">
              判定次数低于此数就不下结论，面板显示「样本不足」。宁可说不知道怎么判，
              也不把「还没法判断」渲染成「一切正常」
            </div>
          </div>
        </div>
      </div>

      <button class="btn primary" :disabled="saving" @click="saveAi">保存 AI 配置</button>
    </section>

    <!-- ---------------------------------------------------- 平台配置 -->
    <section v-show="tab === 'platform'">
      <div class="card">
        <h2>搜索条件</h2>
        <p class="hint">城市填中文名，执行时才换 code。六组筛选是全局一套、只叠加不展开</p>
        <div class="grid cols-2">
          <div class="field">
            <label>城市</label>
            <ChipInput v-model="platform.cities" placeholder="输入城市名后回车，如：福州" :options="dict.city" />
          </div>
          <div class="field">
            <label>关键词</label>
            <ChipInput v-model="platform.keywords" placeholder="输入关键词后回车，如：Java" />
          </div>
        </div>
        <div class="field">
          <label>职位类型</label>
          <select v-model="platform.jobType">
            <option value="">不限</option>
            <option v-for="o in dict.jobType" :key="o" :value="o">{{ o }}</option>
          </select>
        </div>
        <div class="grid cols-3">
          <div class="field">
            <label>薪资</label>
            <ChipInput v-model="platform.salary" placeholder="不限" :options="dict.salary" />
          </div>
          <div class="field">
            <label>经验</label>
            <ChipInput v-model="platform.experience" placeholder="不限" :options="dict.experience" />
          </div>
          <div class="field">
            <label>学历</label>
            <ChipInput v-model="platform.degree" placeholder="不限" :options="dict.degree" />
          </div>
          <div class="field">
            <label>公司规模</label>
            <ChipInput v-model="platform.scale" placeholder="不限" :options="dict.scale" />
          </div>
          <div class="field">
            <label>行业</label>
            <ChipInput v-model="platform.industry" placeholder="不限" :options="dict.industry" />
          </div>
          <div class="field">
            <label>融资阶段</label>
            <ChipInput v-model="platform.stage" placeholder="不限" :options="dict.stage" />
          </div>
        </div>
        <div class="field">
          <label>HR 活跃度下限</label>
          <select v-model="platform.activeLevelFloor" style="width: 220px">
            <option v-for="o in platform.activeLevelOptions || []" :key="o" :value="o">{{ o }}</option>
          </select>
          <div class="desc">
            平台文案没有「7 天」这一档，只能按档位定；映射表之外的文案归为「未知」，不参与硬过滤
          </div>
        </div>
      </div>

      <div class="card">
        <h2>配额与节奏</h2>
        <p class="hint">四个上限各管一件事：投递管产出与风险、评估管成本与暴露、轮数只管防死循环、未读扫描管值守能看到多少人</p>
        <div class="grid cols-3">
          <div class="field">
            <label>日投递配额</label>
            <input v-model.number="platform.dailyDeliveryQuota" type="number" min="1" max="200" />
          </div>
          <div class="field">
            <label>日评估上限</label>
            <input v-model.number="platform.dailyEvalLimit" type="number" min="10" max="1000" />
          </div>
          <div class="field">
            <label>动作间隔（秒）</label>
            <input v-model.number="platform.waitTimeSeconds" type="number" min="3" max="60" />
            <div class="desc">实际等待为 [间隔/2, 间隔] 的随机值</div>
          </div>
          <div class="field">
            <label>单组合滚动上限（轮）</label>
            <input v-model.number="platform.scrollRoundLimit" type="number" min="20" max="2000" />
          </div>
          <div class="field">
            <label>单组合时长上限（秒）</label>
            <input v-model.number="platform.combinationTimeLimitSec" type="number" min="120" max="7200" />
          </div>
          <div class="field">
            <label>未读列表扫描上限（条）</label>
            <input v-model.number="platform.unreadScanLimit" type="number" min="1" max="500" />
            <div class="desc">
              一轮值守最多看多少个会话。曾经写死 30 条：列表第 31 个之后的人永远收不到回复，也不报错。超出会打警告日志
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <h2>值守时段</h2>
        <p class="hint">窗口外不发任何消息——凌晨的招呼语只会被当成骚扰。到点自动收工，不在窗口内也不允许启动</p>
        <div class="grid cols-2">
          <div class="field">
            <label>开始</label>
            <input v-model="platform.dutyStartTime" type="time" step="60" />
          </div>
          <div class="field">
            <label>结束</label>
            <input v-model="platform.dutyEndTime" type="time" step="60" />
          </div>
        </div>
        <div class="desc">{{ dutyHint }}</div>
      </div>

      <div class="card">
        <h2>重复沟通</h2>
        <p class="hint">进会话后先读消息列表：已有我方消息就视为已沟通过，默认跳过</p>
        <label class="switch">
          <input v-model="platform.repeatChatEnabled" type="checkbox" />
          <span>允许对已沟通的会话再发一条跟进语</span>
        </label>
        <div class="field" style="margin-top: 16px">
          <label>跟进语文案池（随机取一条）</label>
          <ChipInput v-model="platform.repeatChatMessages" placeholder="输入一条跟进语后回车" />
        </div>
      </div>

      <div class="card">
        <h2>黑名单</h2>
        <p class="hint">在「岗位」页维护，与这里共用同一份数据</p>
        <div v-if="!blacklist.length" class="empty">还没有黑名单</div>
        <div v-else class="row wrap" style="gap: 8px">
          <span v-for="b in blacklist" :key="b.id" class="chip" :class="b.kind === 'company' ? '' : 'danger'">
            {{ b.keyword }}
            <button @click="removeBlacklist(b)">×</button>
          </span>
        </div>
      </div>

      <button class="btn primary" :disabled="saving" @click="savePlatform">保存平台配置</button>
    </section>

    <!-- ---------------------------------------------------- 值守规则 -->
    <section v-show="tab === 'reply'">
      <ReplyRules />
    </section>
  </div>
</template>

<script setup lang="ts">
const { call } = useApi()
const { show } = useToast()

const tabs = [
  { key: 'provider', label: '模型提供商' },
  { key: 'ai', label: 'AI 配置' },
  { key: 'platform', label: '平台配置' },
  { key: 'reply', label: '值守规则' }
]
const tab = ref('provider')
const saving = ref(false)

// 占位符作为数据渲染，不写字面量花括号：Vue 的插值扫描器遇到字符串里的 }} 会提前截断表达式
const placeholders = ['{{introduce}}', '{{jobName}}', '{{company}}', '{{salary}}', '{{activeLevel}}', '{{blacklist}}', '{{jd}}']

const providers = ref<any[]>([])
const blacklist = ref<any[]>([])

const providerForm = reactive<any>({
  id: null,
  name: '',
  baseUrl: 'https://api.deepseek.com/v1',
  model: 'deepseek-chat',
  apiKey: '',
  timeoutSeconds: 60
})
const providerHeaders = ref<{ key: string; value: string }[]>([])
const providerDialog = ref(false)

const ai = reactive<any>({
  providerId: null,
  introduce: '',
  promptTemplate: '',
  greetingPrompt: '',
  qualityGateEnabled: true,
  templateGreeting: '',
  jdMaxChars: 4000,
  aiTimeoutSeconds: 20,
  collectEffectMetrics: false,
  aiReplyEnabled: false,
  aiReplyStyle: '',
  aiReplyTimeoutSeconds: 8,
  aiReplyMaxPerSweep: 5,
  aiReplyToneCheckEnabled: true,
  aiReplyToneTerms: '',
  aiReplySkipAlertPct: 60,
  aiReplySkipAlertMinSample: 10
})

// 默认风格与默认词表都由后端提供唯一一份（AiReplier.DEFAULT_STYLE / ReplyGuard.DEFAULT_TONE_TERMS），
// 前端只负责展示，不在前端复制文案 —— 两处各写一份必然漂移
const aiStyleDefault = ref('')
const aiToneTermsDefault = ref('')
const aiToneTermsEffectiveCount = ref(0)

/** 输入框为空时生效条数是默认条数，不是 0 —— 只显示「空」会让人以为检查没在工作 */
const toneTermsEffective = computed(() => {
  const text = (ai.aiReplyToneTerms || '').trim()
  if (!text) return aiToneTermsEffectiveCount.value
  return new Set(text.split(/\r?\n/).map((s: string) => s.trim()).filter(Boolean)).size
})

/**
 * 高频口语词。词表是「整条回复包含该词就拦」的匹配方式，所以单独把「感谢」加成一行，
 * 会把所有含「感谢」的正常回复一起拦掉。
 *
 * <p>判据是<b>整行等于</b>该词，不是「行里包含」：默认词表里本来就有「如有需要请随时」，
 * 用包含判定会让用户点了「填入默认」就看到一条假警告，进而怀疑默认词表有问题。
 */
const RISKY_TONE_TERMS = ['感谢', '您好', '你好', '好的', '方便', '可以', '稍等', '随时', '没问题']

const riskyToneHits = computed(() => {
  const text = (ai.aiReplyToneTerms || '').trim()
  if (!text) return []
  const lines = text.split(/\r?\n/).map((s: string) => s.trim()).filter(Boolean)
  return RISKY_TONE_TERMS.filter((word) => lines.includes(word))
})

const platform = reactive<any>({
  cities: [],
  keywords: [],
  jobType: '',
  salary: [],
  experience: [],
  degree: [],
  scale: [],
  industry: [],
  stage: [],
  activeLevelFloor: '本月',
  activeLevelOptions: [],
  waitTimeSeconds: 10,
  dailyDeliveryQuota: 30,
  dailyEvalLimit: 150,
  scrollRoundLimit: 300,
  combinationTimeLimitSec: 1200,
  unreadScanLimit: 100,
  repeatChatEnabled: false,
  repeatChatMessages: [],
  dutyStartTime: '09:00',
  dutyEndTime: '22:00'
})

// 回复超时在 AiReplier 里还要被提供商超时压一道（取较小值）。
// 只信输入框就会以为「设 120 就是 120」，所以把算完的真实值显示出来
const effectiveReplyTimeout = computed(() => {
  const configured = Number(ai.aiReplyTimeoutSeconds)
  if (!Number.isInteger(configured) || configured < 1) return null
  const p = (providers.value || []).find((x: any) => String(x.id) === String(ai.providerId))
  if (!p) return null
  const providerTimeout = Number(p.timeoutSeconds) || 60
  return Math.min(configured, Math.max(1, providerTimeout))
})

// 值守时段的语义有三种（当天 / 跨天 / 全天），输入完直接把结果说出来，避免用户靠猜
const dutyHint = computed(() => {
  const start = platform.dutyStartTime || ''
  const end = platform.dutyEndTime || ''
  if (!start || !end) return '留空按默认 09:00–22:00 处理'
  if (start === end) return '开始与结束相同 = 全天不限，凌晨也会发消息'
  return start < end
    ? `每天 ${start}–${end} 发消息，其余时间扫描与投递一起收工`
    : `跨天窗口：${start} 到次日 ${end}`
})

const dict = reactive<Record<string, string[]>>({
  city: [],
  jobType: [],
  salary: [],
  experience: [],
  degree: [],
  scale: [],
  industry: [],
  stage: []
})

async function loadAll() {
  try {
    const [p, a, c, b] = await Promise.all([
      call('/config/providers'),
      call('/config/ai'),
      call('/config/platform/boss'),
      call('/blacklist')
    ])
    providers.value = p || []
    Object.assign(ai, a || {})
    // 后端可能回 null（列可空）。textarea 绑 null 会渲染成空但状态不一致，
    // 统一归一化成空串 —— 空串的语义是「没配」，与「关闭」无关
    ai.aiReplyStyle = a?.aiReplyStyle ?? ''
    ai.aiReplyToneTerms = a?.aiReplyToneTerms ?? ''
    aiStyleDefault.value = (a?.aiReplyStyleDefault ?? '').trim()
    aiToneTermsDefault.value = (a?.aiReplyToneTermsDefault ?? '').trim()
    aiToneTermsEffectiveCount.value = a?.aiReplyToneTermsEffectiveCount ?? 0
    Object.assign(platform, c || {})
    blacklist.value = b || []
    await loadDictionary()
  } catch (e: any) {
    show(e.message, true)
  }
}

async function loadDictionary() {
  const types = Object.keys(dict)
  await Promise.all(
    types.map(async (type) => {
      try {
        const items = await call('/config/dictionary', { query: { platform: 'boss', type } })
        dict[type] = (items || []).map((i: any) => i.name)
      } catch {
        dict[type] = []
      }
    })
  )
}

/** p 省略 = 新增；传 p = 编辑 */
function openProvider(p?: any) {
  if (p) editProvider(p)
  else newProvider()
  providerDialog.value = true
}

/** 点遮罩 / Esc / 取消：直接丢弃草稿，不发任何请求 */
function closeProviderDialog() {
  providerDialog.value = false
  newProvider()
}

function newProvider() {
  providerForm.id = null
  providerForm.name = ''
  providerForm.baseUrl = 'https://api.deepseek.com/v1'
  providerForm.model = 'deepseek-chat'
  providerForm.apiKey = ''
  providerForm.timeoutSeconds = 60
  providerHeaders.value = []
}

function editProvider(p: any) {
  providerForm.id = p.id
  providerForm.name = p.name
  providerForm.baseUrl = p.baseUrl
  providerForm.model = p.model
  providerForm.apiKey = ''
  providerForm.timeoutSeconds = p.timeoutSeconds || 60
  providerHeaders.value = Object.entries(p.extraHeaders || {}).map(([key, value]) => ({
    key,
    value: String(value)
  }))
}

async function saveProvider() {
  saving.value = true
  try {
    const headers: Record<string, string> = {}
    providerHeaders.value.forEach((h) => {
      if (h.key.trim()) headers[h.key.trim()] = h.value
    })
    await call('/config/providers', {
      method: 'POST',
      body: {
        id: providerForm.id,
        name: providerForm.name,
        baseUrl: providerForm.baseUrl,
        model: providerForm.model,
        apiKey: providerForm.apiKey || null,
        timeoutSeconds: providerForm.timeoutSeconds,
        extraHeaders: headers,
        enabled: true
      }
    })
    show('提供商已保存')
    await loadAll()
    closeProviderDialog()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    saving.value = false
  }
}

async function deleteProvider(p: any) {
  if (!confirm(`删除提供商「${p.name}」？`)) return
  try {
    await call(`/config/providers/${p.id}`, { method: 'DELETE' })
    show('已删除')
    await loadAll()
  } catch (e: any) {
    show(e.message, true)
  }
}

/** 把默认风格填进输入框，不落库 —— 保存动作留给用户，与「点了就改配置」区分开 */
function restoreStyle() {
  if (!aiStyleDefault.value) {
    show('默认风格尚未加载，请刷新页面重试', true)
    return
  }
  ai.aiReplyStyle = aiStyleDefault.value
}

/** 同上：只填框，由用户自己保存 */
function restoreToneTerms() {
  if (!aiToneTermsDefault.value) {
    show('默认词表尚未加载，请刷新页面重试', true)
    return
  }
  ai.aiReplyToneTerms = aiToneTermsDefault.value
}

async function saveAi() {
  // 数字框被清空时 v-model.number 会变成空串，直接 PUT 过去是 400。
  // 不去猜默认值填回去：空着就是用户没填，拦住并说清楚比静默改值诚实
  const timeout = Number(ai.aiReplyTimeoutSeconds)
  const maxPerSweep = Number(ai.aiReplyMaxPerSweep)
  const alertPct = Number(ai.aiReplySkipAlertPct)
  const minSample = Number(ai.aiReplySkipAlertMinSample)
  if (!Number.isInteger(timeout) || timeout < 1) {
    show('单次生成超时要填 ≥1 的整数', true)
    return
  }
  if (!Number.isInteger(maxPerSweep) || maxPerSweep < 1) {
    show('单轮 AI 回复上限要填 ≥1 的整数', true)
    return
  }
  // 阈值 0 是合法值（= 关闭告警），所以这里不能用 `!alertPct` 判断
  if (!Number.isInteger(alertPct) || alertPct < 0) {
    show('告警阈值要填 0–100 的整数，0 表示关闭告警', true)
    return
  }
  if (!Number.isInteger(minSample) || minSample < 1) {
    show('告警最小样本数要填 ≥1 的整数', true)
    return
  }

  saving.value = true
  try {
    const res: any = await call('/config/ai', {
      method: 'PUT',
      body: {
        ...ai,
        aiReplyStyle: ai.aiReplyStyle ?? '',
        aiReplyToneTerms: ai.aiReplyToneTerms ?? '',
        aiReplyTimeoutSeconds: timeout,
        aiReplyMaxPerSweep: maxPerSweep,
        aiReplySkipAlertPct: alertPct,
        aiReplySkipAlertMinSample: minSample
      }
    })
    // 后端会丢弃超长行（一行 50 字基本是误粘的整段文本）。不说一声，用户会以为整段都生效了
    if (res?.toneTermsDropped > 0) {
      show(`已保存，但有 ${res.toneTermsDropped} 行超过 20 字被丢弃`, true)
    } else {
      show('AI 配置已保存')
    }
    // 回读一次：超时上限 120、回复上限 10 这类 clamp 在后端做，前端不该假装自己知道结果
    await loadAll()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    saving.value = false
  }
}

async function savePlatform() {
  saving.value = true
  try {
    await call('/config/platform/boss', { method: 'PUT', body: platform })
    show('平台配置已保存')
  } catch (e: any) {
    show(e.message, true)
  } finally {
    saving.value = false
  }
}

async function removeBlacklist(item: any) {
  try {
    await call(`/blacklist/${item.id}`, { method: 'DELETE' })
    const b = await call('/blacklist')
    blacklist.value = b || []
  } catch (e: any) {
    show(e.message, true)
  }
}

onMounted(loadAll)
</script>
