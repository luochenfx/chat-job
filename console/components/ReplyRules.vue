<template>
  <div>
    <!-- 敏感词：一票否决，视觉上与普通关键词区分开 -->
    <div class="card">
      <h2>敏感词</h2>
      <p class="hint">命中即整条不回复。优先级高于所有关键词规则 —— 否则「你们薪资多少」会同时命中敏感词和某条关键词</p>
      <div class="row" style="margin-bottom: 14px">
        <input v-model="newWord" type="text" placeholder="例如：薪资、押金、培训费" @keyup.enter="addWord" />
        <button class="btn danger" :disabled="!newWord.trim()" @click="addWord">添加</button>
      </div>
      <div v-if="!words.length" class="empty">还没有敏感词</div>
      <div v-else class="row wrap" style="gap: 8px">
        <span v-for="w in words" :key="w.id" class="chip danger">
          {{ w.word }}
          <button @click="removeWord(w)">×</button>
        </span>
      </div>
    </div>

    <!-- 回复规则 -->
    <div class="card">
      <div class="row" style="justify-content: space-between; align-items: flex-start">
        <div>
          <h2>回复规则</h2>
          <p class="hint">一条规则 = 关键词组 + 文案池。命中多个规则时取关键词最长的那条；同会话 3 分钟内最多回一次</p>
        </div>
        <button class="btn sm" @click="openRule()">新增规则</button>
      </div>

      <div v-if="!rules.length" class="empty">还没有规则</div>
      <div v-else class="stack">
        <div v-for="rule in rules" :key="rule.id" class="rule-row">
          <div class="row" style="justify-content: space-between; align-items: flex-start">
            <div>
              <strong style="font-size: 13.5px">{{ rule.name }}</strong>
              <span v-if="!rule.enabled" class="tag" style="margin-left: 8px">已关闭</span>
            </div>
            <div class="row" style="gap: 6px">
              <button class="btn sm" @click="openRule(rule)">编辑</button>
              <button class="btn sm danger" @click="deleteRule(rule)">删除</button>
            </div>
          </div>
          <div class="row wrap" style="gap: 6px; margin-top: 8px">
            <span v-for="k in rule.keywords" :key="k" class="chip">{{ k }}</span>
          </div>
          <div class="hint-inline" style="margin-top: 8px">
            文案 {{ rule.texts.length }} 条 · 本次命中会随机取一条：
            <span style="color: var(--ink)">{{ rule.texts[0] }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 新增与编辑共用同一个弹层：点遮罩或按 Esc 即为放弃，不落库 -->
    <Modal
      :open="dialogOpen"
      :title="form.id ? '编辑规则' : '新增规则'"
      hint="关键词组里任一命中即算命中该规则；文案池随机取一条，避免同一句话发给几十个 HR"
      @close="closeDialog"
    >
      <div class="field">
        <label>规则名</label>
        <input v-model="form.name" type="text" placeholder="约面试" />
      </div>
      <div class="field">
        <label>启用</label>
        <label class="switch">
          <input v-model="form.enabled" type="checkbox" />
          <span>{{ form.enabled ? '生效中' : '已关闭' }}</span>
        </label>
      </div>
      <div class="field">
        <label>关键词</label>
        <ChipInput v-model="form.keywords" placeholder="输入关键词后回车，如：面试" />
      </div>
      <div class="field">
        <label>文案池</label>
        <ChipInput v-model="form.texts" placeholder="输入一条回复文案后回车" />
        <div class="desc">单条建议不超过 60 字，纯文本，不带表情</div>
      </div>

      <template #footer>
        <button class="btn" @click="closeDialog">取消</button>
        <button class="btn primary" :disabled="saving" @click="saveRule">保存规则</button>
      </template>
    </Modal>

    <!-- 未命中：规则库唯一的进化路径，必须和规则配置放在同一页 -->
    <div class="card">
      <div class="row" style="justify-content: space-between; align-items: flex-start">
        <div>
          <h2>未命中消息</h2>
          <p class="hint">
            近 7 天没有回复出去的消息。AI 回复关闭时这里是「没匹配到规则」；
            开启后变成 AI 主动不回、AI 调用失败、被出站校验拦下 —— 三种都值得看，
            补规则就从这里开始，把高频说法变成关键词
          </p>
        </div>
        <button class="btn sm" :disabled="busy" @click="loadUnmatched">刷新</button>
      </div>
      <div v-if="!unmatched.length" class="empty">最近没有未命中消息</div>
      <table v-else>
        <thead>
          <tr>
            <th>收到的消息</th>
            <th style="width: 170px">原因</th>
            <th style="width: 130px">时间</th>
            <th style="width: 110px"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in unmatched" :key="m.id">
            <td>
              {{ m.incomingText }}
              <div v-if="m.reasonNote" class="hint-inline">{{ m.reasonNote }}</div>
            </td>
            <td class="hint-inline">{{ reasonText(m) }}</td>
            <td class="hint-inline">{{ (m.createdAt || '').replace('T', ' ').slice(0, 16) }}</td>
            <td>
              <button class="btn sm" @click="promote(m)">转为规则</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
const { call } = useApi()
const { show } = useToast()

const words = ref<any[]>([])
const rules = ref<any[]>([])
const unmatched = ref<any[]>([])
const newWord = ref('')
const saving = ref(false)
const busy = ref(false)
const dialogOpen = ref(false)

const form = reactive<any>({ id: null, name: '', keywords: [], texts: [], enabled: true })

async function loadAll() {
  try {
    const [w, r] = await Promise.all([call('/reply/sensitive'), call('/reply/rules')])
    words.value = w || []
    rules.value = r || []
    await loadUnmatched()
  } catch (e: any) {
    show(e.message, true)
  }
}

async function loadUnmatched() {
  busy.value = true
  try {
    unmatched.value = (await call('/reply/unmatched', { query: { days: 7, limit: 50 } })) || []
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

/**
 * 把落库动作翻成人话。原因码是枚举（NO_SUBSTANCE / SENSITIVE_TOPIC / ...），
 * 直接显示给运营看等于让他去翻代码。
 */
const REASON_TEXT: Record<string, string> = {
  NO_SUBSTANCE: '没什么可回的',
  SENSITIVE_TOPIC: '话题敏感',
  ALREADY_HANDLED: '已经聊过了',
  LOW_CONFIDENCE: '没把握怎么说',
  HUMAN_REQUIRED: '得人来定'
}

function reasonText(m: any) {
  const action = m.action || ''
  if (action === 'AI_SKIPPED') {
    const why = REASON_TEXT[m.reasonCode] || m.reasonCode || '未给出原因'
    return `AI 主动不回 · ${why}`
  }
  if (action === 'AI_FAILED') {
    return 'AI 调用失败'
  }
  if (action.startsWith('BLOCKED_')) {
    const kind = action.slice('BLOCKED_'.length)
    const map: Record<string, string> = {
      TONE: 'AI 味',
      CONTACT: '联系方式',
      SENSITIVE: '敏感词',
      TOO_LONG: '太长'
    }
    return `出站拦截 · ${map[kind] || kind}`
  }
  if (action === 'UNMATCHED') {
    return '未命中规则'
  }
  return action || '—'
}

async function addWord() {
  const word = newWord.value.trim()
  if (!word) return
  try {
    await call('/reply/sensitive', { method: 'POST', body: { word } })
    newWord.value = ''
    words.value = (await call('/reply/sensitive')) || []
  } catch (e: any) {
    show(e.message, true)
  }
}

async function removeWord(item: any) {
  try {
    await call(`/reply/sensitive/${item.id}`, { method: 'DELETE' })
    words.value = (await call('/reply/sensitive')) || []
  } catch (e: any) {
    show(e.message, true)
  }
}

function newRule() {
  form.id = null
  form.name = ''
  form.keywords = []
  form.texts = []
  form.enabled = true
}

function editRule(rule: any) {
  form.id = rule.id
  form.name = rule.name
  form.keywords = [...rule.keywords]
  form.texts = [...rule.texts]
  form.enabled = rule.enabled
}

/** rule 省略 = 新增；传 rule = 编辑 */
function openRule(rule?: any) {
  if (rule) editRule(rule)
  else newRule()
  dialogOpen.value = true
}

/** 点遮罩 / Esc / 取消：直接丢弃草稿，不发任何请求 */
function closeDialog() {
  dialogOpen.value = false
  newRule()
}

async function saveRule() {
  saving.value = true
  try {
    await call('/reply/rules', { method: 'POST', body: form })
    show('规则已保存')
    rules.value = (await call('/reply/rules')) || []
    closeDialog()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    saving.value = false
  }
}

async function deleteRule(rule: any) {
  if (!confirm(`删除规则「${rule.name}」？`)) return
  try {
    await call(`/reply/rules/${rule.id}`, { method: 'DELETE' })
    rules.value = (await call('/reply/rules')) || []
  } catch (e: any) {
    show(e.message, true)
  }
}

/** 把未命中的消息变成一条规则草稿：关键词留空由人补，文案直接借这条消息的思路 */
function promote(message: any) {
  form.id = null
  form.name = (message.incomingText || '').slice(0, 12)
  form.keywords = []
  form.texts = []
  form.enabled = true
  dialogOpen.value = true
  show('已填入草稿，补充关键词与文案后保存')
}

onMounted(loadAll)
</script>

<style scoped>
.rule-row {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 14px 16px;
}
</style>
