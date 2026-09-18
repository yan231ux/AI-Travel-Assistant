<script setup lang="ts">
import { onMounted, ref } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import type { GuideItem } from "../../services/api";
import {
  getGuides,
  hideGuide,
  importGuides,
  publishGuide,
  rejectGuide,
  restoreGuide,
  submitGuide,
} from "../../services/api";

/** 攻略内容（设计方案 §4.2：草稿/提交/发布/下线/归档 + Markdown 幂等导入；RAG 索引自动构建） */
const router = useRouter();
const seg = ref("");
const guides = ref<GuideItem[]>([]);
const total = ref(0);
const loading = ref(true);
const importing = ref(false);
const rejectingId = ref<number | null>(null);
const rejectReason = ref("");

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
const SOURCE_LABEL: Record<string, string> = {
  CURATED: "管理员精选",
  COMMUNITY: "用户精选",
  IMPORTED: "外部导入",
  SYSTEM: "系统Markdown",
};

async function load() {
  loading.value = true;
  try {
    const resp = await getGuides({ status: seg.value || undefined, page: 1, pageSize: 100 });
    guides.value = resp.items;
    total.value = resp.total;
  } catch {
    guides.value = [];
  } finally {
    loading.value = false;
  }
}

function edit(g: GuideItem) {
  void router.push({ name: "admin-guide-edit", params: { id: String(g.id) } });
}

function createNew() {
  void router.push({ name: "admin-guide-create" });
}

async function submit(g: GuideItem) {
  try {
    await submitGuide(g.id);
    message.success("已提交审核");
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  }
}

async function publish(g: GuideItem) {
  if (!window.confirm(`确认发布「${g.title}」？发布后自动构建 RAG 索引（成功 READY / 失败可重试）。`)) return;
  try {
    await publishGuide(g.id);
    message.success("已发布，RAG 索引构建中…");
    await load();
    setTimeout(() => void load(), 1800);
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  }
}

async function hide(g: GuideItem) {
  if (!window.confirm(`确认下线「${g.title}」？保留已发布版本，可随时恢复。`)) return;
  try {
    await hideGuide(g.id);
    message.success("已下线");
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  }
}

async function restore(g: GuideItem) {
  try {
    await restoreGuide(g.id);
    message.success("已恢复公开");
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  }
}

function openReject(g: GuideItem) {
  rejectingId.value = g.id;
  rejectReason.value = "";
}

async function confirmReject() {
  if (rejectingId.value == null) return;
  if (!rejectReason.value.trim()) return message.warning("请填写拒绝原因");
  try {
    await rejectGuide(rejectingId.value, rejectReason.value.trim());
    message.success("已拒绝");
    rejectingId.value = null;
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  }
}

async function doImport() {
  if (!window.confirm("从 classpath Markdown（guides/*.md）幂等导入攻略：新文件直接发布，同文件内容变更将进入待审。继续？")) return;
  importing.value = true;
  try {
    const r = await importGuides();
    message.success(
      `导入完成：新增发布 ${r.imported} / 文件变更入待审 ${r.changed_pending} / 内容未变跳过 ${r.unchanged}`
    );
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "导入失败");
  } finally {
    importing.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="ad-card">
    <div class="ad-form-row" style="justify-content: space-between; margin-bottom: 4px">
      <p class="ad-title">攻略内容（{{ total }}）</p>
      <div class="ad-ops">
        <button type="button" class="ad-btn" :disabled="importing" @click="doImport">⬇ Markdown 幂等导入</button>
        <button type="button" class="ad-btn ad-btn--ok" @click="createNew">＋ 新建攻略草稿</button>
      </div>
    </div>
    <p class="ad-sub">
      数据库已发布版本 = 唯一线上主数据；Markdown 仅作导入/备份载体。编辑已发布内容会自动回到待审，旧版本继续服务。
    </p>
    <div class="ad-seg" style="margin-bottom: 12px">
      <button type="button" :class="['ad-seg__btn', { 'ad-seg__btn--on': seg === '' }]" @click="seg = ''; void load()">全部</button>
      <button type="button" :class="['ad-seg__btn', { 'ad-seg__btn--on': seg === 'DRAFT' }]" @click="seg = 'DRAFT'; void load()">草稿</button>
      <button type="button" :class="['ad-seg__btn', { 'ad-seg__btn--on': seg === 'PENDING_REVIEW' }]" @click="seg = 'PENDING_REVIEW'; void load()">待审核</button>
      <button type="button" :class="['ad-seg__btn', { 'ad-seg__btn--on': seg === 'PUBLISHED' }]" @click="seg = 'PUBLISHED'; void load()">已发布</button>
      <button type="button" :class="['ad-seg__btn', { 'ad-seg__btn--on': seg === 'REJECTED' }]" @click="seg = 'REJECTED'; void load()">已拒绝</button>
      <button type="button" :class="['ad-seg__btn', { 'ad-seg__btn--on': seg === 'HIDDEN' }]" @click="seg = 'HIDDEN'; void load()">已下线</button>
      <button type="button" :class="['ad-seg__btn', { 'ad-seg__btn--on': seg === 'ARCHIVED' }]" @click="seg = 'ARCHIVED'; void load()">已归档</button>
    </div>

    <div v-if="loading" class="ad-empty">加载中…</div>
    <div v-else-if="guides.length === 0" class="ad-empty">没有攻略 —— 先「新建草稿」或「Markdown 幂等导入」</div>
    <div v-else class="ad-list">
      <div v-for="g in guides" :key="g.id" class="ad-item">
        <div class="ad-item__main" role="button" tabindex="0" @click="edit(g)">
          <p class="ad-item__title">
            {{ g.title }}
            <span class="ad-badge" :class="g.status === 'PUBLISHED' ? 'ad-badge--ok' : g.status === 'PENDING_REVIEW' ? 'ad-badge--warn' : g.status === 'HIDDEN' || g.status === 'ARCHIVED' ? 'ad-badge--muted' : 'ad-badge--no'">
              {{ STATUS_LABEL[g.status] || g.status }}
            </span>
            <span class="ad-badge ad-badge--muted">{{ g.city }}</span>
            <span class="ad-badge ad-badge--info">{{ SOURCE_LABEL[g.source_type || "SYSTEM"] || g.source_type }}</span>
            <span class="ad-badge" :class="g.rag_status === 'READY' ? 'ad-badge--ok' : g.rag_status === 'FAILED' ? 'ad-badge--no' : 'ad-badge--muted'">
              {{ RAG_LABEL[g.rag_status] || g.rag_status }}
            </span>
            <span class="ad-badge ad-badge--info">质量 {{ g.quality_score }}</span>
            <span v-if="g.spot_count" class="ad-badge" :class="g.spot_matched === g.spot_count ? 'ad-badge--ok' : 'ad-badge--warn'">
              景点 {{ g.spot_matched }}/{{ g.spot_count }} 已匹配
            </span>
          </p>
          <p class="ad-item__meta">
            v{{ g.version }} · {{ g.author || "—" }}
            <template v-if="g.summary"> · {{ g.summary }}</template>
            <template v-if="g.reject_reason"> · 拒绝原因：{{ g.reject_reason }}</template>
            <template v-if="g.source_file"> · 源文件 {{ g.source_file }}</template>
            <br />更新于 {{ (g.updated_at || "").replace("T", " ") }}
          </p>
        </div>
        <div class="ad-ops">
          <button type="button" class="ad-btn" @click="edit(g)">编辑 ›</button>
          <template v-if="g.status === 'DRAFT' || g.status === 'REJECTED'">
            <button type="button" class="ad-btn ad-btn--ok" @click="submit(g)">提交审核</button>
          </template>
          <template v-else-if="g.status === 'PENDING_REVIEW'">
            <button type="button" class="ad-btn ad-btn--ok" @click="publish(g)">发布</button>
            <button type="button" class="ad-btn ad-btn--no" @click="openReject(g)">拒绝</button>
          </template>
          <template v-else-if="g.status === 'PUBLISHED'">
            <button type="button" class="ad-btn ad-btn--no" @click="hide(g)">下线</button>
          </template>
          <template v-else-if="g.status === 'HIDDEN'">
            <button type="button" class="ad-btn ad-btn--ok" @click="restore(g)">恢复公开</button>
          </template>
        </div>
      </div>
    </div>

    <div v-if="rejectingId != null" class="ad-modal">
      <div class="ad-modal__card">
        <p class="ad-modal__title">填写拒绝原因（编辑者可见）</p>
        <textarea v-model="rejectReason" class="ad-textarea" rows="3" maxlength="300" placeholder="例如：门票价格疑似过期，请核实后重新提交"></textarea>
        <div class="ad-ops" style="justify-content: flex-end">
          <button type="button" class="ad-btn" @click="rejectingId = null">取消</button>
          <button type="button" class="ad-btn ad-btn--no" :disabled="!rejectReason.trim()" @click="confirmReject">确认拒绝</button>
        </div>
      </div>
    </div>
  </div>
</template>
