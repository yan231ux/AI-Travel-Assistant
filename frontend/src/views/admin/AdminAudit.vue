<script setup lang="ts">
import { onMounted, ref } from "vue";

import type { AuditLogItem } from "../../services/api";
import { getAuditLogs } from "../../services/api";

/** 全链路审计日志（设计方案 §3.1/§9.4：谁在何时对什么做了什么，可追溯） */
const audits = ref<AuditLogItem[]>([]);
const total = ref(0);
const category = ref("");
const days = ref(7);

const CATEGORIES: Record<string, string> = {
  USER: "用户/鉴权",
  TRIP: "行程",
  CONTENT: "内容",
  ADMIN: "审核/治理",
  SOCIAL: "关注",
  PROFILE: "画像",
  OPS: "运营",
};

const ACTIONS: Record<string, string> = {
  user_registered: "注册账号",
  login_success: "登录成功",
  login_failed: "登录失败",
  trip_saved: "保存行程",
  trip_updated: "更新行程",
  trip_deleted: "删除行程",
  post_created: "发布帖子",
  post_submitted: "提交审核",
  post_approved: "通过审核",
  post_rejected: "拒绝帖子",
  post_hidden: "隐藏下架",
  report_created: "收到举报",
  report_resolved: "举报成立处理",
  report_dismissed: "驳回举报",
  user_followed: "关注用户",
  user_unfollowed: "取关用户",
  experiment_created: "创建A/B实验",
  experiment_closed: "关闭A/B实验",
  guide_created: "新建攻略",
  guide_updated: "编辑攻略",
  guide_submitted: "攻略提交审核",
  guide_published: "攻略发布",
  guide_rejected: "攻略拒绝",
  guide_hidden: "攻略下线",
  guide_restored: "攻略恢复",
  guide_imported: "攻略导入",
};

function actionText(a: string): string {
  return ACTIONS[a] || a;
}

function detailText(d?: string | null): string {
  if (!d) return "";
  try {
    const o = JSON.parse(d) as Record<string, unknown>;
    return Object.entries(o)
      .map(([k, v]) => `${k}=${String(v)}`)
      .join(" · ");
  } catch {
    return d;
  }
}

async function load() {
  try {
    const resp = await getAuditLogs({
      category: category.value || undefined,
      days: days.value,
      page: 1,
      pageSize: 60,
    });
    audits.value = resp.items;
    total.value = resp.total;
  } catch {
    audits.value = [];
    total.value = 0;
  }
}

onMounted(load);
</script>

<template>
  <div class="ad-card">
    <div class="ad-form-row" style="justify-content: space-between">
      <p class="ad-title">全链路审计日志（{{ total }}）</p>
      <div class="ad-form-row">
        <select v-model="category" class="ad-select" @change="void load()">
          <option value="">全部分类</option>
          <option v-for="(label, c) in CATEGORIES" :key="c" :value="c">{{ label }}</option>
        </select>
        <select v-model.number="days" class="ad-select" @change="void load()">
          <option :value="1">近 1 天</option>
          <option :value="7">近 7 天</option>
          <option :value="30">近 30 天</option>
          <option :value="90">近 90 天</option>
        </select>
      </div>
    </div>

    <div v-if="audits.length === 0" class="ad-empty">还没有审计记录</div>
    <div v-else class="ad-list">
      <div v-for="a in audits" :key="a.id" class="ad-item">
        <div class="ad-item__main">
          <p class="ad-item__title">
            {{ actionText(a.action) }}
            <span class="ad-badge ad-badge--info">{{ CATEGORIES[a.category] || a.category }}</span>
            <template v-if="a.target_type"> · {{ a.target_type }}#{{ a.target_id }}</template>
          </p>
          <p class="ad-item__meta">
            <template v-if="a.actor_name">@{{ a.actor_name }}（{{ a.actor_id }}）</template>
            <template v-else>系统/匿名</template>
            <template v-if="detailText(a.detail)"> · {{ detailText(a.detail) }}</template>
            <br />{{ (a.created_at || "").replace("T", " ") }}
          </p>
        </div>
      </div>
    </div>
  </div>
</template>
