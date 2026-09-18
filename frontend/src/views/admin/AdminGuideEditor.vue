<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import type { GuideDetail } from "../../services/api";
import {
  archiveGuide,
  copyGuide,
  createGuide,
  getGuideDetail,
  hideGuide,
  publishGuide,
  rejectGuide,
  reindexGuide,
  restoreGuide,
  rollbackGuide,
  submitGuide,
  unarchiveGuide,
  updateGuide,
} from "../../services/api";

/** 攻略编辑器（设计方案 §4.3：Markdown 编辑 + 预览 + 草稿/提交/发布/下线；含版本历史与结构化解析） */
const props = defineProps<{ id?: string }>();
const router = useRouter();
const isEdit = computed(() => !!props.id);

const saving = ref(false);
const busy = ref(false);
const preview = ref(false);
const rejecting = ref(false);
const rejectReason = ref("");

const form = reactive({
  city: "",
  title: "",
  summary: "",
  coverImage: "",
  sourceType: "CURATED",
  sourceName: "",
  content: "",
  changeSummary: "",
});

const detail = ref<GuideDetail | null>(null);

const STATUS_LABEL: Record<string, string> = {
  DRAFT: "草稿",
  PENDING_REVIEW: "待审核",
  PUBLISHED: "已发布",
  REJECTED: "已拒绝",
  HIDDEN: "已下线",
  ARCHIVED: "已归档",
};
const RAG_LABEL: Record<string, string> = {
  NOT_INDEXED: "未索引",
  READY: "已索引",
  FAILED: "索引失败",
};
/** 索引任务状态（READY/FAILED/SUPERSEDED/PENDING…；SUPERSEDED = 版本已被更新发布/下线取代，作废跳过） */
const TASK_LABEL: Record<string, string> = {
  PENDING: "排队中",
  READY: "成功",
  FAILED: "失败",
  SUPERSEDED: "已作废",
  DEFERRED: "历史占位",
};

/** RAG 索引是否成功可用（成功后仍可手动重建） */
function ragReady(): boolean {
  return !!detail.value && detail.value.rag_status === "READY";
}
/** 待审编辑且存在上一已发布版本 → 允许放弃编辑回滚 */
function canRollback(): boolean {
  return (
    !!detail.value &&
    detail.value.status === "PENDING_REVIEW" &&
    detail.value.published_revision_id != null
  );
}

async function load() {
  if (!props.id) return;
  try {
    const d = await getGuideDetail(Number(props.id));
    detail.value = d;
    form.city = d.city;
    form.title = d.title;
    form.summary = d.summary || "";
    form.coverImage = d.cover_image || "";
    form.sourceType = d.source_type || "CURATED";
    form.sourceName = d.source_name || "";
    form.content = d.content_markdown || "";
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "加载失败");
    void back();
  }
}

function back() {
  void router.push({ name: "admin-guides" });
}

function validate(): boolean {
  if (!form.city.trim()) return !message.warning("请填写城市");
  if (!form.title.trim()) return !message.warning("请填写标题");
  if (!form.content.trim()) return !message.warning("正文不能为空");
  return true;
}

/** 保存（草稿语义：新建 DRAFT / 已有保存 append 新版本；已发布内容自动回待审） */
async function save() {
  if (!validate()) return;
  saving.value = true;
  try {
    const payload = {
      city: form.city.trim(),
      title: form.title.trim(),
      summary: form.summary.trim() || undefined,
      coverImage: form.coverImage.trim() || undefined,
      sourceType: form.sourceType,
      sourceName: form.sourceName.trim() || undefined,
      content: form.content,
      changeSummary: form.changeSummary.trim() || undefined,
    };
    if (isEdit.value) {
      const r = await updateGuide(Number(props.id), payload);
      message.success(r.message || (r.unchanged ? "无变化" : "已保存"));
      await load();
    } else {
      const r = await createGuide(payload);
      message.success(`草稿已创建 v1`);
      void router.replace({ name: "admin-guide-edit", params: { id: String(r.id) } });
    }
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "保存失败");
  } finally {
    saving.value = false;
  }
}

async function submit() {
  if (!props.id) return;
  try {
    await submitGuide(Number(props.id));
    message.success("已提交审核");
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  }
}

async function publish() {
  if (!props.id) return;
  try {
    await publishGuide(Number(props.id));
    message.success("已发布，RAG 索引自动构建中（成功 READY / 失败可点「重建索引」重试）");
    await load();
    // 索引多为秒级（无 key 时快速失败），稍后自动刷新一次以呈现 READY/FAILED 终态
    setTimeout(() => void load(), 1800);
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  }
}

async function confirmReject() {
  if (!props.id) return;
  if (!rejectReason.value.trim()) return message.warning("请填写拒绝原因");
  busy.value = true;
  try {
    await rejectGuide(Number(props.id), rejectReason.value.trim());
    message.success("已拒绝");
    rejecting.value = false;
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  } finally {
    busy.value = false;
  }
}

async function hide() {
  if (!props.id) return;
  try {
    await hideGuide(Number(props.id));
    message.success("已下线");
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  }
}

async function restore() {
  if (!props.id) return;
  try {
    await restoreGuide(Number(props.id));
    message.success("已恢复公开，RAG 索引重建中…");
    await load();
    setTimeout(() => void load(), 1800);
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  }
}

async function archive() {
  if (!props.id) return;
  if (!window.confirm("确认归档？归档后停止公开消费并从 RAG 检索源移除（可取消归档回草稿继续维护）。")) return;
  try {
    await archiveGuide(Number(props.id));
    message.success("已归档");
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  }
}

async function unarchive() {
  if (!props.id) return;
  try {
    await unarchiveGuide(Number(props.id));
    message.success("已取消归档，转为草稿");
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  }
}

async function copy() {
  if (!props.id) return;
  if (!window.confirm("复制为新版本？将基于当前正文另起一条全新草稿（不触碰原攻略）。")) return;
  try {
    const r = await copyGuide(Number(props.id));
    message.success(r.message || "已复制为新版本");
    void router.push({ name: "admin-guide-edit", params: { id: String(r.id) } });
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  }
}

async function rollback() {
  if (!props.id || !canRollback()) return;
  if (!window.confirm("放弃本次待审编辑并回滚到上一已发布版本？当前草稿版本会保留在历史里（不再作为当前版本）。")) return;
  try {
    const r = await rollbackGuide(Number(props.id));
    message.success(r.message || "已回滚到上一已发布版本");
    await load();
    setTimeout(() => void load(), 1800);
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  }
}

async function reindex() {
  if (!props.id) return;
  if (!window.confirm("重建/重试 RAG 索引？将用当前已发布版本重新切分与向量化（同步执行，可能需要数秒）。")) return;
  const prev = ragReady();
  try {
    const r = await reindexGuide(Number(props.id));
    if (r.data?.status === "READY") {
      message.success("索引重建成功，新版本已生效");
    } else {
      message.warning(
        `索引失败（旧版本继续服务）：${r.data?.error_message || r.message || "未知原因"}`
      );
    }
    await load();
    if (!prev) setTimeout(() => void load(), 1800);
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "重建失败");
  }
}

onMounted(load);
</script>

<template>
  <div class="ad-card">
    <div class="ad-form-row" style="justify-content: space-between">
      <p class="ad-title">{{ isEdit ? `编辑攻略 #${props.id}` : "新建攻略草稿" }}</p>
      <div class="ad-ops">
        <button type="button" class="ad-btn" @click="preview = !preview">{{ preview ? "回到编辑" : "预览 Markdown" }}</button>
        <button type="button" class="ad-btn" @click="back">← 返回列表</button>
      </div>
    </div>
    <p v-if="detail" class="ad-sub">
      状态：
      <span class="ad-badge" :class="detail.status === 'PUBLISHED' ? 'ad-badge--ok' : detail.status === 'PENDING_REVIEW' ? 'ad-badge--warn' : detail.status === 'ARCHIVED' ? 'ad-badge--muted' : 'ad-badge--no'">
        {{ STATUS_LABEL[detail.status] || detail.status }}
      </span>
      <span class="ad-badge" :class="ragReady() ? 'ad-badge--ok' : detail.rag_status === 'FAILED' ? 'ad-badge--no' : 'ad-badge--muted'">
        RAG：{{ RAG_LABEL[detail.rag_status] || detail.rag_status }}<template v-if="ragReady() && detail.rag_indexed_revision">（线上检索 = 已索引 v{{ detail.rag_indexed_revision }} 对应版本）</template><template v-else-if="detail.rag_status === 'FAILED'">（旧版本继续服务，可点「重建索引」重试）</template>
      </span>
      当前 v{{ detail.version }}<template v-if="detail.reject_reason"> · 拒绝原因：{{ detail.reject_reason }}</template>
    </p>
    <p v-else class="ad-sub">填写城市/标题/正文后「保存草稿」；内容无变化时保存不会产生新版本。</p>

    <!-- 元信息 -->
    <div v-if="!preview" class="ad-editor-form">
      <div class="ad-form-row">
        <input v-model="form.city" class="ad-input" style="width: 130px" placeholder="城市（如 北京）" maxlength="80" />
        <input v-model="form.title" class="ad-input" style="flex: 1; min-width: 200px" placeholder="标题（如 北京旅行攻略）" maxlength="200" />
        <select v-model="form.sourceType" class="ad-select">
          <option value="CURATED">管理员精选</option>
          <option value="SYSTEM">系统Markdown</option>
          <option value="IMPORTED">外部导入</option>
          <option value="COMMUNITY">用户精选</option>
        </select>
        <input v-model="form.sourceName" class="ad-input" style="width: 170px" placeholder="来源名称（可选）" maxlength="120" />
      </div>
      <input v-model="form.summary" class="ad-input" style="width: 100%" placeholder="摘要（可选，≤300 字）" maxlength="300" />
      <input v-model="form.coverImage" class="ad-input" style="width: 100%" placeholder="封面图 URL（可选，http 或 /uploads/xxx）" maxlength="500" />
      <input v-model="form.changeSummary" class="ad-input" style="width: 100%" placeholder="本次变更说明（可选，会写进版本历史）" maxlength="300" />
      <textarea v-model="form.content" class="ad-textarea" rows="18" placeholder="# 城市旅行攻略&#10;&#10;## 2. 核心景点&#10;&#10;### 2.1 景点名&#10;- **位置**：&#10;- **门票**：&#10;- **简介**："></textarea>
    </div>
    <div v-else>
      <p class="ad-item__meta">Markdown 预览（结构化解析：以下格式的景点卡会被识别：### 2.x 名称 + 位置/门票/简介）</p>
      <pre class="ad-markdown">{{ form.content || "（空）" }}</pre>
    </div>

    <!-- 动作 -->
    <div class="ad-ops" style="margin-top: 12px; flex-wrap: wrap">
      <button type="button" class="ad-btn ad-btn--ok" :disabled="saving" @click="save">💾 保存{{ isEdit ? "新版本" : "草稿" }}</button>
      <template v-if="detail">
        <template v-if="detail.status === 'DRAFT' || detail.status === 'REJECTED'">
          <button type="button" class="ad-btn" @click="submit">提交审核</button>
        </template>
        <template v-else-if="detail.status === 'PENDING_REVIEW'">
          <button type="button" class="ad-btn ad-btn--ok" :disabled="busy" @click="publish">✓ 通过并发布</button>
          <button type="button" class="ad-btn ad-btn--no" :disabled="busy" @click="rejecting = true">✕ 拒绝</button>
          <button v-if="canRollback()" type="button" class="ad-btn" :disabled="busy" @click="rollback">↩ 放弃编辑·回滚上一版本</button>
        </template>
        <template v-else-if="detail.status === 'PUBLISHED'">
          <span class="ad-item__meta">再保存将生成新版本并回到待审（旧版本继续线上服务）</span>
          <button type="button" class="ad-btn ad-btn--no" @click="hide">下线</button>
          <button type="button" class="ad-btn" @click="archive">归档</button>
          <button type="button" class="ad-btn" @click="copy">⧉ 复制为新版本</button>
          <button type="button" class="ad-btn" @click="reindex">↻ 重建索引{{ detail.rag_status === "FAILED" ? "（重试）" : "" }}</button>
        </template>
        <template v-else-if="detail.status === 'HIDDEN'">
          <button type="button" class="ad-btn ad-btn--ok" @click="restore">恢复公开</button>
          <button type="button" class="ad-btn" @click="archive">归档</button>
          <button type="button" class="ad-btn" @click="copy">⧉ 复制为新版本</button>
        </template>
        <template v-else-if="detail.status === 'ARCHIVED'">
          <button type="button" class="ad-btn ad-btn--ok" @click="unarchive">取消归档（转草稿）</button>
          <button type="button" class="ad-btn" @click="copy">⧉ 复制为新版本</button>
        </template>
      </template>
    </div>

    <!-- 结构化解析与版本历史 -->
    <template v-if="detail">
      <div class="ad-editor-bottom">
        <div class="ad-card">
          <p class="ad-title" style="font-size: 14px">解析结果（v{{ detail.version }}）</p>
          <p class="ad-item__meta">
            识别到 {{ detail.spot_total ?? detail.spots.length }} 个景点 ·
            {{ detail.spot_matched ?? 0 }} 已匹配 spot 表 ·
            {{ detail.spot_unmatched ?? detail.spots.length }} 未匹配（绿=已匹配 · 灰=待补充到景点主档）
          </p>
          <div>
            <span v-for="s in detail.spots" :key="s.name" class="ad-badge" :class="s.matched ? 'ad-badge--ok' : 'ad-badge--muted'">
              {{ s.matched ? "✓ " : "✗ " }}{{ s.name }}
            </span>
          </div>
          <p class="ad-item__meta" style="margin-top: 8px">推导标签 {{ detail.tags.length }} 个</p>
          <div>
            <span v-for="t in detail.tags" :key="t.tag" class="ad-badge ad-badge--info">{{ t.tag }}</span>
          </div>
        </div>
        <div class="ad-card">
          <p class="ad-title" style="font-size: 14px">版本历史（最新 30）</p>
          <div v-if="detail.revisions.length === 0" class="ad-item__meta">暂无</div>
          <div v-for="r in detail.revisions" :key="r.id" class="ad-item__meta" style="margin-top: 2px">
            · v{{ r.revision_no }} · {{ r.status ? STATUS_LABEL[r.status] || r.status : "—" }} · {{ r.change_summary || "—" }} · @{{ r.editor_id }} · {{ (r.created_at || "").replace("T", " ") }}
            <template v-if="r.hash">（{{ r.hash }}…）</template>
          </div>
        </div>
        <div class="ad-card">
          <p class="ad-title" style="font-size: 14px">RAG 索引任务</p>
          <div v-if="detail.rag_tasks.length === 0" class="ad-item__meta">发布后自动登记并异步构建（成功 READY / 失败 FAILED，旧版本继续服务）</div>
          <div v-for="t in detail.rag_tasks" :key="t.id" class="ad-item__meta" style="margin-top: 2px">
            · #{{ t.id }} revision#{{ t.revision_id }} ·
            <span :class="t.status === 'READY' ? 'ad-badge ad-badge--ok' : t.status === 'FAILED' ? 'ad-badge ad-badge--no' : 'ad-badge ad-badge--muted'">{{ t.status ? TASK_LABEL[t.status] || t.status : "—" }}</span>
            <template v-if="t.error_message"> · {{ t.error_message }}</template>
            <template v-if="t.finished_at"> · {{ (t.finished_at || "").replace("T", " ") }}</template>
          </div>
        </div>
      </div>
    </template>

    <div v-if="rejecting" class="ad-modal">
      <div class="ad-modal__card">
        <p class="ad-modal__title">填写拒绝原因</p>
        <textarea v-model="rejectReason" class="ad-textarea" rows="3" maxlength="300" placeholder="例如：门票信息疑似过期，请核实后重新提交"></textarea>
        <div class="ad-ops" style="justify-content: flex-end">
          <button type="button" class="ad-btn" @click="rejecting = false">取消</button>
          <button type="button" class="ad-btn ad-btn--no" :disabled="!rejectReason.trim()" @click="confirmReject">确认拒绝</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ad-editor-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ad-editor-bottom {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 12px;
  margin-top: 14px;
}
@media (max-width: 1100px) {
  .ad-editor-bottom {
    grid-template-columns: 1fr;
  }
}
</style>
