import axios from "axios";

import type {
  AgentTraceResponse,
  AgentTraceStep,
  AuthResponse,
  BehaviorRequest,
  BehaviorResponse,
  CityTopic,
  CommentItem,
  CommentPage,
  FollowResponse,
  Itinerary,
  LoginRequest,
  PostDetail,
  PostItem,
  PostPage,
  PostPayload,
  PreferenceAdjustment,
  ProfileResponse,
  ProfileStatsResponse,
  ProfileSummary,
  QuestionnaireRequest,
  RecommendationFeed,
  RegisterRequest,
  ReportItem,
  ReportPayload,
  SpotDetail,
  SpotFavorite,
  TripDetailResponse,
  TripListResponse,
  TripRequestPayload,
  TripSaveResponse,
  User,
  UserHome,
  WeatherForecastResponse,
} from "../types";

export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

/**
 * 图片 URL 归一化：后端返回的相对路径（如 /uploads/xxx.jpg）必须拼上 API_BASE_URL 才能加载
 * —— 页面 origin 是前端(5173)，裸相对路径会指向前端导致 404「加载失败」；
 * 绝对 http(s)://、协议相对 //、data: 原样返回。所有封面/上传图展示统一走此函数。
 */
export function resolveImageUrl(u?: string | null): string {
  if (!u) return "";
  if (/^(https?:)?\/\//i.test(u) || u.startsWith("data:")) return u;
  return `${API_BASE_URL}${u.startsWith("/") ? u : `/${u}`}`;
}

const TOKEN_KEY = "ai_travel_token";
const USER_KEY = "ai_travel_user";

/* ---------- 登录态存取（localStorage） ---------- */

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

export function getUser(): User | null {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as User;
  } catch {
    return null;
  }
}

export function setUser(user: User): void {
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

/** 当前登录用户 id；未登录返回 null（后端始终以 token 解析的 userId 为准） */
export function getUserId(): string | null {
  return getUser()?.id ?? null;
}

export function isLoggedIn(): boolean {
  return getToken() !== null;
}

/** 登录失效全局信号：App.vue 监听后回到登录页 */
export function dispatchUnauthorized(): void {
  clearToken();
  window.dispatchEvent(new CustomEvent("auth:unauthorized"));
}

/** 上传图片（帖子封面）：返回后端相对 URL（展示时用 resolveImageUrl 拼全） */
export async function uploadImage(file: File): Promise<string> {
  const fd = new FormData();
  fd.append("file", file);
  const resp = await api.post<{ success?: boolean; data?: { url?: string } }>(
    "/file/upload-image",
    fd
  );
  const url = resp.data?.data?.url;
  if (!url) throw new Error("上传接口未返回图片地址");
  return url;
}

const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 120000,
});

/* 请求拦截器：统一附带 Authorization: Bearer <token> */
api.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/* 响应拦截器：401（登录失效）→ 清登录态 + 全局通知；/auth/* 的 401/409 属业务错误，不触发 */
api.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status;
    const url: string = error.config?.url ?? "";
    if (status === 401 && !url.startsWith("/auth/")) {
      dispatchUnauthorized();
    }
    return Promise.reject(error);
  }
);

export async function generateTrip(payload: TripRequestPayload): Promise<Itinerary> {
  const response = await api.post<Itinerary>("/trip/generate", payload);
  return response.data;
}

export async function generateTripWithTrace(
  payload: TripRequestPayload
): Promise<AgentTraceResponse> {
  const response = await api.post<AgentTraceResponse>(
    "/trip/generate-with-trace",
    payload
  );
  return response.data;
}

export interface StreamHandlers {
  onProgress?: (phase: string, message: string) => void;
  onStep?: (step: AgentTraceStep) => void;
}

/**
 * SSE 流式生成行程（绕过 axios 的 120s 超时）。
 * 每完成一个 Agent 阶段推一条 step，阶段间推 progress，最后推 itinerary + done。
 * 通过 AbortSignal 支持取消（组件卸载时 abort 断开连接）。
 */
export async function streamGenerateTrip(
  payload: TripRequestPayload,
  handlers: StreamHandlers,
  signal?: AbortSignal
): Promise<AgentTraceResponse> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const resp = await fetch(`${API_BASE_URL}/trip/generate-stream`, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
    signal,
  });
  if (!resp.ok || !resp.body) {
    let msg = `后端返回 ${resp.status}`;
    try {
      const j = await resp.json();
      msg = (j as { message?: string }).message || msg;
    } catch {
      /* 非 JSON 错误体，保留默认文案 */
    }
    if (resp.status === 401) dispatchUnauthorized();
    throw new Error(msg);
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  const result: AgentTraceResponse = {
    success: true,
    trace: [],
    collected_data: {},
    token_usage: {},
    errors: [],
  };

  const dispatch = (event: string, data: string) => {
    if (!data) return;
    if (event === "step") {
      const step = JSON.parse(data) as AgentTraceStep;
      result.trace.push(step);
      handlers.onStep?.(step);
    } else if (event === "progress") {
      const p = JSON.parse(data) as { phase: string; message: string };
      handlers.onProgress?.(p.phase, p.message);
    } else if (event === "itinerary") {
      result.itinerary = JSON.parse(data) as Itinerary;
    } else if (event === "done") {
      const d = JSON.parse(data) as { token_usage?: Record<string, number>; collected_data?: Record<string, unknown> };
      result.token_usage = d.token_usage || {};
      result.collected_data = d.collected_data || {};
    } else if (event === "error") {
      const e = JSON.parse(data) as { message?: string };
      throw new Error(e.message || "生成失败");
    }
  };

  const processBlock = (block: string) => {
    let event = "message";
    let data = "";
    for (const rawLine of block.split("\n")) {
      if (rawLine.startsWith("event:")) event = rawLine.slice(6).trim();
      else if (rawLine.startsWith("data:")) data += (data ? "\n" : "") + rawLine.slice(5);
    }
    dispatch(event, data);
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");
      let idx;
      while ((idx = buffer.indexOf("\n\n")) !== -1) {
        processBlock(buffer.slice(0, idx));
        buffer = buffer.slice(idx + 2);
      }
    }
    if (buffer.trim()) processBlock(buffer);
  } finally {
    reader.releaseLock();
  }
  return result;
}

export async function saveTrip(
  itinerary: Itinerary,
  trace?: AgentTraceStep[]
): Promise<TripSaveResponse> {
  const response = await api.post<TripSaveResponse>("/trip/save", {
    trip_id: itinerary.trip_id,
    itinerary,
    // 后端以 token 解析的 userId 为准（防伪造）；这里仍传真实值保持请求体完整
    user_id: getUserId() ?? "",
    trace: trace ?? [],
  });
  return response.data;
}

export async function listTrips(): Promise<TripListResponse> {
  const response = await api.get<TripListResponse>("/trip");
  return response.data;
}

export async function getTripDetail(tripId: string): Promise<TripDetailResponse> {
  const response = await api.get<TripDetailResponse>(`/trip/${tripId}`);
  return response.data;
}

export async function deleteTrip(tripId: string): Promise<void> {
  await api.delete(`/trip/${tripId}`);
}

export async function fetchWeatherForecast(
  city: string,
  startDate?: string,
  endDate?: string
): Promise<WeatherForecastResponse> {
  const response = await api.get<WeatherForecastResponse>("/weather/forecast", {
    params: { city, start_date: startDate, end_date: endDate },
  });
  return response.data;
}

/* ---------- 个性化画像 API（阶段一：结构化画像） ---------- */

export async function getUserProfile(): Promise<ProfileResponse> {
  const response = await api.get<ProfileResponse>("/user/profile");
  return response.data;
}

export async function saveProfileQuestionnaire(
  payload: QuestionnaireRequest
): Promise<ProfileResponse> {
  const response = await api.put<ProfileResponse>("/user/profile/questionnaire", payload);
  return response.data;
}

/** 上报结果页行为反馈（收藏/不感兴趣/评分等），后端落库并增量更新画像权重 */
export async function reportBehavior(
  payload: BehaviorRequest
): Promise<BehaviorResponse> {
  const response = await api.post<BehaviorResponse>("/user/behavior", payload);
  return response.data;
}

/** 查询个性化效果统计（阶段四：偏好命中率/负反馈率/满意度均值） */
export async function getProfileStats(): Promise<ProfileStatsResponse> {
  const response = await api.get<ProfileStatsResponse>("/user/profile/stats");
  return response.data;
}

/* ---------- 产品化阶段一：推荐景点 / 详情 / 收藏 / 旅行摘要 ---------- */

/**
 * 生成一次"内容上下文"的曝光幂等键（P1-5 审查报告）。
 * 前端在进入某个推荐上下文（城市/排序/筛选变化或主动刷新）时生成一次并随请求带上
 * feed_trace_id：同一上下文内偶发的重复请求（组件重复渲染/重试/接口重放）共享同一 trace，
 * 后端据此对 (user, trace, item) 判重，避免同一曝光被双写稀释推荐效果统计的分母。
 */
export function newFeedTrace(): string {
  return `tr-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * 推荐景点分页流（首页"为你推荐"与 /recommendations 发现页共用）。
 * sort: personalized（默认，画像排序）/ popular（攻略质量优先，第一版无真实热度埋点）/ latest（最近同步保序）。
 * 登录有画像 → 个性化；无画像/新用户 → 后端自动降级攻略质量排序（不返回空列表）。
 * feedTrace：页面会话幂等键（可选；不传 = 旧口径每次请求各记一次曝光）。
 */
export async function getRecommendations(
  city?: string | null,
  page = 1,
  pageSize = 12,
  sort: "personalized" | "popular" | "latest" = "personalized",
  feedTrace?: string
): Promise<RecommendationFeed> {
  const response = await api.get<{ success: boolean; data: RecommendationFeed }>(
    "/recommendations/spots",
    {
      params: {
        city: city ?? undefined,
        page,
        pageSize,
        sort,
        feed_trace_id: feedTrace || undefined,
      },
    }
  );
  return response.data.data;
}

/** 景点详情（含可信度三档/是否去过/收藏状态/同城相关推荐） */
export async function fetchSpotDetail(spotId: string): Promise<SpotDetail> {
  const response = await api.get<{ success: boolean; data: SpotDetail }>(
    `/spots/${encodeURIComponent(spotId)}`
  );
  return response.data.data;
}

/** 收藏结果（existed=true 表示已在收藏中，首次收藏会触发画像 SAVE 升权） */
export interface FavoriteResponse {
  success: boolean;
  existed?: boolean;
  message?: string;
  adjustments?: PreferenceAdjustment[];
}

export async function favoriteSpot(spotId: string): Promise<FavoriteResponse> {
  const response = await api.post<FavoriteResponse>(
    `/spots/${encodeURIComponent(spotId)}/favorite`
  );
  return response.data;
}

/** 取消收藏（幂等；只删收藏行，不反噬画像权重） */
export async function unfavoriteSpot(
  spotId: string
): Promise<{ success: boolean; message?: string }> {
  const response = await api.delete<{ success: boolean; message?: string }>(
    `/spots/${encodeURIComponent(spotId)}/favorite`
  );
  return response.data;
}

/** 我的景点收藏列表（/favorites 数据源） */
export interface SpotFavoritesResponse {
  success: boolean;
  items: SpotFavorite[];
  total: number;
  page: number;
}

export async function listFavorites(page = 1, pageSize = 12): Promise<SpotFavoritesResponse> {
  const response = await api.get<SpotFavoritesResponse>("/user/spot-favorites", {
    params: { page, pageSize },
  });
  return response.data;
}

/**
 * 用户旅行摘要（Q5 修复：服务端实时聚合 trip_record，不再展示 user_profile 过时快照；
 * 首页画像卡与 ProfileView 一律用它）。
 */
export async function getProfileSummary(): Promise<ProfileSummary> {
  const response = await api.get<{ success: boolean; data: ProfileSummary }>(
    "/user/profile/summary"
  );
  return response.data.data;
}

/* ---------- 阶段四：城市专题页 ---------- */

/** 城市专题聚合（一次拉齐热门景点 + 最新攻略 + 规模统计，供 /city/:name 专题页首屏） */
export async function getCityTopic(city: string): Promise<CityTopic> {
  const response = await api.get<{ success: boolean; data: CityTopic }>("/city-topic", {
    params: { city },
  });
  return response.data.data;
}

/* ---------- 阶段四：关注 + 用户旅行主页 ---------- */

/** 关注用户（重复关注幂等） */
export async function followUser(userId: string): Promise<FollowResponse> {
  const response = await api.post<FollowResponse>(`/users/${encodeURIComponent(userId)}/follow`);
  return response.data;
}

/** 取关（幂等） */
export async function unfollowUser(userId: string): Promise<FollowResponse> {
  const response = await api.delete<FollowResponse>(`/users/${encodeURIComponent(userId)}/follow`);
  return response.data;
}

/** 关注状态查询 */
export async function getFollowStatus(userId: string): Promise<FollowResponse> {
  const response = await api.get<FollowResponse>(
    `/users/${encodeURIComponent(userId)}/follow/status`
  );
  return response.data;
}

/** 用户旅行主页（公开数据：昵称/粉丝/帖子） */
export async function getUserHome(userId: string): Promise<UserHome> {
  const response = await api.get<{ success: boolean; data: UserHome }>(
    `/users/${encodeURIComponent(userId)}/home`
  );
  return response.data.data;
}

/* ---------- 认证 API ---------- */

/** 当前用户最新信息（阶段二：登录/启动时同步 role；旧 localStorage 缺 role 时用） */
export interface MeResponse {
  success: boolean;
  user?: { id: string; role?: string } | null;
}

export async function fetchMe(): Promise<MeResponse> {
  const response = await api.get<MeResponse>("/auth/me");
  return response.data;
}

export async function register(payload: RegisterRequest): Promise<AuthResponse> {
  const response = await api.post<AuthResponse>("/auth/register", payload);
  return response.data;
}

export async function login(payload: LoginRequest): Promise<AuthResponse> {
  const response = await api.post<AuthResponse>("/auth/login", payload);
  return response.data;
}

/* ---------- 导出（blob 下载，axios 自动带 Authorization 头） ---------- */

async function downloadBlob(url: string, fallbackName: string): Promise<void> {
  const response = await api.get<Blob>(url, { responseType: "blob" });
  const blob = response.data;
  const fileName = extractFileName(response.headers["content-disposition"]) || fallbackName;
  const objectUrl = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = objectUrl;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(objectUrl);
}

/** 从 Content-Disposition 里解析 attachment 文件名；无则回退默认名 */
function extractFileName(contentDisposition?: string): string | null {
  if (!contentDisposition) return null;
  const match = /filename\*=UTF-8''([^;]+)|filename="?([^";]+)"?/.exec(contentDisposition);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1] ?? match[2] ?? "");
  } catch {
    return match[1] ?? match[2] ?? null;
  }
}

export async function exportMarkdown(tripId: string): Promise<void> {
  await downloadBlob(`/export/${encodeURIComponent(tripId)}/markdown`, `${tripId}.md`);
}

export async function exportPdf(tripId: string): Promise<void> {
  await downloadBlob(`/export/${encodeURIComponent(tripId)}/pdf`, `${tripId}.pdf`);
}

/* ---------- 产品化阶段二：社区 API ---------- */

/** 帖子流排序：recommended（为你推荐·阶段三）/ popular（热门）/ latest（最新） */
export type PostSort = "recommended" | "latest" | "popular";
export type FeedPostType =
  | "GUIDE"
  | "SPOT_RECOMMENDATION"
  | "ITINERARY"
  | "NOTE"
  | "";

/** 公开帖子流（city/postType 可选，sort=latest|popular；recommended=个性化） */
export async function getPosts(
  params: {
    city?: string;
    postType?: string;
    sort?: PostSort;
    page?: number;
    pageSize?: number;
    /** P1-5 页面会话幂等键（可选）：sort=recommended 时后端据此对曝光判重 */
    feedTrace?: string;
  } = {}
): Promise<PostPage> {
  const response = await api.get<{ success: boolean } & PostPage>("/community/posts", {
    params: {
      city: params.city || undefined,
      postType: params.postType || undefined,
      sort: params.sort || "latest",
      page: params.page || 1,
      pageSize: params.pageSize || 12,
      feed_trace_id: params.feedTrace || undefined,
    },
  });
  return {
    items: (response.data as unknown as { items: PostItem[] }).items || [],
    total: (response.data as unknown as { total: number }).total || 0,
    page: params.page || 1,
  };
}

/** 帖子详情 */
export async function getPostDetail(postId: number): Promise<PostDetail> {
  const response = await api.get<{ success: boolean; data: PostDetail }>(
    `/community/posts/${postId}`
  );
  return response.data.data;
}

/** 我的帖子（全部状态） */
export async function getMyPosts(page = 1, pageSize = 12): Promise<PostPage> {
  const response = await api.get<{ success: boolean; items: PostItem[]; total: number }>(
    "/community/posts/mine",
    { params: { page, pageSize } }
  );
  return { items: response.data.items || [], total: response.data.total || 0, page };
}

/** 创建帖子（草稿） */
export async function createPost(payload: PostPayload): Promise<{ postId: number; message: string }> {
  const response = await api.post<{ success: boolean; postId: number; message: string }>(
    "/community/posts",
    payload
  );
  return response.data;
}

/** 编辑帖子 */
export async function updatePost(postId: number, payload: PostPayload): Promise<void> {
  await api.put(`/community/posts/${postId}`, payload);
}

/** 删除帖子 */
export async function deletePost(postId: number): Promise<void> {
  await api.delete(`/community/posts/${postId}`);
}

/** 提交审核：返回违规列表（空=成功） */
export async function submitPost(
  postId: number
): Promise<{ success: boolean; violations?: string[]; message?: string }> {
  const response = await api.post<{ success: boolean; violations?: string[]; message?: string }>(
    `/community/posts/${postId}/submit`
  );
  return response.data;
}

/** 点赞/收藏/不喜欢状态变更（POST 生效 / DELETE 取消；幂等） */
export interface InteractionResponse {
  success: boolean;
  liked: boolean;
  favorited: boolean;
  disliked: boolean;
  likeCount: number;
  favoriteCount: number;
}

async function interact(
  postId: number,
  action: "like" | "favorite" | "dislike",
  active: boolean
): Promise<InteractionResponse> {
  const url = `/community/posts/${postId}/${action}`;
  const response = active
    ? await api.post<InteractionResponse>(url)
    : await api.delete<InteractionResponse>(url);
  return response.data;
}

export const likePost = (postId: number) => interact(postId, "like", true);
export const unlikePost = (postId: number) => interact(postId, "like", false);
export const favoritePost = (postId: number) => interact(postId, "favorite", true);
export const unfavoritePost = (postId: number) => interact(postId, "favorite", false);
export const dislikePost = (postId: number) => interact(postId, "dislike", true);
export const undislikePost = (postId: number) => interact(postId, "dislike", false);

/* ---------- 评论 ---------- */

export async function getComments(
  postId: number,
  page = 1,
  pageSize = 20
): Promise<CommentPage> {
  const response = await api.get<{ success: boolean; items: CommentItem[]; total: number; page: number }>(
    `/community/posts/${postId}/comments`,
    { params: { page, pageSize } }
  );
  return { items: response.data.items || [], total: response.data.total || 0, page: response.data.page || 1 };
}

export async function addComment(
  postId: number,
  content: string,
  parentId?: number | null
): Promise<CommentItem> {
  const response = await api.post<{ success: boolean; data: CommentItem }>(
    `/community/posts/${postId}/comments`,
    { content, parent_id: parentId ?? null }
  );
  return response.data.data;
}

export async function deleteComment(commentId: number): Promise<void> {
  await api.delete(`/community/comments/${commentId}`);
}

/* ---------- 举报 / 审核（管理端） ---------- */

export async function createReport(payload: ReportPayload): Promise<{ success: boolean; existed: boolean }> {
  const response = await api.post<{ success: boolean; existed: boolean }>("/community/reports", payload);
  return response.data;
}

export async function getPendingPosts(page = 1, pageSize = 12): Promise<PostPage> {
  const response = await api.get<{ success: boolean; items: PostItem[]; total: number }>(
    "/community/moderation/posts/pending",
    { params: { page, pageSize } }
  );
  return { items: response.data.items || [], total: response.data.total || 0, page };
}

/* ---------- 阶段四：管理后台（帖子全量治理 / 举报历史 / 用户列表） ---------- */

/** 已发布/已隐藏帖子全量队列（PUBLISHED 可下架；HIDDEN 可恢复） */
export async function getAdminPosts(
  status: "PUBLISHED" | "HIDDEN",
  page = 1,
  pageSize = 20
): Promise<PostPage> {
  const response = await api.get<{ success: boolean; items: PostItem[]; total: number }>(
    "/community/moderation/posts",
    { params: { status, page, pageSize } }
  );
  return { items: response.data.items || [], total: response.data.total || 0, page };
}

/** 已处理举报历史（RESOLVED/DISMISSED，含处理人与时间） */
export async function getReportHistory(page = 1, pageSize = 20): Promise<ReportItem[]> {
  const response = await api.get<{ success: boolean; items: ReportItem[] }>(
    "/community/moderation/reports/history",
    { params: { page, pageSize } }
  );
  return response.data.items || [];
}

/** 管理端用户列表项 */
export interface AdminUserItem {
  id: string;
  username: string;
  nickname?: string | null;
  role: string;
  created_at?: string | null;
  post_count: number;
}

/** 管理端用户列表（新注册在前） */
export async function getAdminUsers(page = 1, pageSize = 20): Promise<AdminUserItem[]> {
  const response = await api.get<{ success: boolean; items: AdminUserItem[] }>(
    "/community/moderation/users",
    { params: { page, pageSize } }
  );
  return response.data.items || [];
}

export async function approvePost(postId: number): Promise<void> {
  await api.post(`/community/moderation/posts/${postId}/approve`);
}

export async function rejectPost(postId: number, reason: string): Promise<void> {
  await api.post(`/community/moderation/posts/${postId}/reject`, { reason });
}

export async function hidePost(postId: number): Promise<void> {
  await api.post(`/community/moderation/posts/${postId}/hide`);
}

export async function getPendingReports(page = 1, pageSize = 20): Promise<ReportItem[]> {
  const response = await api.get<{ success: boolean; items: ReportItem[] }>(
    "/community/moderation/reports/pending",
    { params: { page, pageSize } }
  );
  return response.data.items || [];
}

export async function handleReport(reportId: number, action: "RESOLVE" | "DISMISS"): Promise<void> {
  await api.post(`/community/moderation/reports/${reportId}/handle`, { action });
}

/* ---------- 阶段四：推荐 A/B 实验 + 推荐流监控（任务 6/7，管理端） ---------- */

/** A/B 实验列表项（含每变体参与人数） */
export interface ExperimentItem {
  id: number;
  name: string;
  description?: string | null;
  feed_type: "SPOT_FEED" | "POST_FEED";
  strategy: string;
  status: "ACTIVE" | "CLOSED";
  traffic_percent: number;
  control_percent: number;
  control_users: number;
  treatment_users: number;
  started_at?: string | null;
  closed_at?: string | null;
}

/** 实验列表（管理端） */
export async function getExperiments(): Promise<ExperimentItem[]> {
  const response = await api.get<{ success: boolean; items: ExperimentItem[] }>("/admin/experiments");
  return response.data.items || [];
}

/** 创建实验（管理端）：SPOT_FEED→QUALITY_GATE（攻略质量门）/ POST_FEED→LOW_QUALITY_FILTER（低质过滤） */
export async function createExperiment(payload: {
  name: string;
  description?: string;
  feedType: "SPOT_FEED" | "POST_FEED";
  strategy: string;
  trafficPercent: number;
  controlPercent: number;
}): Promise<void> {
  await api.post("/admin/experiments", payload);
}

/** 关闭实验（管理端）：CLOSED 后推荐流回到基线 */
export async function closeExperiment(name: string): Promise<void> {
  await api.post(`/admin/experiments/${encodeURIComponent(name)}/close`);
}

/** 单条推荐流的监控读数 */
export interface FeedMetricsItem {
  exposures: number;
  users: number;
  hits: number;
  hit_rate: number;
  avg_score: number;
  by_variant: Record<string, number>;
  by_quality: Record<string, number>;
  feedbacks: Record<string, number>;
  save_rate: number;
  dislike_rate: number;
}

/** 推荐流监控报告（管理端）：近 N 天两条流的曝光/命中/质量构成/A-B 对照/反馈漏斗 */
export async function getFeedMonitor(days = 7): Promise<{
  days: number;
  feeds: Record<"SPOT_FEED" | "POST_FEED", FeedMetricsItem>;
}> {
  const response = await api.get<{ success: boolean; days: number; feeds: Record<"SPOT_FEED" | "POST_FEED", FeedMetricsItem> }>(
    "/admin/feed-monitor",
    { params: { days } }
  );
  return {
    days: response.data.days || days,
    feeds:
      response.data.feeds ||
      ({ SPOT_FEED: emptyFeed(), POST_FEED: emptyFeed() } as Record<"SPOT_FEED" | "POST_FEED", FeedMetricsItem>),
  };
}

function emptyFeed(): FeedMetricsItem {
  return {
    exposures: 0,
    users: 0,
    hits: 0,
    hit_rate: 0,
    avg_score: 0,
    by_variant: {},
    by_quality: {},
    feedbacks: {},
    save_rate: 0,
    dislike_rate: 0,
  };
}

/* ---------- 阶段四：全链路审计日志（任务 10，管理端） ---------- */

/** 审计日志项 */
export interface AuditLogItem {
  id: number;
  actor_id?: string | null;
  actor_name?: string | null;
  category: string;
  action: string;
  target_type?: string | null;
  target_id?: string | null;
  detail?: string | null;
  created_at?: string | null;
}

/** 审计日志查询（管理端）：按操作者/分类/时间窗过滤，新→旧 */
export async function getAuditLogs(params: {
  actor?: string;
  category?: string;
  days?: number;
  page?: number;
  pageSize?: number;
}): Promise<{ items: AuditLogItem[]; total: number; page: number }> {
  const response = await api.get<{ success: boolean; items: AuditLogItem[]; total: number; page: number }>(
    "/admin/audit-logs",
    { params }
  );
  return {
    items: response.data.items || [],
    total: response.data.total || 0,
    page: response.data.page || 1,
  };
}


export default api;