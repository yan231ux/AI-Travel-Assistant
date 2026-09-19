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
  PendingRevision,
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
  TrendingSpotFeed,
  User,
  UserHome,
  WeatherForecastResponse,
  FollowUserVO,
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

/**
 * 首页「大家最近在规划」热门景点（设计方案 §6.2 / §6.4）。
 *
 * 定位提醒：这是**社会热度**，不是"适合你"。文档 §6.1 明确要求首页把"热门规划"
 * 与"个性化推荐"分开标注，所以返回值里没有任何匹配度/画像字段，UI 也不能把它
 * 包装成"为你推荐"。后端直出 { items, window_days, generated_at, degraded, errors }，
 * 没有 success/data 包裹；degraded=true 时前端应显式提示，而不是显示成"暂无热门"。
 *
 * @param city  可选城市过滤；不传 = 跨城市（各城市内部归一化后比较，避免大城市霸榜）
 * @param days  统计窗口，默认 7 天（后端限制 1~30）
 * @param limit 返回条数，默认 8（后端限制 1~20）
 */
export async function getHomeTrendingSpots(
  city?: string | null,
  days = 7,
  limit = 8
): Promise<TrendingSpotFeed> {
  const response = await api.get<TrendingSpotFeed>("/home/trending-spots", {
    params: { city: city || undefined, days, limit },
  });
  return response.data;
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

/** 关注列表（查看某用户关注了谁；following 恒为 true） */
export async function getFollowing(userId: string, limit = 50): Promise<FollowUserVO[]> {
  const response = await api.get<{ success: boolean; items: FollowUserVO[] }>(
    `/users/${encodeURIComponent(userId)}/following`,
    { params: { limit } }
  );
  return response.data.items ?? [];
}

/** 粉丝列表（查看某用户的粉丝；following 表示我是否也关注了对方，用于互关标识） */
export async function getFollowers(userId: string, limit = 50): Promise<FollowUserVO[]> {
  const response = await api.get<{ success: boolean; items: FollowUserVO[] }>(
    `/users/${encodeURIComponent(userId)}/followers`,
    { params: { limit } }
  );
  return response.data.items ?? [];
}

/* ---------- 认证 API ---------- */

/** 当前用户最新信息（阶段二：登录/启动时同步 role；旧 localStorage 缺 role 时用） */
export interface MeResponse {
  success: boolean;
  user?: {
    id: string;
    role?: string | null;
    /** 角色中文名（如"内容审核员"） */
    role_label?: string | null;
    /** 权限点集合（前端据过滤管理端菜单/路由） */
    permissions?: string[] | null;
  } | null;
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

/** 管理员登录（独立入口）：非管理端账号会被后端 403 拦截 */
export async function adminLogin(payload: LoginRequest): Promise<AuthResponse> {
  const response = await api.post<AuthResponse>("/auth/admin-login", payload);
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

/** 作者查看自己某条帖子的最新 AI 审核细分状态（PENDING/RUNNING/PASSED/REVIEW/FAILED）。无任务返回 null。 */
export interface MyPostModerationStatus {
  task_id: number;
  status: "PENDING" | "RUNNING" | "PASSED" | "REVIEW" | "FAILED";
  risk_level?: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL" | null;
  rule_hit_count?: number | null;
  decision?: "APPROVE" | "REJECT" | null;
  decision_by?: string | null;
  decision_reason?: string | null;
  error_message?: string | null;
  created_at?: string | null;
  finished_at?: string | null;
}
export async function getMyPostModerationStatus(
  postId: number
): Promise<MyPostModerationStatus | null> {
  try {
    const response = await api.get<{ success: boolean; data: MyPostModerationStatus }>(
      `/community/posts/${postId}/moderation-status`
    );
    return response.data?.data || null;
  } catch (err: unknown) {
    // 204 No Content / 404（无任务或帖子已删）→ 视为"暂无状态"
    const status = (err as { response?: { status?: number } })?.response?.status;
    if (status === 204 || status === 404) return null;
    throw err;
  }
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

export async function handleReport(
  reportId: number,
  action: "RESOLVE" | "DISMISS",
  note?: string
): Promise<void> {
  await api.post(`/community/moderation/reports/${reportId}/handle`, { action, note });
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
  /** 各城市推荐量（曝光行按城市聚合；帖子流无城市维度，恒为空表） */
  by_city: Record<string, number>;
  feedbacks: Record<string, number>;
  save_rate: number;
  dislike_rate: number;
}

/** 推荐流监控报告（管理端）：近 N 天两条流的曝光/命中/质量构成/A-B 对照/反馈漏斗 */
export async function getFeedMonitor(days = 7): Promise<{
  days: number;
  feeds: Record<"SPOT_FEED" | "POST_FEED", FeedMetricsItem>;
  /** 数据源读取失败时为 true，读数不可信 */
  degraded: boolean;
  errors: string[];
}> {
  const response = await api.get<{
    success: boolean;
    days: number;
    feeds: Record<"SPOT_FEED" | "POST_FEED", FeedMetricsItem>;
    degraded?: boolean;
    errors?: string[];
  }>("/admin/feed-monitor", { params: { days } });
  const degraded = response.data.degraded === true;
  return {
    days: response.data.days || days,
    feeds:
      response.data.feeds ||
      ({ SPOT_FEED: emptyFeed(), POST_FEED: emptyFeed() } as Record<"SPOT_FEED" | "POST_FEED", FeedMetricsItem>),
    degraded: degraded || !response.data.feeds,
    errors: response.data.errors ?? (response.data.feeds ? [] : ["feeds"]),
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
    by_city: {},
    feedbacks: {},
    save_rate: 0,
    dislike_rate: 0,
  };
}

/* ---------- 城市推荐质量（设计方案 §6「城市推荐质量」，管理端） ---------- */

/** 单城市推荐质量读数（GET /admin/recommendations/city-quality 元素） */
export interface CityQualityItem {
  city: string;
  /** 可推荐景点数（在线且未被治理标记，与推荐池同口径） */
  recommendable_spots: number;
  /** 有攻略景点数（可信度 GUIDE_MATCHED / VERIFIED） */
  guide_backed_spots: number;
  /** 无攻略景点数（= 可推荐 − 有攻略，服务端保证两数自洽） */
  poi_only_spots: number;
  /** 平均质量分 = 可信度加权（VERIFIED 100 / GUIDE_MATCHED 70 / POI_ONLY 40），不是攻略内容分 */
  avg_quality_score: number;
  /** 窗口内曝光行数（反馈率的分母） */
  exposures: number;
  /** 窗口内被曝光过的去重用户数 */
  users: number;
  save_count: number;
  dislike_count: number;
  /** 收藏率 % = 先曝光后收藏的去重计数 ÷ 曝光行数 */
  save_rate: number;
  dislike_rate: number;
  last_synced_at?: string | null;
  last_rag_updated_at?: string | null;
}

export interface CityQualityResponse {
  items: CityQualityItem[];
  window_days: number;
  generated_at: string;
  /** 任一数据源读取失败 → true（此时"空城市"不代表真的没数据） */
  degraded: boolean;
  errors: string[];
  /** 后端写明的指标口径 + 未采集项说明，页面需原样透出（不留静默缺口） */
  notes: string[];
}

/**
 * 城市推荐质量（管理端）：按城市拆开看"有没有货 / 货好不好 / 用户买不买账"。
 * 与 /admin/feed-monitor 的分工：那边是全站两条流的读数，这边是城市维度切片。
 */
export async function getRecommendationCityQuality(
  days = 30,
  limit = 50
): Promise<CityQualityResponse> {
  const response = await api.get<CityQualityResponse & { success?: boolean }>(
    "/admin/recommendations/city-quality",
    { params: { days, limit } }
  );
  const d = response.data;
  return {
    items: d.items ?? [],
    window_days: d.window_days ?? days,
    generated_at: d.generated_at ?? "",
    // 拿不到 items 字段本身就说明响应异常 → 一并降级，不让页面显示成"没有城市数据"
    degraded: d.degraded === true || !d.items,
    errors: d.errors ?? (d.items ? [] : ["items"]),
    notes: d.notes ?? [],
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

/* =====================================================================
 * 管理后台（管理员后台与内容运营中心设计方案：独立 /admin 空间 + 内容运营骨架）
 * 服务端全部 requireAdmin 二次校验；前端路由守卫只做体验层。
 * ===================================================================== */

/** 后台作者信息（治理上下文） */
export interface AdminAuthorInfo {
  id: string;
  nickname: string;
  role?: string | null;
  registered_at?: string | null;
  published_posts?: number;
  resolved_reports?: number;
}

/** 针对某对象的举报摘要 */
export interface AdminReportBrief {
  id: number;
  reason: string;
  detail?: string | null;
  status?: string | null;
  reporter_name?: string | null;
  created_at?: string | null;
}

/** 操作审计条目 */
export interface AdminAuditEntry {
  action: string;
  category?: string | null;
  actor?: string | null;
  target_type?: string | null;
  target_id?: string | null;
  detail?: string | null;
  created_at?: string | null;
}

/** 帖子审核证据详情（管理员专用，替代普通用户 PostDetail） */
export interface ReviewPostDetail {
  id: number;
  title: string;
  summary?: string | null;
  content: string;
  cover_image?: string | null;
  city?: string | null;
  travel_days?: number | null;
  budget?: number | null;
  pace?: string | null;
  post_type: string;
  status: string;
  quality_score?: number;
  low_quality?: boolean;
  reject_reason?: string | null;
  like_count: number;
  favorite_count: number;
  comment_count: number;
  view_count: number;
  published_at?: string | null;
  created_at?: string | null;
  updated_at?: string | null;
  author: AdminAuthorInfo;
  reports: AdminReportBrief[];
  audit: AdminAuditEntry[];
  /* ---------- P1-1 版本化：审核队列标记 + 待审修改版本快照 ---------- */
  /** 是否存在待审修改版本（已发布帖被编辑 → 审核页需展示"将切换成什么"） */
  has_pending_revision?: boolean;
  /** 待审修改版本内容快照（无待审版本为 null） */
  pending_revision?: PendingRevision | null;
}

/** 被举报帖子完整上下文 */
export interface ReportTargetPost {
  id: number;
  title: string;
  summary?: string | null;
  content: string;
  cover_image?: string | null;
  city?: string | null;
  post_type?: string | null;
  status: string;
  quality_score?: number;
  low_quality?: boolean;
  reject_reason?: string | null;
  like_count?: number;
  favorite_count?: number;
  comment_count?: number;
  view_count?: number;
  published_at?: string | null;
  created_at?: string | null;
  author?: AdminAuthorInfo | null;
  reports?: AdminReportBrief[];
}

/** 举报证据详情（P0-3：快照 → 完整上下文） */
export interface ReportEvidence {
  report: {
    id: number;
    target_type: "POST" | "COMMENT";
    target_id: number;
    reason: string;
    detail?: string | null;
    status: string;
    reporter_id: string;
    reporter_name: string;
    handled_by?: string | null;
    handled_at?: string | null;
    handle_note?: string | null;
    created_at?: string | null;
  };
  target_post?: ReportTargetPost | null;
  target_comment?: {
    id: number;
    post_id: number;
    parent_id?: number | null;
    content: string;
    status: string;
    author?: AdminAuthorInfo | null;
    created_at?: string | null;
  } | null;
  audit: AdminAuditEntry[];
}

/** 攻略列表/详情项（内容运营） */
export interface GuideItem {
  id: number;
  city: string;
  title: string;
  summary?: string | null;
  cover_image?: string | null;
  source_type?: string | null;
  source_name?: string | null;
  source_file?: string | null;
  author?: string | null;
  status: string;
  quality_score: number;
  reject_reason?: string | null;
  version: number;
  rag_status: string;
  rag_indexed_revision?: number | null;
  published_revision_id?: number | null;
  current_revision_id?: number | null;
  published_at?: string | null;
  created_at?: string | null;
  updated_at?: string | null;
  spot_count?: number;
  spot_matched?: number;
}

export interface GuideSpotRef {
  name: string;
  matched?: boolean;
  spot_id?: string | null;
  poi_id?: string | null;
}

export interface GuideTagRef {
  category: string;
  tag: string;
}

export interface GuideRevisionItem {
  id: number;
  revision_no: number;
  hash?: string | null;
  status?: string | null;
  change_summary?: string | null;
  editor_id?: string | null;
  created_at?: string | null;
}

export interface RagTaskItem {
  id: number;
  revision_id?: number | null;
  status: string;
  error_message?: string | null;
  triggered_by?: string | null;
  created_at?: string | null;
  finished_at?: string | null;
}

export interface GuideDetail extends GuideItem {
  content_markdown: string;
  current_hash?: string | null;
  published_revision_no?: number | null;
  spots: GuideSpotRef[];
  tags: GuideTagRef[];
  revisions: GuideRevisionItem[];
  rag_tasks: RagTaskItem[];
  spot_total?: number;
  spot_matched?: number;
  spot_unmatched?: number;
}

export interface GuidePayload {
  city: string;
  title: string;
  summary?: string;
  coverImage?: string;
  sourceType?: string;
  sourceName?: string;
  content: string;
  changeSummary?: string;
}

/** 管理后台看板（设计方案 §3.1） */
export interface AdminDashboardSummary {
  generated_at: string;
  /** 统计项失败时为 null（不可用），绝不伪装成 0；前端据此显示"—/数据暂时不可用" */
  todo: {
    pending_posts: number | null;
    pending_reports: number | null;
    pending_guides: number | null;
    low_quality_pending: number | null;
  };
  content: {
    posts_total: number | null;
    published_posts: number | null;
    hidden_posts: number | null;
    published_today: number | null;
    reports_7d: number | null;
    guides_total: number | null;
    guides_published: number | null;
    cities_with_guide: number | null;
    users_total: number | null;
    report_resolved: number | null;
    report_dismissed: number | null;
    report_resolve_rate?: number | null;
  };
  recent_ops: AdminAuditEntry[];
  /** 是否可见"最近操作"（审计内容，需 AUDIT_VIEW；false 时 recent_ops 恒为空） */
  recent_ops_visible?: boolean;
  /** 是否有统计项查询失败 */
  degraded?: boolean;
  /** 失败项清单（key 定位到具体统计项） */
  errors?: { key: string; error: string }[];
}

/** 管理后台看板汇总 */
export async function getAdminSummary(): Promise<AdminDashboardSummary> {
  const response = await api.get<{ success: boolean; data: AdminDashboardSummary }>(
    "/admin/dashboard/summary"
  );
  return response.data.data;
}

/** 管理端帖子审核/治理队列（status: PENDING_REVIEW/PUBLISHED/HIDDEN） */
export async function getReviewPosts(
  status: "PENDING_REVIEW" | "PUBLISHED" | "HIDDEN" = "PENDING_REVIEW",
  page = 1,
  pageSize = 20
): Promise<PostPage> {
  const response = await api.get<{ success: boolean; items: PostItem[]; total: number; page: number }>(
    "/admin/review/posts",
    { params: { status, page, pageSize } }
  );
  return { items: response.data.items || [], total: response.data.total || 0, page: response.data.page || 1 };
}

/** 帖子审核证据详情（审核页不跳普通用户 PostDetail） */
export async function getPostReviewDetail(postId: number): Promise<ReviewPostDetail> {
  const response = await api.get<{ success: boolean; data: ReviewPostDetail }>(
    `/admin/review/posts/${postId}`
  );
  return response.data.data;
}

/** 举报证据详情（完整被举报内容 + 上下文） */
export async function getReportEvidence(reportId: number): Promise<ReportEvidence> {
  const response = await api.get<{ success: boolean; data: ReportEvidence }>(
    `/admin/reports/${reportId}`
  );
  return response.data.data;
}

/** 攻略列表（内容运营） */
export async function getGuides(params: {
  city?: string;
  status?: string;
  keyword?: string;
  page?: number;
  pageSize?: number;
}): Promise<{ items: GuideItem[]; total: number; page: number }> {
  const response = await api.get<{ success: boolean; data: { items: GuideItem[]; total: number; page: number } }>(
    "/admin/guides",
    { params }
  );
  return response.data.data;
}

/** 攻略详情 */
export async function getGuideDetail(id: number): Promise<GuideDetail> {
  const response = await api.get<{ success: boolean; data: GuideDetail }>(`/admin/guides/${id}`);
  return response.data.data;
}

/** 新建攻略草稿 */
export async function createGuide(payload: GuidePayload): Promise<{ id: number; message?: string }> {
  const response = await api.post<{ success: boolean; id: number; message?: string }>("/admin/guides", payload);
  return response.data;
}

/** 保存攻略编辑（unchanged=true 表示无变化未产生新版本） */
export async function updateGuide(
  id: number,
  payload: GuidePayload
): Promise<{ unchanged: boolean; version: number; message?: string }> {
  const response = await api.put<{ success: boolean; unchanged: boolean; version: number; message?: string }>(
    `/admin/guides/${id}`,
    payload
  );
  return response.data;
}

export async function submitGuide(id: number): Promise<void> {
  await api.post(`/admin/guides/${id}/submit`);
}

export async function publishGuide(id: number): Promise<void> {
  await api.post(`/admin/guides/${id}/publish`);
}

export async function rejectGuide(id: number, reason: string): Promise<void> {
  await api.post(`/admin/guides/${id}/reject`, { reason });
}

export async function hideGuide(id: number): Promise<void> {
  await api.post(`/admin/guides/${id}/hide`);
}

export async function restoreGuide(id: number): Promise<void> {
  await api.post(`/admin/guides/${id}/restore`);
}

/** 归档（PUBLISHED/HIDDEN → ARCHIVED：停止公开消费并从 RAG 检索源移除） */
export async function archiveGuide(id: number): Promise<void> {
  await api.post(`/admin/guides/${id}/archive`);
}

/** 取消归档（ARCHIVED → DRAFT） */
export async function unarchiveGuide(id: number): Promise<void> {
  await api.post(`/admin/guides/${id}/unarchive`);
}

/** 复制为新版本（fork 一条全新草稿） */
export async function copyGuide(id: number): Promise<{ id: number; message?: string }> {
  const response = await api.post<{ success: boolean; id: number; message?: string }>(`/admin/guides/${id}/copy`);
  return response.data;
}

/** 回滚上一已发布版本（撤销待审编辑 / 错误发布） */
export async function rollbackGuide(id: number): Promise<{ message?: string }> {
  const response = await api.post<{ success: boolean; message?: string }>(`/admin/guides/${id}/rollback`);
  return response.data;
}

/** 手动重建/重试 RAG 索引（同步执行，返回任务结果） */
export async function reindexGuide(id: number): Promise<{
  message?: string;
  data?: { task_id?: number; revision_id?: number; status?: string; error_message?: string | null };
}> {
  const response = await api.post<{
    success: boolean;
    message?: string;
    data?: { task_id?: number; revision_id?: number; status?: string; error_message?: string | null };
  }>(`/admin/guides/${id}/reindex`);
  return response.data;
}

/** 静态 Markdown 幂等导入（source_file + content_hash 判重；文件变更入待审） */
export async function importGuides(): Promise<{
  files: string[];
  imported: number;
  changed_pending: number;
  unchanged: number;
}> {
  const response = await api.post<{ success: boolean; data: { files: string[]; imported: number; changed_pending: number; unchanged: number } }>(
    "/admin/guides/import"
  );
  return response.data.data;
}


/* ==================== 景点数据治理（设计方案 §5，/admin/spots） ==================== */

export interface AdminSpotItem {
  id: number;
  spot_id: string;
  poi_id?: string | null;
  name: string;
  city: string;
  address?: string | null;
  longitude?: number | null;
  latitude?: number | null;
  category?: string | null;
  image_url?: string | null;
  description?: string | null;
  tags?: string | null;
  source?: string | null;
  data_quality?: string | null;
  /** ONLINE / OFFLINE */
  status?: string | null;
  /** NON_SPOT / CLOSED / OUTDATED / ERROR_POI / null(正常) */
  flag?: string | null;
  flag_reason?: string | null;
  manual_override?: boolean;
  /** 人工锁定字段（逗号分隔：name,address,description,tags…） */
  manual_override_fields?: string | null;
  last_verified_by?: string | null;
  last_verified_at?: string | null;
  merged_into?: string | null;
  last_synced_at?: string | null;
  created_at?: string | null;
  updated_at?: string | null;
}

export interface AdminSpotSummary {
  total: number;
  online: number;
  offline: number;
  flagged: number;
  merged: number;
}

export interface AdminSpotPage {
  items: AdminSpotItem[];
  total: number;
  page: number;
  pageSize: number;
  summary: AdminSpotSummary;
}

export interface AdminSpotDetail extends AdminSpotItem {
  favoriteCount: number;
  aliasCount: number;
  guideLinkCount: number;
  postLinkCount: number;
}

/** 景点治理列表（城市/上下架/治理标记/关键词过滤） */
export async function listAdminSpots(params: {
  city?: string;
  status?: string;
  flag?: string;
  keyword?: string;
  page?: number;
  pageSize?: number;
}): Promise<AdminSpotPage> {
  const response = await api.get<{ success: boolean; data: AdminSpotPage }>("/admin/spots", { params });
  return response.data.data;
}

/** 治理详情（完整字段 + 收藏/别名/攻略/帖子关联计数） */
export async function getAdminSpot(id: number): Promise<AdminSpotDetail> {
  const response = await api.get<{ success: boolean; data: AdminSpotDetail }>(`/admin/spots/${id}`);
  return response.data.data;
}

/** 编辑字段（白名单人工修正，修改即锁定；unlockFields 显式解锁） */
export async function updateAdminSpot(
  id: number,
  payload: {
    name?: string;
    address?: string;
    category?: string;
    description?: string;
    tags?: string;
    imageUrl?: string;
    longitude?: number;
    latitude?: number;
    unlockFields?: string[];
    reason?: string;
  }
): Promise<{ message?: string }> {
  const response = await api.put<{ success: boolean; message?: string }>(`/admin/spots/${id}`, payload);
  return response.data;
}

/** 设置/清除治理标记（flag 空/null = 清除；NON_SPOT/CLOSED/ERROR_POI 自动下线） */
export async function flagAdminSpot(id: number, flag?: string, reason?: string): Promise<{ message?: string }> {
  const response = await api.post<{ success: boolean; message?: string }>(`/admin/spots/${id}/flag`, {
    flag: flag ?? "",
    reason: reason ?? "",
  });
  return response.data;
}

/** 下线（人工） */
export async function offlineAdminSpot(id: number, reason?: string): Promise<{ message?: string }> {
  const response = await api.post<{ success: boolean; message?: string }>(`/admin/spots/${id}/offline`, {
    reason: reason ?? "",
  });
  return response.data;
}

/** 重新上线（存在未清除治理标记时服务端拒绝） */
export async function onlineAdminSpot(id: number): Promise<{ message?: string }> {
  const response = await api.post<{ success: boolean; message?: string }>(`/admin/spots/${id}/online`);
  return response.data;
}

/** 合并重复景点到目标 */
export async function mergeAdminSpot(
  id: number,
  targetSpotId: string,
  reason?: string
): Promise<{ message?: string }> {
  const response = await api.post<{ success: boolean; message?: string }>(`/admin/spots/${id}/merge`, {
    targetSpotId,
    reason: reason ?? "",
  });
  return response.data;
}

/** 手动单点重同步（强刷高德） */
export async function resyncAdminSpot(id: number): Promise<{ message?: string }> {
  const response = await api.post<{ success: boolean; message?: string }>(`/admin/spots/${id}/resync`);
  return response.data;
}

/** 重新匹配 RAG 攻略卡片 */
export async function rematchAdminSpotGuide(id: number): Promise<{ message?: string }> {
  const response = await api.post<{ success: boolean; message?: string }>(`/admin/spots/${id}/rematch-guide`);
  return response.data;
}

/* ================= 用户与账号治理（设计方案 §7 /admin/users） ================= */

/** 用户治理列表项 */
export interface AdminUserGovernItem {
  id: string;
  username: string;
  nickname?: string | null;
  /** 角色码：USER / CONTENT_REVIEWER / CITY_EDITOR / RECOMMENDATION_OPERATOR / SUPER_ADMIN */
  role: string;
  /** 角色中文名（服务端下发，用于列表展示"他管什么"） */
  role_label?: string | null;
  /** ACTIVE / SUSPENDED */
  account_status: string;
  post_limited: boolean;
  comment_banned: boolean;
  violation_count: number;
  post_count: number;
  last_login_at?: string | null;
  created_at?: string | null;
}

export interface AdminUserPage {
  items: AdminUserGovernItem[];
  total: number;
  page: number;
  pageSize: number;
}

/** 用户治理列表（keyword=用户名/昵称；status=ALL/ACTIVE/SUSPENDED；from/to=注册时间段） */
export async function listAdminUsers(params: {
  keyword?: string;
  status?: string;
  from?: string;
  to?: string;
  page?: number;
  pageSize?: number;
}): Promise<AdminUserPage> {
  const response = await api.get<{ success: boolean; data: AdminUserPage }>("/admin/users", { params });
  return response.data.data;
}

/**
 * 治理动作：POST_LIMIT / UNPOST_LIMIT / COMMENT_BAN / UNCOMMENT_BAN / SUSPEND / RESTORE。
 * 限制即时生效于真实链路：发帖/评论被拒、暂停账号登录被拒。
 */
export async function governAdminUser(
  id: string,
  action: string,
  reason: string
): Promise<{ message?: string; data?: { account_status?: string } }> {
  const response = await api.post<{ success: boolean; message?: string; data?: { account_status?: string } }>(
    `/admin/users/${id}/govern`,
    { action, reason }
  );
  return response.data;
}

/**
 * 分配管理端角色（高风险，§11 第五阶段）：role + reason + confirm=true 三者缺一不可。
 * 服务端还会拒绝：改自己的角色、把最后一名超管降级、非法/历史角色码。
 */
export async function assignAdminUserRole(
  id: string,
  role: string,
  reason: string,
  confirm: boolean
): Promise<{ message?: string; data?: { role?: string; role_label?: string } }> {
  const response = await api.post<{
    success: boolean;
    message?: string;
    data?: { role?: string; role_label?: string };
  }>(`/admin/users/${id}/role`, { role, reason, confirm });
  return response.data;
}

/* ================= 推荐人工干预（设计方案 §6.3 /admin/recommendations/interventions） ================= */

/** 干预动作：SPOT 置顶/降权/黑名单；CITY 城市精选 */
export type AdminInterventionAction = "PIN" | "DEMOTE" | "BLACKLIST" | "FEATURED";

/** 推荐人工干预列表项 */
export interface AdminInterventionItem {
  id: string;
  target_type: "SPOT" | "CITY";
  target_id: string;
  action: AdminInterventionAction;
  reason?: string | null;
  effective_from?: string | null;
  effective_until?: string | null;
  created_by?: string | null;
  created_at?: string | null;
  /** ACTIVE / SCHEDULED / EXPIRED */
  status: "ACTIVE" | "SCHEDULED" | "EXPIRED";
  spot_name?: string | null;
  spot_city?: string | null;
}

export interface AdminInterventionPage {
  items: AdminInterventionItem[];
  total: number;
  page: number;
  pageSize: number;
}

/** 干预列表（scope=ALL/ACTIVE/SCHEDULED/EXPIRED） */
export async function listAdminInterventions(params: {
  action?: string;
  targetType?: string;
  scope?: string;
  page?: number;
  pageSize?: number;
}): Promise<AdminInterventionPage> {
  const response = await api.get<{ success: boolean; data: AdminInterventionPage }>(
    "/admin/recommendations/interventions",
    { params }
  );
  return response.data.data;
}

/** 新建/覆盖保存干预（同对象同动作 = 覆盖窗口与原因） */
export async function saveAdminIntervention(payload: {
  target_type: "SPOT" | "CITY";
  target_id: string;
  action: AdminInterventionAction;
  reason: string;
  effective_from?: string;
  effective_until?: string;
}): Promise<{ message?: string }> {
  const response = await api.post<{ success: boolean; message?: string }>(
    "/admin/recommendations/interventions",
    payload
  );
  return response.data;
}

/** 删除干预（即时恢复算法排序） */
export async function removeAdminIntervention(id: string): Promise<void> {
  await api.delete(`/admin/recommendations/interventions/${id}`);
}

/* ================= AI 内容审核（阶段三 §4.7 /admin/content/ai-review） ================= */

/** 审核/风险字段采用 snake_case（与后端 JSON 直传） */
export interface ModerationTask {
  id: number;
  target_type: "POST" | "COMMENT" | "GUIDE" | "SPOT";
  target_id: string;
  revision_id?: number | null;
  content_hash: string;
  task_type: string;
  status: "PENDING" | "RUNNING" | "PASSED" | "REVIEW" | "FAILED";
  risk_level?: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL" | null;
  risk_score?: number | null;
  model_name?: string | null;
  prompt_version?: string | null;
  result_json?: string | null;
  matched_rules_json?: string | null;
  rule_hit_count: number;
  content_title?: string | null;
  content_text?: string | null;
  city?: string | null;
  error_message?: string | null;
  retry_count: number;
  decision?: "APPROVE" | "REJECT" | null;
  decision_by?: string | null;
  decision_reason?: string | null;
  decided_at?: string | null;
  created_by?: string | null;
  created_at?: string | null;
  finished_at?: string | null;
}

export interface ModerationPage {
  items: ModerationTask[];
  total: number;
  page: number;
}

/** AI 审核队列分页（status/targetType 可选过滤） */
export async function listModerationTasks(params: {
  status?: string;
  targetType?: string;
  page?: number;
  pageSize?: number;
}): Promise<ModerationPage> {
  const response = await api.get<{ success: boolean; items: ModerationTask[]; total: number; page: number }>(
    "/admin/content/moderation",
    { params }
  );
  const { items, total, page } = response.data;
  return { items: items || [], total: total || 0, page: page || 1 };
}

/** 任务详情（原文快照 + 规则命中 + AI 结构化输出） */
export async function getModerationTask(id: number): Promise<ModerationTask> {
  const response = await api.get<{ success: boolean; data: ModerationTask }>(
    `/admin/content/moderation/${id}`
  );
  return response.data.data;
}

/** 人工决策（覆盖 AI 结论；REJECT 必须填原因） */
export async function decideModerationTask(
  id: number,
  action: "APPROVE" | "REJECT",
  reason?: string
): Promise<{ message?: string }> {
  const response = await api.post<{ success: boolean; message?: string }>(
    `/admin/content/moderation/${id}/decide`,
    { action, reason }
  );
  return response.data;
}

/** 失败/待审任务重试（重跑规则+AI 流水线） */
export async function retryModerationTask(id: number): Promise<{ message?: string }> {
  const response = await api.post<{ success: boolean; message?: string }>(
    `/admin/content/moderation/${id}/retry`
  );
  return response.data;
}

/* ================= 数据看板（阶段四 §5.3 六张图 /admin/analytics/*） ================= */

/** 聚合响应壳：degraded = 任一部分查询失败（显式降级，不伪装成 0） */
export interface AnalyticsResp<T> {
  data: T | null;
  degraded: boolean;
  errors: string[];
}

export interface SpotAdoptRow {
  item_id: string;
  item_name: string;
  city: string;
  adopt_count: number;
  adopt_users: number;
}

export interface CityHeatRow {
  city: string;
  trip_generated: number;
  trip_saved: number;
  spot_adopt: number;
  active_users: number;
}

export interface ModerationFunnel {
  total: number;
  auto_passed: number;
  review: number;
  ai_failed: number;
  human_approved: number;
  human_rejected: number;
  rule_hits: number;
}

export interface RiskBreakdownRow {
  risk_level: string;
  cnt: number;
}

export interface TrendRow {
  stat_date: string;
  trips: number;
  saves: number;
  adopts: number;
  favorites: number;
}

export interface RagStatus {
  guides: { rag_status: string; cnt: number }[];
  tasks: { status: string; cnt: number }[];
}

async function analyticsGet<T>(path: string, params?: Record<string, unknown>): Promise<AnalyticsResp<T>> {
  const response = await api.get<AnalyticsResp<T>>(path, { params });
  return response.data;
}

/** 图表一：景点规划采用排行（days 窗口 + city 筛选） */
export function getSpotAdoption(days: number, city?: string) {
  return analyticsGet<SpotAdoptRow[]>("/admin/analytics/spot-adopt", { days, city });
}

/** 图表二：城市热度排行 */
export function getCityHeat(days: number) {
  return analyticsGet<CityHeatRow[]>("/admin/analytics/city-heat", { days });
}

/** 图表三：内容审核漏斗 */
export function getModerationFunnel(days: number) {
  return analyticsGet<ModerationFunnel>("/admin/analytics/moderation-funnel", { days });
}

/** 图表四：内容风险构成 */
export function getRiskBreakdown(days: number) {
  return analyticsGet<RiskBreakdownRow[]>("/admin/analytics/risk-breakdown", { days });
}

/** 图表五：推荐/规划趋势 */
export function getTrend(days: number) {
  return analyticsGet<TrendRow[]>("/admin/analytics/trend", { days });
}

/** 图表六：RAG 索引状态 */
export function getRagStatus() {
  return analyticsGet<RagStatus>("/admin/analytics/rag-status");
}

export default api;