<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { message } from "ant-design-vue";

import type { AdminSpotItem, AdminSpotSummary } from "../../services/api";
import {
  flagAdminSpot,
  listAdminSpots,
  mergeAdminSpot,
  offlineAdminSpot,
  onlineAdminSpot,
  rematchAdminSpotGuide,
  resyncAdminSpot,
  updateAdminSpot,
} from "../../services/api";

/**
 * 景点数据治理（设计方案 §5 /admin/spots）：
 * 查看/修正景点主档数据质量 —— 编辑字段（自动人工锁定，同步不再覆盖）、标记异常、
 * 上下线、重复合并、单点重同步、重匹配攻略。全部动作后端审计留痕。
 */
const loading = ref(true);
const acting = ref(false);
const rows = ref<AdminSpotItem[]>([]);
const total = ref(0);
const summary = ref<AdminSpotSummary>({ total: 0, online: 0, offline: 0, flagged: 0, merged: 0 });

const filters = reactive({ city: "", status: "", flag: "", keyword: "", page: 1, pageSize: 20 });

/** 当前模态：{ type, spot }，type = edit/flag/offline/merge */
const modal = ref<{ type: "edit" | "flag" | "offline" | "merge"; spot: AdminSpotItem } | null>(null);
/** 编辑表单（含解锁勾选） */
const editForm = reactive({
  name: "",
  address: "",
  category: "",
  description: "",
  tags: "",
  imageUrl: "",
  longitude: "",
  latitude: "",
  unlock: [] as string[],
});
/** 标记/下线/合并表单 */
const opForm = reactive({ flag: "", reason: "", targetSpotId: "" });

const STATUS_LABEL: Record<string, string> = { ONLINE: "在线", OFFLINE: "已下线" };
const FLAG_LABEL: Record<string, string> = {
  NON_SPOT: "非景点",
  CLOSED: "已关闭",
  OUTDATED: "过时待核验",
  ERROR_POI: "错误POI",
};
const QUALITY_LABEL: Record<string, string> = {
  POI_ONLY: "仅POI",
  GUIDE_MATCHED: "攻略匹配",
  VERIFIED: "攻略+校验",
};
const SOURCE_LABEL: Record<string, string> = {
  AMAP: "高德",
  RAG: "攻略",
  AMAP_AND_RAG: "高德+攻略",
};
const FLAG_OPTIONS = ["NON_SPOT", "CLOSED", "OUTDATED", "ERROR_POI"];

const lockedFields = (s: AdminSpotItem): string[] =>
  s.manual_override_fields ? s.manual_override_fields.split(",").map((f) => f.trim()).filter(Boolean) : [];

async function load() {
  loading.value = true;
  try {
    const resp = await listAdminSpots({
      city: filters.city.trim() || undefined,
      status: filters.status || undefined,
      flag: filters.flag || undefined,
      keyword: filters.keyword.trim() || undefined,
      page: filters.page,
      pageSize: filters.pageSize,
    });
    rows.value = resp.items;
    total.value = resp.total;
    summary.value = resp.summary;
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

function errOf(e: unknown): string {
  return (e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败";
}

async function run(action: () => Promise<unknown>, okText: string, reload = true) {
  acting.value = true;
  try {
    const r = await action();
    message.success(r && typeof r === "object" && "message" in r ? String((r as { message: string }).message) : okText);
    modal.value = null;
    if (reload) await load();
  } catch (e: unknown) {
    message.error(errOf(e));
  } finally {
    acting.value = false;
  }
}

/* ---------- 模态入口 ---------- */
function openEdit(s: AdminSpotItem) {
  editForm.name = s.name ?? "";
  editForm.address = s.address ?? "";
  editForm.category = s.category ?? "";
  editForm.description = s.description ?? "";
  editForm.tags = s.tags ?? "";
  editForm.imageUrl = s.image_url ?? "";
  editForm.longitude = s.longitude == null ? "" : String(s.longitude);
  editForm.latitude = s.latitude == null ? "" : String(s.latitude);
  editForm.unlock = lockedFields(s);
  modal.value = { type: "edit", spot: s };
}

function openFlag(s: AdminSpotItem) {
  opForm.flag = s.flag ?? "";
  opForm.reason = s.flag_reason ?? "";
  modal.value = { type: "flag", spot: s };
}

function openOffline(s: AdminSpotItem) {
  opForm.reason = s.flag_reason ?? "";
  modal.value = { type: "offline", spot: s };
}

function openMerge(s: AdminSpotItem) {
  opForm.targetSpotId = "";
  opForm.reason = "";
  modal.value = { type: "merge", spot: s };
}

/* ---------- 提交 ---------- */
function submitEdit() {
  const s = modal.value?.spot;
  if (!s) return;
  if (!editForm.name.trim()) return void message.warning("景点名称不能为空");
  const lon = editForm.longitude.trim() === "" ? undefined : Number(editForm.longitude);
  const lat = editForm.latitude.trim() === "" ? undefined : Number(editForm.latitude);
  if ((editForm.longitude.trim() !== "" || editForm.latitude.trim() !== "") && (Number.isNaN(lon) || Number.isNaN(lat))) {
    return void message.warning("经度/纬度需为数字（可留空表示不修改）");
  }
  void run(
    () =>
      updateAdminSpot(s.id, {
        name: editForm.name.trim(),
        address: editForm.address,
        category: editForm.category,
        description: editForm.description,
        tags: editForm.tags,
        imageUrl: editForm.imageUrl,
        ...(lon !== undefined && lat !== undefined ? { longitude: lon, latitude: lat } : {}),
        ...(editForm.unlock.length ? { unlockFields: editForm.unlock } : {}),
        reason: "景点数据治理后台编辑",
      }),
    "已保存（修改字段已人工锁定，自动同步不再覆盖）"
  );
}

function submitFlag() {
  const s = modal.value?.spot;
  if (!s) return;
  void run(
    () => flagAdminSpot(s.id, opForm.flag.trim() || undefined, opForm.reason.trim() || undefined),
    opForm.flag.trim() ? "已标记" : "已清除标记"
  );
}

function submitOffline() {
  const s = modal.value?.spot;
  if (!s) return;
  void run(() => offlineAdminSpot(s.id, opForm.reason.trim() || undefined), "已下线（不再进入推荐与展示）");
}

function submitOnline(s: AdminSpotItem) {
  void run(() => onlineAdminSpot(s.id), "已重新上线");
}

function submitMerge() {
  const s = modal.value?.spot;
  if (!s) return;
  if (!opForm.targetSpotId.trim()) return void message.warning("请填写合并目标 spot_id");
  if (!window.confirm(`确认把「${s.name}」合并到 ${opForm.targetSpotId.trim()}？\n本景点将下线并指向目标，收藏/帖子/攻略引用自动重定向。`)) return;
  void run(() => mergeAdminSpot(s.id, opForm.targetSpotId.trim(), opForm.reason.trim() || undefined), "合并完成");
}

function submitResync(s: AdminSpotItem) {
  if (!window.confirm(`确认强制从高德重新同步「${s.name}」？（绕过缓存；人工锁定字段不会被覆盖）`)) return;
  void run(() => resyncAdminSpot(s.id), "已重同步");
}

function submitRematch(s: AdminSpotItem) {
  void run(() => rematchAdminSpotGuide(s.id), "已重新匹配攻略");
}

onMounted(() => void load());
</script>

<template>
  <div class="ad-list">
    <!-- 头部 + 过滤 -->
    <div class="ad-card">
      <h2 class="ad-title">景点数据治理</h2>
      <p class="ad-sub">
        景点主档 = 高德同步 + RAG 攻略增强。管理员修正自动「人工锁定」：自动同步只更新未锁定字段；
        下线 / 合并别名 / 异常标记的景点不再进入推荐与展示。
      </p>
      <div class="ad-form-row" style="margin-bottom: 12px">
        <input v-model="filters.city" class="ad-input" style="width: 120px" placeholder="城市（如 北京）" @keyup.enter="search" />
        <select v-model="filters.status" class="ad-select" style="width: 110px">
          <option value="">全部状态</option>
          <option value="ONLINE">在线</option>
          <option value="OFFLINE">已下线</option>
        </select>
        <select v-model="filters.flag" class="ad-select" style="width: 140px">
          <option value="">全部标记</option>
          <option value="NONE">正常（无标记）</option>
          <option value="ANY">有异常标记</option>
          <option v-for="f in FLAG_OPTIONS" :key="f" :value="f">{{ FLAG_LABEL[f] }}（{{ f }}）</option>
        </select>
        <input v-model="filters.keyword" class="ad-input" style="width: 200px" placeholder="名称 / spot_id / poi_id 搜索" @keyup.enter="search" />
        <button class="ad-btn ad-btn--ok" type="button" @click="search">查询</button>
      </div>
      <div class="ad-kpis">
        <div class="ad-kpi"><b>{{ summary.total }}</b><span>景点总数（{{ filters.city || "全站" }}）</span></div>
        <div class="ad-kpi"><b>{{ summary.online }}</b><span>在线</span></div>
        <div class="ad-kpi"><b>{{ summary.offline }}</b><span>已下线</span></div>
        <div class="ad-kpi"><b>{{ summary.flagged }}</b><span>异常标记</span></div>
        <div class="ad-kpi"><b>{{ summary.merged }}</b><span>合并别名</span></div>
      </div>
    </div>

    <!-- 列表 -->
    <div v-if="loading" class="ad-empty">加载中…</div>
    <div v-else-if="rows.length === 0" class="ad-empty">暂无景点数据（换个城市或清空筛选试试）</div>

    <article v-for="s in rows" :key="s.id" class="ad-item" style="align-items: flex-start">
      <div class="ad-item__main">
        <div class="ad-item__title">
          {{ s.name }}
          <span class="ad-badge ad-badge--ok" v-if="s.status === 'ONLINE'">在线</span>
          <span class="ad-badge ad-badge--no" v-else-if="s.status === 'OFFLINE'">已下线</span>
          <span class="ad-badge ad-badge--no" v-if="s.flag">异常：{{ FLAG_LABEL[s.flag] || s.flag }}</span>
          <span class="ad-badge ad-badge--warn" v-if="s.merged_into">已合并→{{ s.merged_into }}</span>
          <span class="ad-badge ad-badge--info" v-if="s.manual_override" :title="`人工锁定：${s.manual_override_fields || ''}`">
            🔒 {{ lockedFields(s).length }} 字段锁定
          </span>
        </div>
        <p class="ad-item__meta">
          {{ s.city }} · {{ s.category || "未分类" }}
          <span class="ad-badge ad-badge--muted">{{ QUALITY_LABEL[s.data_quality || ""] || s.data_quality || "—" }}</span>
          <span class="ad-badge ad-badge--muted">{{ SOURCE_LABEL[s.source || ""] || s.source || "—" }}</span>
          <span class="ad-badge ad-badge--muted">spot: {{ s.spot_id }}</span>
          <span v-if="s.poi_id" class="ad-badge ad-badge--muted">poi: {{ s.poi_id }}</span>
        </p>
        <p class="ad-item__meta" style="margin-top: 4px">
          {{ s.address || "无地址" }}
          <template v-if="s.longitude != null"> · {{ s.longitude.toFixed(5) }}, {{ s.latitude?.toFixed(5) }}</template>
          <template v-if="s.description"> · {{ s.description }}</template>
        </p>
        <p class="ad-item__meta">
          <template v-if="s.flag_reason">原因：{{ s.flag_reason }} · </template>
          <template v-if="s.last_verified_by">最近核验：{{ s.last_verified_by }}（{{ s.last_verified_at?.replace("T", " ").slice(0, 16) }}）· </template>
          <template v-if="s.last_synced_at">同步：{{ s.last_synced_at.replace("T", " ").slice(0, 16) }}</template>
          <template v-else>从未同步</template>
        </p>
      </div>
      <div class="ad-ops">
        <button class="ad-btn" type="button" @click="openEdit(s)">编辑</button>
        <button class="ad-btn" type="button" @click="openFlag(s)">标记</button>
        <button class="ad-btn" type="button" @click="openMerge(s)">合并</button>
        <button class="ad-btn" type="button" @click="submitResync(s)">重同步</button>
        <button class="ad-btn" type="button" @click="submitRematch(s)">重匹配攻略</button>
        <button v-if="s.status === 'ONLINE'" class="ad-btn ad-btn--no" type="button" @click="openOffline(s)">下线</button>
        <button v-else-if="s.merged_into" class="ad-btn" type="button" disabled title="合并别名不可直接上线">已合并</button>
        <button v-else class="ad-btn ad-btn--ok" type="button" @click="submitOnline(s)">上线</button>
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

    <!-- 通用模态 -->
    <div v-if="modal" class="ad-modal" @click.self="modal = null">
      <div class="ad-modal__card">
        <h3 class="ad-modal__title">
          <template v-if="modal.type === 'edit'">编辑景点 · {{ modal.spot.name }}</template>
          <template v-else-if="modal.type === 'flag'">治理标记 · {{ modal.spot.name }}</template>
          <template v-else-if="modal.type === 'offline'">下线景点 · {{ modal.spot.name }}</template>
          <template v-else>合并重复景点 · {{ modal.spot.name }}</template>
        </h3>

        <!-- 编辑 -->
        <template v-if="modal.type === 'edit'">
          <div class="ad-form-row">
            <label style="font-size: 12px; color: #6b6861">名称</label>
            <input v-model="editForm.name" class="ad-input" style="flex: 1" />
          </div>
          <div class="ad-form-row">
            <label style="font-size: 12px; color: #6b6861">城市</label>
            <input :value="modal.spot.city" class="ad-input" style="flex: 1" disabled title="城市归属不允许直接修改：跨城重复用「合并」，错误 POI 标记 ERROR_POI" />
          </div>
          <div class="ad-form-row">
            <label style="font-size: 12px; color: #6b6861">地址</label>
            <input v-model="editForm.address" class="ad-input" style="flex: 1" placeholder="留空可清空" />
          </div>
          <div class="ad-form-row">
            <label style="font-size: 12px; color: #6b6861">业态</label>
            <input v-model="editForm.category" class="ad-input" style="flex: 1" placeholder="如：风景名胜" />
          </div>
          <div class="ad-form-row">
            <label style="font-size: 12px; color: #6b6861">标签</label>
            <input v-model="editForm.tags" class="ad-input" style="flex: 1" placeholder="逗号分隔：自然风景,历史文化" />
          </div>
          <div class="ad-form-row">
            <label style="font-size: 12px; color: #6b6861">封面URL</label>
            <input v-model="editForm.imageUrl" class="ad-input" style="flex: 1" placeholder="http(s) 图片地址" />
          </div>
          <div class="ad-form-row">
            <label style="font-size: 12px; color: #6b6861">坐标</label>
            <input v-model="editForm.longitude" class="ad-input" style="flex: 1" placeholder="经度（留空不改）" />
            <input v-model="editForm.latitude" class="ad-input" style="flex: 1" placeholder="纬度（留空不改）" />
          </div>
          <textarea v-model="editForm.description" class="ad-textarea" rows="4" placeholder="景点简介（留空可清空）"></textarea>
          <div v-if="editForm.unlock.length" style="font-size: 12px; color: #6b6861">
            已锁定字段（取消勾选 = 解锁，解锁后自动同步可再次覆盖该字段）：
            <label v-for="f in editForm.unlock" :key="f" style="margin-right: 10px; white-space: nowrap">
              <input v-model="editForm.unlock" type="checkbox" :value="f" /> {{ f }}
            </label>
          </div>
          <p style="font-size: 12px; color: #9a978f; margin: 0">
            修改的字段会自动加入人工锁定；「解锁」仅对当前已锁字段生效。
          </p>
        </template>

        <!-- 标记 -->
        <template v-else-if="modal.type === 'flag'">
          <div class="ad-form-row">
            <select v-model="opForm.flag" class="ad-select" style="flex: 1">
              <option value="">清除标记（恢复正常）</option>
              <option v-for="f in FLAG_OPTIONS" :key="f" :value="f">{{ FLAG_LABEL[f] }}（{{ f }}）</option>
            </select>
          </div>
          <textarea v-model="opForm.reason" class="ad-textarea" rows="3" placeholder="治理原因（进入审计日志与 flag_reason）"></textarea>
          <p style="font-size: 12px; color: #9a978f; margin: 0">
            非景点 / 已关闭 / 错误POI 会自动下线；「过时待核验」仅提示，重同步成功后自动清除。
          </p>
        </template>

        <!-- 下线 -->
        <template v-else-if="modal.type === 'offline'">
          <textarea v-model="opForm.reason" class="ad-textarea" rows="3" placeholder="下线原因（进入审计日志）"></textarea>
        </template>

        <!-- 合并 -->
        <template v-else>
          <div class="ad-form-row">
            <label style="font-size: 12px; color: #6b6861">目标 spot_id</label>
            <input v-model="opForm.targetSpotId" class="ad-input" style="flex: 1" placeholder="如 spot_北京_B0FFHXXXX" />
          </div>
          <textarea v-model="opForm.reason" class="ad-textarea" rows="3" placeholder="合并原因（进入审计日志）"></textarea>
          <p style="font-size: 12px; color: #9a978f; margin: 0">
            仅支持同城合并；本景点下线并指向目标，收藏/帖子/攻略关联自动重定向（历史日志保留原 ID）。
          </p>
        </template>

        <div class="ad-form-row" style="justify-content: flex-end">
          <button class="ad-btn" type="button" @click="modal = null">取消</button>
          <button
            class="ad-btn ad-btn--ok"
            type="button"
            :disabled="acting"
            @click="modal.type === 'edit' ? submitEdit() : modal.type === 'flag' ? submitFlag() : modal.type === 'offline' ? submitOffline() : submitMerge()"
          >
            {{ acting ? "处理中…" : "确定" }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
