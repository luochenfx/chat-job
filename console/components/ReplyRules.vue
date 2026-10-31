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
          <p class="hint">
            一条规则 = 几个关键词 + 一组准备好的回复。同时命中多条规则时，用关键词最长的那条；
            同一个对话 3 分钟内最多回一次
          </p>
        </div>
        <button class="btn sm" @click="openRule()">添加规则</button>
      </div>

      <div v-if="!rules.length" class="empty">还没有添加规则</div>
      <div v-else class="stack">
        <div v-for="rule in rules" :key="rule.id" class="rule-row">
          <div class="row" style="justify-content: space-between; align-items: flex-start">
            <div>
              <strong style="font-size: 13.5px">{{ rule.name }}</strong>
              <span v-if="!rule.enabled" class="tag" style="margin-left: 8px">已关闭</span>
              <span v-if="rule.action === 'SEND_RESUME'" class="tag" style="margin-left: 8px">
                会真的发简历
              </span>
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
            <template v-if="rule.action === 'SEND_RESUME'">
              触发后直接点平台的「发简历」按钮，不发送任何文字。下面这句只在按钮点不了时才会用到：
              <span style="color: var(--ink)">{{ rule.texts[0] }}</span>
            </template>
            <template v-else>
              准备了 {{ rule.texts.length }} 条回复，每次随机用一条：
              <span style="color: var(--ink)">{{ rule.texts[0] }}</span>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- 新增与编辑共用同一个弹层：点遮罩或按 Esc 即为放弃，不落库 -->
    <Modal
      :open="dialogOpen"
      :title="form.id ? '编辑规则' : '添加规则'"
      hint="消息里出现任意一个关键词就算命中这条规则；每次随机取一条回复，避免同一句话发给几十个 HR"
      @close="closeDialog"
    >
      <div class="field">
        <label>规则名称</label>
        <input v-model="form.name" type="text" placeholder="约面试" />
      </div>
      <div class="field">
        <label>是否启用</label>
        <label class="switch">
          <input v-model="form.enabled" type="checkbox" />
          <span>{{ form.enabled ? '生效中' : '已关闭' }}</span>
        </label>
      </div>
      <div class="field">
        <label>触发后做什么</label>
        <select v-model="form.action">
          <option value="REPLY">回一句话</option>
          <option value="SEND_RESUME">发简历（真的点按钮，不发文字）</option>
        </select>
        <div v-if="form.action === 'SEND_RESUME'" class="desc">
          触发后直接点平台的「发简历」按钮，发完不再补话。只有在按钮点不了时才会发送文字，
          比如平台要求双方先说过话才能发简历
        </div>
      </div>
      <div class="field">
        <label>关键词</label>
        <ChipInput v-model="form.keywords" placeholder="输入关键词后回车，如：面试" />
      </div>
      <div class="field">
        <label>{{ form.action === 'SEND_RESUME' ? '按钮点不了时发的文字' : '准备好的回复' }}</label>
        <ChipInput v-model="form.texts" placeholder="输入一条回复后回车" />
        <div class="desc">单条建议不超过 60 个字，只发文字，不带表情</div>
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
          <h2>没有回复的消息</h2>
          <p class="hint">
            最近 7 天里没有回复对方的消息。关闭 AI 回复时，这里只有「没有匹配到规则」这一种；
            开启后还包括 AI 判断不需要回、AI 没有正常返回、被发送前检查拦下。
            这些都是补规则的好材料，常出现的说法可以直接变成关键词
          </p>
        </div>
        <button class="btn sm" :disabled="busy" @click="loadUnmatched">刷新</button>
      </div>
      <div v-if="!unmatched.length" class="empty">最近没有漏回的消息</div>
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
              <button class="btn sm" @click="promote(m)">用它建一条规则</button>
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

const form = reactive<any>({
  id: null,
  name: '',
  keywords: [],
  texts: [],
  enabled: true,
  action: 'REPLY'
})

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
  NO_SUBSTANCE: '没有需要回答的内容',
  SENSITIVE_TOPIC: '话题敏感',
  ALREADY_HANDLED: '之前已经聊过',
  LOW_CONFIDENCE: 'AI 不确定怎么回',
  HUMAN_REQUIRED: '需要你自己回'
}

function reasonText(m: any) {
  const action = m.action || ''
  if (action === 'AI_SKIPPED') {
    const why = REASON_TEXT[m.reasonCode] || m.reasonCode || '没有给出原因'
    return `AI 判断不用回 · ${why}`
  }
  if (action === 'AI_FAILED') {
    return 'AI 没有正常返回'
  }
  if (action.startsWith('BLOCKED_')) {
    const kind = action.slice('BLOCKED_'.length)
    const map: Record<string, string> = {
      TONE: '听起来像 AI 写的',
      CONTACT: '含联系方式',
      SENSITIVE: '敏感词',
      TOO_LONG: '字数太多',
      FALSE_CLAIM: '说了没做过的事'
    }
    return `发送前被拦下 · ${map[kind] || kind}`
  }
  if (action === 'UNMATCHED') {
    return '没有匹配到规则'
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
  form.action = 'REPLY'
}

function editRule(rule: any) {
  form.id = rule.id
  form.name = rule.name
  form.keywords = [...rule.keywords]
  form.texts = [...rule.texts]
  form.enabled = rule.enabled
  form.action = rule.action || 'REPLY'
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
  form.action = 'REPLY'
  dialogOpen.value = true
  show('已填入草稿，补上关键词和回复内容后保存')
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
