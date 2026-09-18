<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import type { AdminUserGovernItem } from "../../services/api";
import { assignAdminUserRole, governAdminUser, listAdminUsers } from "../../services/api";
import { currentUserId, isAdminSideRole } from "../../stores/session";

/**
 * 用户与账号治理（设计方案 §7 /admin/users + §11 第五阶段角色细化）：
 * 搜索/筛选注册用户 → 限制发帖、暂停评论、暂停账号及对应恢复；分配管理端角色。
 * 限制即时生效于真实链路：发帖/评论被拒、被暂停账号登录被拒（已登录老 token 在下次登录时拒绝）。
 * 全部动作服务端校验 USER_GOVERN 权限 + 审计；管理端账号不受用户治理动作（页面同时隐藏按钮）。
 */
const router = useRouter();
const loading = ref(true);
const acting = ref(false);
const rows = ref<AdminUserGovernItem[]>([]);
const total = ref(0);

const filters = reactive({ keyword: "", status: "", from: "", to: "", page: 1, pageSize: 20 });

/** 治理模态：{ user, action, note, danger } */
const modal = ref<{ user: AdminUserGovernItem; action: string; note: string; danger: boolean } | null>(null);
const reason = ref("");

const STATUS_LABEL: Record<string, string> = { ACTIVE: "正常", SUSPENDED: "已暂停" };

function goUser(u: AdminUserGovernItem) {
  void router.push({ name: "user-home", params: { id: u.id } });
}

function errOf(e: unknown): string {
  return (e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败";
}

async function load() {
  loading.value = true;
  try {
    const resp = await listAdminUsers({
      keyword: filters.keyword.trim() || undefined,
      status: filters.status || undefined,
      from: filters.from || undefined,
      to: filters.to || undefined,
      page: filters.page,
      pageSize: filters.pageSize,
    });
    rows.value = resp.items;
    total.value = resp.total;
  } catch {
    rows.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}

function search() {
  filters.page = 1;
  void load();
}

function pageOf(p: number) {
  filters.page = p;
  void load();
}

/* ---------- 治理动作入口 ---------- */
const ACTION_META: Record<string, { note: string; danger: boolean }> = {
  POST_LIMIT: { note: "限制发帖（保留浏览/评论，新发帖与编辑发布将被拒）", danger: false },
  UNPOST_LIMIT: { note: "解除发帖限制", danger: false },
  COMMENT_BAN: { note: "暂停评论（发新评论将被拒）", danger: false },
  UNCOMMENT_BAN: { note: "恢复评论", danger: false },
  SUSPEND: { note: "暂停账号（无法登录，已登录会话在下次登录时拒绝）", danger: true },
  RESTORE: { note: "恢复账号（可正常登录使用）", danger: false },
};

function openGovern(u: AdminUserGovernItem, action: string) {
  reason.value = "";
  const meta = ACTION_META[action];
  modal.value = { user: u, action, note: meta.note, danger: meta.danger };
}

async function submitGovern() {
  const m = modal.value;
  if (!m) return;
  acting.value = true;
  try {
    const r = await governAdminUser(m.user.id, m.action, reason.value.trim());
    message.success(r?.message || "已执行");
    modal.value = null;
    await load();
  } catch (e: unknown) {
    message.error(errOf(e));
  } finally {
    acting.value = false;
  }
}

/** 该用户当前可用的治理动作（管理端账号一律不可被治理，与服务端保护一致） */
function actionsOf(u: AdminUserGovernItem): string[] {
  if (isAdminSideRole(u.role)) return [];
  const acts: string[] = [];
  if (u.account_status === "SUSPENDED") {
    acts.push("RESTORE");
    return acts;
  }
  acts.push(u.post_limited ? "UNPOST_LIMIT" : "POST_LIMIT");
  acts.push(u.comment_banned ? "UNCOMMENT_BAN" : "COMMENT_BAN");
  acts.push("SUSPEND");
  return acts;
}

const ACTION_BTN: Record<string, string> = {
  POST_LIMIT: "限制发帖",
  UNPOST_LIMIT: "解除发帖限制",
  COMMENT_BAN: "暂停评论",
  UNCOMMENT_BAN: "恢复评论",
  SUSPEND: "暂停账号",
  RESTORE: "恢复账号",
};

/* ---------- 角色分配（高风险，§11 第五阶段） ---------- */

const ROLE_LABEL: Record<string, string> = {
  USER: "普通用户",
  CONTENT_REVIEWER: "内容审核员",
  CITY_EDITOR: "城市内容编辑",
  RECOMMENDATION_OPERATOR: "推荐运营",
  SUPER_ADMIN: "超级管理员",
  ADMIN: "管理员（历史值）",
};

/** 固定展示顺序：普通用户 → 三个业务角色 → 超管 */
const ROLE_OPTIONS = [
  { code: "USER", label: "普通用户", desc: "无管理端权限（收回权限时选它）" },
  { code: "CONTENT_REVIEWER", label: "内容审核员", desc: "帖子审核 · 举报处理 · AI 初筛 · 数据看板" },
  { code: "CITY_EDITOR", label: "城市内容编辑", desc: "攻略内容运营 · 城市专题 · 数据看板" },
  { code: "RECOMMENDATION_OPERATOR", label: "推荐运营", desc: "A/B 实验 · 人工干预 · 推荐质量 · 数据看板" },
  { code: "SUPER_ADMIN", label: "超级管理员", desc: "全部权限（含景点数据治理 · 用户治理 · 审计）" },
];

/** 角色分配模态：step 1 选角色+填原因 → step 2 二次确认 */
const roleModal = ref<{ user: AdminUserGovernItem; role: string; note: string } | null>(null);
const roleReason = ref("");
const roleConfirmed = ref(false);

function roleLabelOf(u: AdminUserGovernItem): string {
  return u.role_label || ROLE_LABEL[(u.role || "").toUpperCase()] || u.role;
}

/** 只能给自己之外的人改角色：改自己的角色服务端会拒（避免自我提权/自降失权） */
function canAssignRoleTo(u: AdminUserGovernItem): boolean {
  return u.id !== currentUserId.value;
}

function openRole(u: AdminUserGovernItem) {
  roleReason.value = "";
  roleConfirmed.value = false;
  roleModal.value = { user: u, role: "", note: "" };
}

/** 目标角色说明（用于二次确认页复述"改完他能做什么"） */
function descOfRole(code: string): string {
  return ROLE_OPTIONS.find((r) => r.code === code)?.desc || "";
}

function labelOfRole(code: string): string {
  return ROLE_LABEL[code] || code;
}

function roleStep1Ok(): boolean {
  const m = roleModal.value;
  if (!m || !m.role) return false;
  return m.role.toUpperCase() !== (m.user.role || "").toUpperCase();
}

async function submitRole() {
  const m = roleModal.value;
  if (!m) return;
  acting.value = true;
  try {
    const r = await assignAdminUserRole(m.user.id, m.role, roleReason.value.trim(), true);
    message.success(r?.message || "角色已更新");
    roleModal.value = null;
    await load();
  } catch (e: unknown) {
    message.error(errOf(e));
  } finally {
    acting.value = false;
  }
}

onMounted(() => void load());
</script>

<template>
  <div class="ad-list">
    <div class="ad-card">
      <h2 class="ad-title">用户与账号治理</h2>
      <p class="ad-sub">
        治理动作即时生效于真实链路：限制发帖 → 发布/编辑提交被拒；暂停评论 → 新评论被拒；
        暂停账号 → 登录即拒绝。管理员账号不可被治理（保护管理入口）。
      </p>
      <div class="ad-form-row" style="margin-bottom: 12px">
        <input v-model="filters.keyword" class="ad-input" style="width: 180px" placeholder="用户名 / 昵称搜索" @keyup.enter="search" />
        <select v-model="filters.status" class="ad-select" style="width: 110px">
          <option value="">全部状态</option>
          <option value="ACTIVE">正常</option>
          <option value="SUSPENDED">已暂停</option>
        </select>
        <input v-model="filters.from" class="ad-input" style="width: 140px" type="date" title="注册起始日" />
        <span style="font-size: 12px; color: #8c8a83">至</span>
        <input v-model="filters.to" class="ad-input" style="width: 140px" type="date" title="注册截止日" />
        <button class="ad-btn ad-btn--ok" type="button" @click="search">查询</button>
      </div>
    </div>

    <div v-if="loading" class="ad-empty">加载中…</div>
    <div v-else-if="rows.length === 0" class="ad-empty">没有符合条件的用户</div>

    <article v-for="u in rows" :key="u.id" class="ad-item" style="align-items: flex-start">
      <div class="ad-item__main" role="button" tabindex="0" @click="goUser(u)">
        <div class="ad-item__title">
          {{ u.nickname || u.username }}
          <span class="ad-badge ad-badge--info" v-if="isAdminSideRole(u.role)">
            🛡️ {{ roleLabelOf(u) }}
          </span>
          <span class="ad-badge ad-badge--no" v-else-if="u.account_status === 'SUSPENDED'">已暂停</span>
          <span class="ad-badge ad-badge--warn" v-else>正常</span>
          <span class="ad-badge ad-badge--warn" v-if="u.post_limited">🚫发帖受限</span>
          <span class="ad-badge ad-badge--warn" v-if="u.comment_banned">💬禁评</span>
          <span class="ad-badge ad-badge--no" v-if="u.violation_count > 0">违规 ×{{ u.violation_count }}</span>
        </div>
        <p class="ad-item__meta">
          @{{ u.username }} · {{ u.post_count }} 篇攻略
          <template v-if="u.last_login_at"> · 最近登录 {{ u.last_login_at.slice(0, 16) }}</template>
          <template v-if="u.created_at"> · 注册于 {{ u.created_at.slice(0, 10) }}</template>
        </p>
      </div>
      <div class="ad-ops">
        <button class="ad-btn" type="button" @click="goUser(u)">主页 ›</button>
        <button
          v-if="canAssignRoleTo(u)"
          class="ad-btn ad-btn--ok"
          type="button"
          title="分配管理端角色（高风险操作，需二次确认）"
          @click="openRole(u)"
        >
          🛡️ 分配角色
        </button>
        <template v-for="act in actionsOf(u)" :key="act">
          <button
            v-if="act === 'SUSPEND' || act === 'POST_LIMIT' || act === 'COMMENT_BAN'"
            class="ad-btn ad-btn--no"
            type="button"
            @click="openGovern(u, act)"
          >
            {{ ACTION_BTN[act] }}
          </button>
          <button v-else class="ad-btn ad-btn--ok" type="button" @click="openGovern(u, act)">
            {{ ACTION_BTN[act] }}
          </button>
        </template>
      </div>
    </article>

    <!-- 分页 -->
    <div v-if="total > filters.pageSize" class="ad-card" style="display: flex; justify-content: space-between; align-items: center">
      <span style="font-size: 12px; color: #8c8a83">共 {{ total }} 条</span>
      <div class="ad-form-row">
        <button class="ad-btn" type="button" :disabled="filters.page <= 1" @click="pageOf(filters.page - 1)">上一页</button>
        <span style="font-size: 12px; color: #6b6861">第 {{ filters.page }} 页</span>
        <button class="ad-btn" type="button" :disabled="filters.page * filters.pageSize >= total" @click="pageOf(filters.page + 1)">下一页</button>
      </div>
    </div>

    <!-- 治理模态 -->
    <div v-if="modal" class="ad-modal" @click.self="modal = null">
      <div class="ad-modal__card">
        <h3 class="ad-modal__title">
          {{ ACTION_BTN[modal.action] }} · {{ modal.user.nickname || modal.user.username }}
        </h3>
        <p style="font-size: 13px; color: #6b6861; margin: 4px 0 12px">
          @{{ modal.user.username }}（{{ modal.user.post_count }} 篇攻略，违规 {{ modal.user.violation_count }} 次）
        </p>
        <p style="font-size: 13px; margin: 0 0 8px">
          <span v-if="modal.danger" style="color: #d4380d">⚠️ {{ modal.note }}</span>
          <span v-else style="color: #389e0d">{{ modal.note }}</span>
        </p>
        <textarea v-model="reason" class="ad-textarea" rows="3" placeholder="治理原因（写入审计日志，建议填写，如：多次违规/广告刷屏）"></textarea>
        <div class="ad-form-row" style="justify-content: flex-end; margin-top: 12px">
          <button class="ad-btn" type="button" @click="modal = null">取消</button>
          <button class="ad-btn ad-btn--ok" type="button" :disabled="acting" @click="submitGovern">
            {{ acting ? "处理中…" : "确认执行" }}
          </button>
        </div>
      </div>
    </div>

    <!-- 角色分配模态（高风险操作：先选角色，再二次确认） -->
    <div v-if="roleModal" class="ad-modal" @click.self="roleModal = null">
      <div class="ad-modal__card">
        <h3 class="ad-modal__title">
          分配角色 · {{ roleModal.user.nickname || roleModal.user.username }}
        </h3>
        <p style="font-size: 12px; color: #8c8a83; margin: 0 0 10px">
          当前角色：{{ roleLabelOf(roleModal.user) }}
        </p>

        <!-- 第一步：选择目标角色 + 填写原因 -->
        <template v-if="!roleConfirmed">
          <label
            v-for="r in ROLE_OPTIONS"
            :key="r.code"
            style="display: flex; gap: 10px; align-items: flex-start; padding: 9px 11px; border: 1px solid #ece8e0; border-radius: 10px; cursor: pointer; margin-bottom: 6px"
            :style="roleModal.role === r.code ? 'border-color: #2f7770; background: rgba(47,119,112,0.06)' : ''"
          >
            <input
              v-model="roleModal.role"
              type="radio"
              :value="r.code"
              :disabled="r.code === (roleModal.user.role || '').toUpperCase()"
              style="margin-top: 3px"
            />
            <span>
              <b style="display: block; font-size: 13px; color: #1d1d1b">{{ r.label }}</b>
              <span style="font-size: 12px; color: #8c8a83">{{ r.desc }}</span>
            </span>
          </label>
          <textarea
            v-model="roleReason"
            class="ad-textarea"
            rows="2"
            placeholder="变更原因（必填，写入审计日志，如：运营组新同事 / 转岗做内容审核）"
          ></textarea>
          <div class="ad-form-row" style="justify-content: flex-end">
            <button class="ad-btn" type="button" @click="roleModal = null">取消</button>
            <button
              class="ad-btn ad-btn--ok"
              type="button"
              :disabled="!roleStep1Ok() || !roleReason.trim()"
              @click="roleConfirmed = true"
            >
              下一步 ›
            </button>
          </div>
        </template>

        <!-- 第二步：二次确认（高风险操作，服务端同样要求 confirm=true） -->
        <template v-else>
          <div class="ad-degraded" style="display: block">
            ⚠️ 高风险操作：角色变更<b>立即生效</b>，对方的后台菜单与可操作范围会随之改变。请确认无误后再提交。
          </div>
          <p style="font-size: 13px; line-height: 1.9; margin: 0">
            {{ roleLabelOf(roleModal.user) }} → <b>{{ labelOfRole(roleModal.role) }}</b><br />
            <span style="color: #8c8a83">变更后可操作：{{ descOfRole(roleModal.role) }}</span><br />
            <span style="color: #8c8a83">原因：{{ roleReason.trim() }}</span>
          </p>
          <div class="ad-form-row" style="justify-content: flex-end">
            <button class="ad-btn" type="button" @click="roleConfirmed = false">返回修改</button>
            <button class="ad-btn ad-btn--no" type="button" :disabled="acting" @click="submitRole">
              {{ acting ? "提交中…" : "确认分配" }}
            </button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>
