<template>
  <div>
    <div class="page-head">
      <div>
        <h1>配置</h1>
        <p>在这里填写 AI 服务、判断标准、每个平台的搜索条件和自动回复规则</p>
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
            <h2>AI 服务商</h2>
            <p class="hint">
              这里填的是提供 AI 服务的公司，不是某一个具体模型。密钥会加密保存在本机，
              页面上只显示「是否已填写」，不会显示内容
            </p>
          </div>
          <button class="btn sm" @click="openProvider()">添加</button>
        </div>

        <table v-if="providers.length">
          <thead>
            <tr>
              <th>名称</th>
              <th>接口地址</th>
              <th>模型</th>
              <th style="width: 90px">密钥</th>
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
                  {{ p.keyConfigured ? '已填写' : '未填写' }}
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
        <div v-else class="empty">
          还没有添加 AI 服务商，先添加一个。以 DeepSeek 为例，接口地址填 https://api.deepseek.com/v1
        </div>
      </div>

      <!-- 新增与编辑共用同一个弹层：点遮罩或按 Esc 即为放弃，不落库 -->
      <Modal
        :open="providerDialog"
        :title="providerForm.id ? '编辑 AI 服务商' : '添加 AI 服务商'"
        hint="编辑时密钥留空，表示保持原来的密钥不变"
        width="620px"
        @close="closeProviderDialog"
      >
        <div class="grid cols-2">
          <div class="field">
            <label>服务商名称</label>
            <input v-model="providerForm.name" type="text" placeholder="DeepSeek" />
          </div>
          <div class="field">
            <label>模型名称</label>
            <input v-model="providerForm.model" type="text" placeholder="deepseek-flash" />
          </div>
        </div>
        <div class="field">
          <label>接口地址</label>
          <input v-model="providerForm.baseUrl" type="text" placeholder="https://api.deepseek.com/v1" />
        </div>
        <div class="grid cols-2">
          <div class="field">
            <label>密钥</label>
            <input v-model="providerForm.apiKey" type="password" :placeholder="providerForm.id ? '留空则不修改' : 'sk-...'" />
          </div>
          <div class="field">
            <label>等待回复时间（秒）</label>
            <input v-model.number="providerForm.timeoutSeconds" type="number" min="5" max="300" />
          </div>
        </div>
        <div class="field">
          <label>额外的请求参数</label>
          <div v-for="(h, i) in providerHeaders" :key="i" class="kv-row">
            <input v-model="h.key" type="text" placeholder="参数名" />
            <input v-model="h.value" type="text" placeholder="值" />
            <button class="btn sm danger" @click="providerHeaders.splice(i, 1)">删除</button>
          </div>
          <button class="btn sm" @click="providerHeaders.push({ key: '', value: '' })">添加一行</button>
          <div class="desc">一般不用填。服务商有特殊要求时，在这里补充参数</div>
        </div>

        <template #footer>
          <button class="btn" @click="closeProviderDialog">取消</button>
          <button class="btn primary" :disabled="saving" @click="saveProvider">保存</button>
        </template>
      </Modal>
    </section>

    <!-- ---------------------------------------------------- AI 配置 -->
    <section v-show="tab === 'ai'">
      <div class="card">
        <h2>AI 判断与打招呼</h2>
        <p class="hint">
          每看到一个岗位，AI 会一次性决定投不投，并写好招呼语。AI 判断出错时直接跳过这个岗位，
          不会勉强投递
        </p>

        <div class="grid cols-2">
          <div class="field">
            <label>使用哪个 AI 服务商</label>
            <select v-model.number="ai.providerId">
              <option :value="null">未选择</option>
              <option v-for="p in providers" :key="p.id" :value="p.id">{{ p.name }} · {{ p.model }}</option>
            </select>
          </div>
          <div class="field">
            <label>是否让 AI 判断该不该投</label>
            <label class="switch" style="margin-top: 6px">
              <input v-model="ai.qualityGateEnabled" type="checkbox" />
              <span>开启：由 AI 判断岗位值不值得投。关闭：只要符合搜索条件，就用固定的招呼语</span>
            </label>
          </div>
        </div>

        <div class="field">
          <label>你的个人情况</label>
          <textarea v-model="ai.introduce" placeholder="写下你的技能、经验和专业背景，AI 会据此写招呼语" />
        </div>

        <div class="field">
          <label>判断标准（写给 AI 的说明）</label>
          <textarea v-model="ai.promptTemplate" style="min-height: 220px" />
          <div class="desc">
            可以在这里插入下面的内容，AI 会换成对应岗位的实际信息：<code v-for="p in placeholders" :key="p">{{ p }}</code>。
          </div>
        </div>

        <div class="field">
          <label>固定的招呼语（关闭 AI 判断时使用）</label>
          <input v-model="ai.templateGreeting" type="text" />
        </div>

        <div class="grid cols-3">
          <div class="field">
            <label>岗位描述最多读取多少字</label>
            <input v-model.number="ai.jdMaxChars" type="number" min="500" max="20000" />
          </div>
          <div class="field">
            <label>AI 判断的最长等待时间（秒）</label>
            <input v-model.number="ai.aiTimeoutSeconds" type="number" min="5" max="120" />
            <div class="desc">判断投不投、写招呼语时最多等这么久。下面「AI 自动回复」里的等待时间是另一项设置</div>
          </div>
          <div class="field">
            <label>统计回复效果</label>
            <label class="switch" style="margin-top: 6px">
              <input v-model="ai.collectEffectMetrics" type="checkbox" />
              <span>记录对方多久回第一次、回了多少条（只做统计，不影响投递）</span>
            </label>
          </div>
        </div>

      </div>

      <div class="card">
        <div class="row" style="justify-content: space-between; align-items: flex-start">
          <div>
            <h2>AI 自动回复</h2>
            <p class="hint">
              HR 发来新消息时，由 AI 读消息并写出回复，不需要你盯着。
            </p>
          </div>
          <span class="tag" :class="ai.aiReplyEnabled ? 'ok' : ''">
            {{ ai.aiReplyEnabled ? '已开启' : '已关闭' }}
          </span>
        </div>

        <label class="switch">
          <input v-model="ai.aiReplyEnabled" type="checkbox" />
          <span>开启 AI 自动回复</span>
        </label>

        <div class="field" style="margin-top: 16px">
          <label>回复风格要求</label>
          <textarea
            v-model="ai.aiReplyStyle"
            :placeholder="aiStyleDefault"
            style="min-height: 150px"
          />
          <div class="desc">
            <template v-if="ai.aiReplyStyle && ai.aiReplyStyle.trim()">
              当前使用你自己写的风格（{{ ai.aiReplyStyle.trim().length }} 字）。这里只决定语气和写法，
              能不能发出去还要看下面的检查
            </template>
            <template v-else>
              不填就使用默认风格。默认内容已用灰字显示在输入框里，共 {{ aiStyleDefault.length }} 字
            </template>
            <button class="btn sm" style="margin-left: 8px" @click="restoreStyle">填入默认</button>
          </div>
        </div>

        <div class="grid cols-2">
          <div class="field">
            <label>生成一条回复的最长等待时间（秒）</label>
            <input v-model.number="ai.aiReplyTimeoutSeconds" type="number" min="1" max="120" />
            <div class="desc">
              AI 迟迟没有回应时，等这么久就放弃这一条。放弃后会重新尝试，不会悄悄漏掉。
              <template v-if="effectiveReplyTimeout && effectiveReplyTimeout < ai.aiReplyTimeoutSeconds">
                <b>实际只等 {{ effectiveReplyTimeout }} 秒</b> —— 你给这家 AI 服务商设的等待时间更短，到点会先断开
              </template>
            </div>
          </div>
          <div class="field">
            <label>每轮最多回复几条</label>
            <input v-model.number="ai.aiReplyMaxPerSweep" type="number" min="1" max="10" />
            <div class="desc">
              每轮自动回复最多调用几次 AI。超出数量的未读消息会留到下一轮继续处理，不算失败，也不占用重试机会。
              <!-- ADR-0003 第 4 条：两个平台各建一份预算，不共享。 -->
              <b>每个平台单独计算</b>，{{ platforms.length }} 个平台同时开启时，一轮最多调用
              {{ platforms.length || 1 }} × {{ ai.aiReplyMaxPerSweep || 0 }} 次
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="row" style="justify-content: space-between; align-items: flex-start">
          <div>
            <h2>发送前检查与提醒</h2>
            <p class="hint">
              消息发出前的最后一道检查。
            </p>
          </div>
          <span class="tag" :class="ai.aiReplyToneCheckEnabled ? 'ok' : 'danger'">
            {{ ai.aiReplyToneCheckEnabled ? '已开启' : '已关闭' }}
          </span>
        </div>

        <label class="switch">
          <input v-model="ai.aiReplyToneCheckEnabled" type="checkbox" />
          <span>开启「像 AI 写的」检查：出现客套话、客服口气、小标题等一律不发送</span>
        </label>

        <div class="field" style="margin-top: 16px">
          <label>不发送的词（一行一个）</label>
          <textarea
            v-model="ai.aiReplyToneTerms"
            :placeholder="aiToneTermsDefault"
            style="min-height: 160px"
          />
          <div class="desc">
            <template v-if="ai.aiReplyToneTerms && ai.aiReplyToneTerms.trim()">
              当前使用你自己填的词，共 {{ toneTermsEffective }} 条。一条回复里只要出现其中任何一个词，这条就不会发送
            </template>
            <template v-else>
              不填就使用默认的词（{{ aiToneTermsEffectiveCount }} 条），内容已用灰字显示在输入框里。
              自己填的会<b>整个替换</b>默认的那份，不是加在默认清单后面
            </template>
            <button class="btn sm" style="margin-left: 8px" @click="restoreToneTerms">填入默认</button>
          </div>
          <div v-if="riskyToneHits.length" class="desc" style="color: var(--warn)">
            ⚠ 这几个词本身就是平时说话会用到的：{{ riskyToneHits.join('、') }}。
            因为只要出现这个词，整条回复就不会发送，加上它们会把「感谢您的回复」「方便发份简历吗」
            这类正常回复也一起拦住，建议删掉。不过仍然可以保存
          </div>
        </div>

        <div class="grid cols-2">
          <div class="field">
            <label>「AI 决定不回」比例提醒（%）</label>
            <input v-model.number="ai.aiReplySkipAlertPct" type="number" min="0" max="100" />
            <div class="desc">
              最近 7 天里，AI 判断后决定不回的比例超过这个数，就在「系统」页给出提醒。
              <b>填 0 表示关闭提醒</b>；关闭时也会明确写出来，不会让你以为一切正常
            </div>
          </div>
          <div class="field">
            <label>至少判断多少次才提醒</label>
            <input v-model.number="ai.aiReplySkipAlertMinSample" type="number" min="1" max="1000" />
            <div class="desc">
              判断次数少于这个数时不下结论，页面上会显示「样本不足」。
            </div>
          </div>
        </div>
      </div>

      <button class="btn primary" :disabled="saving" @click="saveAi">保存 AI 设置</button>
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
          三枚开关排成一组：平台启用决定「能不能启动」，扫描与值守决定「启动之后做什么」，
          三者互不派生。并排放是为了让这层关系直接可见 —— 分开摆就得靠文字解释它们的关系。

          它们与其余字段同批保存，不做即时保存：开关也属于这份平台配置，
          单独即时保存会出现「改了城市没保存、开关却已经关了」的错位。
        -->
        <div class="switches">
          <label class="switch" :title="enabledHint">
            <input v-model="platform.enabled" type="checkbox" />
            <span>启用平台<b>{{ platform.enabled === false ? '关' : '开' }}</b></span>
          </label>
          <label class="switch" :title="scanHint">
            <input v-model="platform.scanEnabled" type="checkbox" />
            <span>搜索岗位<b>{{ platform.scanEnabled === false ? '关' : '开' }}</b></span>
          </label>
          <label class="switch" :title="watchHint">
            <input v-model="platform.watchEnabled" type="checkbox" />
            <span>自动回复<b>{{ platform.watchEnabled === false ? '关' : '开' }}</b></span>
          </label>
        </div>
        <span class="scope-note">下面这些设置只对这个平台有效，每个平台各有一份</span>
        <span v-if="dirty" class="tag warn">有未保存改动</span>
      </div>

      <!--
        后果写在开关下方，四种状态各一句。只给开关、不写后果时，人分不清「关扫描」与
        「关值守」的差别，也看不出两个都关会怎样 —— 那正好是被后端拒绝保存的那种状态。
        同时只能出现一条：停用时后面几行的判断都不再成立。
      -->
      <div v-if="platform.enabled === false" class="off-note">
        停用后不会再为「{{ platformLabel }}」打开浏览器，也不会搜索岗位或自动回复；另一个平台不受影响。
        如果有任务正在跑，要先停止才能停用。停用期间下面两个开关不起作用。
      </div>
      <div v-else-if="bothOff" class="off-note danger">
        两个都关掉时无法保存。如果想让「{{ platformLabel }}」完全停下，请关闭上面的「启用平台」，
        那会连浏览器一起关掉。
      </div>
      <div v-else-if="scanOff" class="off-note">
        只自动回复：「{{ platformLabel }}」启动后不搜索岗位、不查看岗位详情、不投递，
        也不占用投递和查看的名额；它会直接开始盯着 HR 的消息，按设定间隔处理未读消息。
      </div>
      <div v-else-if="watchOff" class="off-note">
        只搜索岗位：「{{ platformLabel }}」照常搜索和投递，但所有搜索组合跑完后任务就结束
        （状态显示「已完成」），不再继续盯着消息。想再跑一轮，重新点启动。
      </div>

      <!--
        未启用扫描时整张卡用原生 disabled：控件灰化、键盘也进不去。
        这一段全是扫描侧字段（城市、关键词、筛选、活跃度下限），只值守时它们不参与任何判定；
        留成可编辑会让「改了会生效」变成默认假设。
        用 fieldset 而不是给每个控件绑 disabled，是因为禁用的对象是「这一组字段」，
        逐个绑会在新增字段时被漏掉。
      -->
      <fieldset class="card" :disabled="scanOff">
        <h2>搜索条件</h2>
        <p v-if="scanOff" class="card-note">
          该平台没有开启「搜索岗位」：下面这些条件暂时不起作用，开启后才会用到
        </p>
        <p class="hint">
          城市直接填中文名即可。{{ platformNote || '这些筛选条件可以同时使用，符合条件的岗位才会出现' }}
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
          <label>HR 活跃程度要求</label>
          <select v-model="platform.activeLevelFloor" style="width: 220px">
            <option v-for="o in platform.activeLevelOptions || []" :key="o" :value="o">{{ o }}</option>
          </select>
          <div class="desc">
            平台只提供这几档活跃程度，没有更细的选项。认不出的档位一律当作「未知」，不会因此被排除
          </div>
        </div>
      </fieldset>

      <div class="card">
        <h2>名额与节奏</h2>
        <p class="hint">
          这两个数字决定今天最多主动联系多少岗位、最多查看多少岗位。用完之后只是不再找新岗位，
          HR 发来的消息照样回复
        </p>
        <!--
          这张卡里混了两类字段：前五项属扫描（配额、上限、节奏），后两项属值守（未读上限、巡检间隔）。
          所以只禁用前五项，不整卡禁用 —— 整卡禁用会让人以为巡检间隔也改不了，而它照常生效。
        -->
        <p v-if="scanOff" class="card-note">
          该平台没有开启「搜索岗位」：投递名额、查看上限、动作间隔、翻页次数和每组条件的时长都不起作用；
          最后两项（每次最多看多少条未读消息、多久检查一次新消息）属于自动回复，照常生效
        </p>
        <div class="grid cols-3">
          <div class="field">
            <label>每天最多主动联系多少个岗位</label>
            <input v-model.number="platform.dailyDeliveryQuota" type="number" min="1" max="200" :disabled="scanOff" />
            <div class="desc">只算搜索时主动发起的联系；对方要简历后发出的简历不计入这个数</div>
          </div>
          <div class="field">
            <label>每天最多查看多少个岗位</label>
            <input v-model.number="platform.dailyEvalLimit" type="number" min="10" max="1000" :disabled="scanOff" />
          </div>
          <div class="field">
            <label>每个动作之间的间隔（秒）</label>
            <input v-model.number="platform.waitTimeSeconds" type="number" min="3" max="60" :disabled="scanOff" />
            <div class="desc">实际等待时间会在这个数的一半到它之间随机</div>
          </div>
          <div class="field">
            <label>每组条件最多翻多少轮</label>
            <input v-model.number="platform.scrollRoundLimit" type="number" min="20" max="2000" :disabled="scanOff" />
          </div>
          <div class="field">
            <label>每组条件最长跑多久（秒）</label>
            <input v-model.number="platform.combinationTimeLimitSec" type="number" min="120" max="7200" :disabled="scanOff" />
          </div>
          <div class="field">
            <label>每次最多看多少条未读消息</label>
            <input v-model.number="platform.unreadScanLimit" type="number" min="1" max="500" />
            <div class="desc">
              一条未读消息算一条。超出后会在日志里给出提醒
            </div>
          </div>
          <div class="field">
            <label>多久检查一次新消息（秒）</label>
            <input v-model.number="platform.watchIntervalSeconds" type="number" min="30" max="600" />
            <div class="desc">
              名额用完后，按这个间隔检查有没有新消息（可填 30–600 秒，超出范围会按最接近的边界保存）。
              它决定对方最多等多久收到回复，也决定刷新有多频繁。120 秒意味着最慢两分钟内回复；
              调得太短（比如 60 秒）会让操作显得太频繁，增加被平台限制的风险。
              实际间隔会在 ±10% 内随机浮动。
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <h2>自动回复时段</h2>
        <p class="hint">
          这个时间段之外不会发出任何消息，凌晨发招呼语容易被当成骚扰。
          在时间段外点启动，任务会先排队，等时间到了自动开始，期间随时可以取消。
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
        <h2>已经聊过的人</h2>
        <p class="hint">打开对话后先看消息记录：最后一条是我们发的，就当作已经聊过，默认跳过</p>
        <label class="switch">
          <input v-model="platform.repeatChatEnabled" type="checkbox" />
          <span>允许对已经聊过的对话再发一条跟进消息</span>
        </label>
        <div class="field" style="margin-top: 16px">
          <label>跟进消息（随机取一条发送）</label>
          <ChipInput v-model="platform.repeatChatMessages" placeholder="输入一条跟进消息后回车" />
        </div>
      </div>

      <div class="card">
        <h2>黑名单 · 两个平台共用</h2>
        <p class="hint">在「岗位」页增删，和这里用的是同一份名单，两个平台都生效</p>
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
  { key: 'provider', label: 'AI 服务商 · 两个平台共用' },
  { key: 'ai', label: 'AI 判断与回复 · 两个平台共用' },
  { key: 'platform', label: `平台配置 · ${platformLabel.value || '…'}` },
  { key: 'reply', label: '自动回复规则 · 两个平台共用' }
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
    // 三枚开关与其余字段同属一份配置：没配过时按建表默认值给，都是开
    enabled: true,
    // 扫描与值守互不派生：关掉哪个就只少做那件事，不影响另一个（V14 两列都是 DEFAULT 1）
    scanEnabled: true,
    watchEnabled: true,
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

const platform = reactive<any>(defaultPlatformConfig())

/**
 * 两个关闭态在模板里被反复判定（off-note、置灰、悬浮说明），取个名字比处处写
 * `=== false` 更不容易读错 —— 这两个字段来自后端，缺失时按开处理，所以只判 false。
 */
const scanOff = computed(() => platform.scanEnabled === false)
const watchOff = computed(() => platform.watchEnabled === false)
/** 两个都关是后端拒绝保存的状态：界面先说出来，别等人点了保存才看到 400 */
const bothOff = computed(() => scanOff.value && watchOff.value)

/**
 * 三枚开关的悬浮说明。关闭态单独写一句，因为「关掉会怎样」不能从开启态反推：
 * 关扫描不是「少扫一点」，而是连搜索页都不打开、不占配额。
 */
const enabledHint = computed(() =>
  platform.enabled === false
    ? '关闭：不再为它打开浏览器，也不会搜索岗位或自动回复；下面两个开关不再起作用'
    : '开启：这个平台可以启动。启动后具体做什么，由「搜索岗位」和「自动回复」两个开关决定'
)

const scanHint = computed(() =>
  scanOff.value
    ? '关闭：不搜索岗位、不查看岗位详情、不投递，也不占用投递和查看的名额'
    : '开启：按城市和关键词找到岗位，逐个查看岗位详情，并决定投不投'
)

const watchHint = computed(() =>
  watchOff.value
    ? '关闭：不检查未读消息、不读消息、不回消息、不发简历；平台上的未读标记保持原样'
    : '开启：搜索过程中会顺带检查消息，搜索结束后继续盯着消息'
)

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
  if (!start || !end) return '不填则按 09:00–22:00 处理'
  if (start === end) return '开始和结束填成一样，表示全天都发消息，凌晨也会发'
  return start < end
    ? `每天 ${start}–${end}：这段时间内启动会立刻开始，到 ${end} 自动停止；在这段时间外启动会先排队，等到下一个 ${start} 自动开始`
    : `跨天时段：从 ${start} 一直发到第二天 ${end}`
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
  if (dirty.value && !confirm(`「${platformLabel.value}」的改动还没保存，放弃改动并切换到 ${labelOf(slug)}？`)) return
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
  if (tab.value === 'platform' && dirty.value && !confirm('改动还没保存，放弃改动并切换吗？')) return
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
    show('已保存')
    await loadAll()
    closeProviderDialog()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    saving.value = false
  }
}

async function deleteProvider(p: any) {
  if (!confirm(`删除「${p.name}」？`)) return
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
    show('默认风格还没加载出来，请刷新页面后再试', true)
    return
  }
  ai.aiReplyStyle = aiStyleDefault.value
}

/** 同上：只填框，由用户自己保存 */
function restoreToneTerms() {
  if (!aiToneTermsDefault.value) {
    show('默认词表还没加载出来，请刷新页面后再试', true)
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
    show('「生成一条回复的最长等待时间」要填大于 0 的整数', true)
    return
  }
  if (!Number.isInteger(maxPerSweep) || maxPerSweep < 1) {
    show('「每轮最多回复几条」要填大于 0 的整数', true)
    return
  }
  // 阈值 0 是合法值（= 关闭告警），所以这里不能用 `!alertPct` 判断
  if (!Number.isInteger(alertPct) || alertPct < 0) {
    show('提醒比例要填 0 到 100 之间的整数，0 表示关闭提醒', true)
    return
  }
  if (!Number.isInteger(minSample) || minSample < 1) {
    show('「至少判断多少次才提醒」要填大于 0 的整数', true)
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
      show(`已保存，但有 ${res.toneTermsDropped} 行超过 20 个字，没有保存进去`, true)
    } else {
      show('已保存')
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
    show('已保存')
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

/* 两个都关＝保存一定会被拒：与「某个开关关掉的后果」不是一类信息，用错误色区分 */
.off-note.danger {
  border-left-color: var(--danger);
  background: var(--danger-soft);
  color: var(--danger);
}

/* 三枚开关一组：间距比卡片外的小，读起来是「同一组状态」而不是三个独立选项 */
.switches {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
}

/* 开关后的「开 / 关」：只有滑块位置一个状态信号时，一行三枚很容易看错是哪一个 */
.switches b {
  margin-left: 5px;
  font-weight: 500;
  color: var(--ink);
}

/* fieldset 自带 border、margin-inline 与 min-inline-size；这里只保留 .card 的观感，
   min-width: 0 是让它在 grid 或 flex 容器里允许被压缩的前提 */
fieldset.card {
  min-width: 0;
  margin-inline: 0;
}

/* 卡片内部的「这一段现在不生效」提示：比正文次要，比 tooltip 显眼 */
.card-note {
  margin: 0 0 14px;
  padding: 8px 12px;
  border-left: 3px solid var(--line-strong);
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
