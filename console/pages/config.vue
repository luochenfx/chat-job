<template>
  <div>
    <div class="page-head">
      <div>
        <h1>配置</h1>
        <p>模型提供商、AI 判定、平台搜索条件与值守规则</p>
      </div>
    </div>

    <div class="tabs">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="tab"
        :class="{ active: tab === t.key }"
        @click="switchTab(t.key)"
      >
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
            <input v-model="providerForm.model" type="text" placeholder="deepseek-flash" />
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
            支持占位符：<code v-for="p in placeholders" :key="p">{{ p }}</code>。
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
            <div class="desc">投递判定与招呼语的调用超时，与下方的值守回复的超时是两件事</div>
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
              HR 发来新消息时由 AI 生成回复。
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
              模型迟迟不回时，等多久就放弃这条。超时会进重试链，不会静默跳过。
              <template v-if="effectiveReplyTimeout && effectiveReplyTimeout < ai.aiReplyTimeoutSeconds">
                <b>实际生效 {{ effectiveReplyTimeout }} 秒</b> —— 当前提供商的超时更短，会先断开
              </template>
            </div>
          </div>
          <div class="field">
            <label>单轮 AI 回复上限（条）</label>
            <input v-model.number="ai.aiReplyMaxPerSweep" type="number" min="1" max="10" />
            <div class="desc">
              一轮值守最多花几次模型调用。超出的未读退回待处理，不消耗重试次数，下一轮继续。
              <!-- ADR-0003 第 4 条：两个平台各建一份预算，不共享。 -->
              <b>每个平台各自算一份</b>，{{ platforms.length }} 个平台同时值守时，一轮最多花
              {{ platforms.length || 1 }} × {{ ai.aiReplyMaxPerSweep || 0 }} 次
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="row" style="justify-content: space-between; align-items: flex-start">
          <div>
            <h2>出站校验与告警</h2>
            <p class="hint">
              消息发出去之前的最后一次检查。
            </p>
          </div>
          <span class="tag" :class="ai.aiReplyToneCheckEnabled ? 'ok' : 'danger'">
            {{ ai.aiReplyToneCheckEnabled ? '检查中' : '已停止' }}
          </span>
        </div>

        <label class="switch">
          <input v-model="ai.aiReplyToneCheckEnabled" type="checkbox" />
          <span>开启 AI 味检查（套话、客服腔、小标题等一律拦下）</span>
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
              判定次数低于此数就不下结论，面板会显示「样本不足」。
            </div>
          </div>
        </div>
      </div>

      <button class="btn primary" :disabled="saving" @click="saveAi">保存 AI 配置</button>
    </section>

    <!-- ---------------------------------------------------- 平台配置 -->
    <section v-show="tab === 'platform'">
      <!--
        切换器只在这个 tab 内：其余三个 tab 两平台共用，整页切换会造成
        「切到智联，AI 配置一个字没变」的错觉。控件与它作用的那几张卡同屏。
      -->
      <div class="scope-bar">
        <span class="scope-key">正在配置</span>
        <PlatformSwitch v-model="platformPick" :platforms="platforms" />
        <!--
          开关与其余字段同批保存：它不是「立刻生效的总闸」，而是这个平台配置的一部分。
          单独给开关做即时保存，就会出现「改了城市没保存、开关却已经关了」的错位。
        -->
        <label class="switch" :title="switchHint">
          <input v-model="platform.enabled" type="checkbox" />
          <span>{{ platform.enabled === false ? '已停用' : '已启用' }}</span>
        </label>
        <span class="scope-note">下面五张卡只属于这个平台；搜索条件与配额都是每平台一份</span>
        <span v-if="dirty" class="tag warn">有未保存改动</span>
      </div>

      <!-- 停用后果写在开关正下方：只写一个「已停用」标签，人不知道它关掉了什么 -->
      <div v-if="platform.enabled === false" class="off-note">
        停用后不再为「{{ platformLabel }}」开浏览器，也不启动它的扫描与值守；另一个平台照常跑。
        正在跑的任务要先停止，否则停用会被拒绝。
      </div>

      <div class="card">
        <h2>搜索条件</h2>
        <p class="hint">
          城市填中文名，执行时才换 code。{{ platformNote || '六组筛选是全局一套、只叠加不展开' }}
        </p>
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
        <div v-if="supports('jobType')" class="field">
          <label>职位类型</label>
          <select v-model="platform.jobType">
            <option value="">不限</option>
            <option v-for="o in dict.jobType" :key="o" :value="o">{{ o }}</option>
          </select>
        </div>
        <!-- 筛选组按平台能力渲染：智联这一期只支持城市 + 关键词，
             把 Boss 那六组摆出来会让人填一堆"看着生效、实际被忽略"的条件 -->
        <div v-if="hasExtraFilters" class="grid cols-3">
          <div v-if="supports('salary')" class="field">
            <label>薪资</label>
            <ChipInput v-model="platform.salary" placeholder="不限" :options="dict.salary" />
          </div>
          <div v-if="supports('experience')" class="field">
            <label>经验</label>
            <ChipInput v-model="platform.experience" placeholder="不限" :options="dict.experience" />
          </div>
          <div v-if="supports('degree')" class="field">
            <label>学历</label>
            <ChipInput v-model="platform.degree" placeholder="不限" :options="dict.degree" />
          </div>
          <div v-if="supports('scale')" class="field">
            <label>公司规模</label>
            <ChipInput v-model="platform.scale" placeholder="不限" :options="dict.scale" />
          </div>
          <div v-if="supports('industry')" class="field">
            <label>行业</label>
            <ChipInput v-model="platform.industry" placeholder="不限" :options="dict.industry" />
          </div>
          <div v-if="supports('stage')" class="field">
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
        <p class="hint">
          主动投递配额决定主动沟通多少岗位，评估上限决定查看多少岗位
          配额用完只是今天不再找岗位，HR 的消息照常回。
        </p>
        <div class="grid cols-3">
          <div class="field">
            <label>日主动投递配额</label>
            <input v-model.number="platform.dailyDeliveryQuota" type="number" min="1" max="200" />
            <div class="desc">只含扫描侧发起的投递；值守里应对方索要发的简历不计入</div>
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
              最多看多少个会话消息。超出会打警告日志
            </div>
          </div>
          <div class="field">
            <label>值守巡检间隔（秒）</label>
            <input v-model.number="platform.watchIntervalSeconds" type="number" min="30" max="600" />
            <div class="desc">
              配额达到上限后，按这个节奏检查未读消息（30–600，越界按最近边界保存）。
              这个数字同时是「AI 回复的最长延迟」和「刷新的频率」。120 秒 ≈ 最坏两分钟内回上；再短（如 60 秒）
              等于每 8 小时按分钟级刷新几百次，不像人在用，反而更容易被风控盯上。实际间隔会在 ±10% 内随机浮动。
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <h2>值守时段</h2>
        <p class="hint">
          时段外不发任何消息——凌晨的招呼语只会被当成骚扰。
          时段外点启动会把任务挂起，等窗口打开自动开始，期间可以随时取消。
        </p>
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
        <p class="hint">进会话后先读消息列表：已有我方最新消息就视为已沟通过，默认跳过</p>
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
        <h2>黑名单 · 两平台共用</h2>
        <p class="hint">在「岗位」页维护，与这里共用同一份数据；黑名单没有平台维度</p>
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

/**
 * tab 标题带作用域后缀。后缀比任何说明文字都有效：用户在 AI 配置页看不到平台切换器、
 * 标题又写着「两平台共用」，就不会去找开关。
 */
const tabs = computed(() => [
  { key: 'provider', label: '模型提供商 · 两平台共用' },
  { key: 'ai', label: 'AI 配置 · 两平台共用' },
  { key: 'platform', label: `平台配置 · ${platformLabel.value || '…'}` },
  { key: 'reply', label: '值守规则 · 两平台共用' }
])
const tab = ref('provider')
const saving = ref(false)

// 当前平台由 URL / localStorage / 清单决定；配置读写与字典查询都要跟着它走
const {
  platforms,
  ensure: ensurePlatform,
  platform: platformSlug,
  label: platformLabel,
  labelOf,
  note: platformNote,
  supports,
  select,
  refresh: refreshPlatforms
} = usePlatform()

/** 分段控件的 v-model：写的时候先过 dirty 拦截，再切换并重新加载该平台的配置 */
const platformPick = computed({
  get: () => platformSlug.value,
  set: (value: string) => {
    if (!value || value === platformSlug.value) return
    void switchPlatform(value)
  }
})

const hasExtraFilters = computed(() =>
  ['salary', 'experience', 'degree', 'scale', 'industry', 'stage'].some((key) => supports(key))
)

// 占位符作为数据渲染，不写字面量花括号：Vue 的插值扫描器遇到字符串里的 }} 会提前截断表达式
const placeholders = ['{{introduce}}', '{{jobName}}', '{{company}}', '{{salary}}', '{{activeLevel}}', '{{blacklist}}', '{{jd}}']

const providers = ref<any[]>([])
const blacklist = ref<any[]>([])

const providerForm = reactive<any>({
  id: null,
  name: '',
  baseUrl: 'https://api.deepseek.com/v1',
  model: 'deepseek-flash',
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

/**
 * 平台配置的默认值：字段全集取 BOSS 那一份。
 *
 * <p>切换平台时必须先回到它、再叠加响应 —— 直接 `Object.assign` 的话，切到智联时
 * `salary` / `experience` / `degree` 等字段在响应里不存在，BOSS 的旧值会留在对象上，
 * 接着点保存就被写进智联的配置行。那是数据污染，不只是显示问题。
 */
function defaultPlatformConfig() {
  return {
    // 开关与其余字段同属一份配置：没配过按启用（与 platform_config.enabled 的建表默认值 1 一致）
    enabled: true,
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
    watchIntervalSeconds: 120,
    repeatChatEnabled: false,
    repeatChatMessages: [],
    dutyStartTime: '09:00',
    dutyEndTime: '22:00'
  }
}

/** 开关的悬浮说明：停用后果不写在提示里，人就以为它只是「暂时不显示」 */
const switchHint = computed(() =>
  platform.enabled === false
    ? '停用：不再为它开浏览器，也不启动它的扫描与值守'
    : '启用：这个平台会参与扫描与值守'
)

const platform = reactive<any>(defaultPlatformConfig())

/** 加载完成时的快照：与当前值比较得出 dirty（切 tab / 切平台前据此拦截） */
const snapshot = ref(JSON.stringify(platform))
const dirty = computed(() => JSON.stringify(platform) !== snapshot.value)

/** 先回默认再叠加，随后拍快照 —— 之后任何改动都会让 dirty 变 true */
function applyPlatform(config: any) {
  Object.assign(platform, defaultPlatformConfig(), config || {})
  snapshot.value = JSON.stringify(platform)
}

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
    ? `每天 ${start}–${end}：窗口内启动立刻开跑、到 ${end} 自动收工；窗口外启动会挂起，等到下一次 ${start} 自动开始`
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
    await ensurePlatform()
    const [p, a, b] = await Promise.all([
      call('/config/providers'),
      call('/config/ai'),
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
    blacklist.value = b || []
    await loadPlatform()
  } catch (e: any) {
    show(e.message, true)
  }
}

/** 只重拉「当前平台」的配置与字典：切平台不需要把提供商与 AI 配置再拉一遍 */
async function loadPlatform() {
  const c = await call(`/config/platform/${platformSlug.value}`)
  applyPlatform(c)
  await loadDictionary()
}

/**
 * 切平台：先拦未保存的草稿，再切（写 URL 与 localStorage），然后重拉。
 *
 * <p>不拦的话草稿会被静默覆盖 —— 人改了一半、切过去看一眼别的平台、再切回来，
 * 改动已经没了，而且没有任何痕迹说明它是什么时候没的。
 */
async function switchPlatform(slug: string) {
  if (dirty.value && !confirm(`有未保存的平台配置改动，放弃并切换到 ${labelOf(slug)}？`)) return
  try {
    await select(slug)
    await loadPlatform()
  } catch (e: any) {
    show(e.message, true)
  }
}

/** 切 tab 同样要拦：离开「平台配置」时草稿会被下一次加载覆盖 */
function switchTab(key: string) {
  if (key === tab.value) return
  if (tab.value === 'platform' && dirty.value && !confirm('有未保存的平台配置改动，放弃并切换？')) return
  tab.value = key
}

async function loadDictionary() {
  const types = Object.keys(dict)
  await Promise.all(
    types.map(async (type) => {
      try {
        const items = await call('/config/dictionary', { query: { platform: platformSlug.value, type } })
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
  providerForm.model = 'deepseek-flash'
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
    await call(`/config/platform/${platformSlug.value}`, { method: 'PUT', body: platform })
    show('平台配置已保存')
    // 开关变了要立刻重拉清单：否则分段控件上那个「已停用」要等下次进页面才出现
    await refreshPlatforms().catch(() => {})
    // 回读一次：后端对 watchIntervalSeconds 等字段有钳制，不回读的话界面显示的是用户填的、
    // 库里存的是钳制后的 —— 两个数字不一致，而且人会按界面上那个数去推算行为
    await loadPlatform()
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

<style scoped>
/* 平台作用域条：紧贴「平台配置」tab 的内容顶部，让「这几张卡只属于这个平台」可见 */
.scope-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: var(--gap);
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

/* 停用后果：紧跟在开关那一行下面，与作用域条同宽、但视觉上从属于它 */
.off-note {
  margin: -6px 0 var(--gap);
  padding: 9px 14px;
  border: 1px solid var(--line);
  border-left: 3px solid var(--warn, #b7791f);
  border-radius: var(--radius);
  background: var(--surface-2);
  font-size: 12px;
  color: var(--ink-2);
}

.scope-key {
  font-size: 12px;
  color: var(--muted);
}

.scope-note {
  font-size: 12px;
  color: var(--muted);
}
</style>
