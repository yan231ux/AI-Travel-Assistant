import axios from "axios";

import type {
  AgentTraceResponse,
  AgentTraceStep,
  AuthResponse,
  Itinerary,
  LoginRequest,
  RegisterRequest,
  TripDetailResponse,
  TripListResponse,
  TripRequestPayload,
  TripSaveResponse,
  User,
  WeatherForecastResponse,
} from "../types";

export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

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

/* ---------- 认证 API ---------- */

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

export default api;
