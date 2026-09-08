import type { RecommendationItem } from "../types";

/**
 * 卡片可见的"与你的偏好匹配度"百分比（纯函数，供单测/复用，排查报告 P0-1 §五 P2-4）。
 *
 * 前端不得把后端综合排序分 score（含 0.5 基础分/已去过惩罚）包装成匹配度展示。
 * 只有以下条件**同时**满足才展示百分比：
 *   1. 本条真实命中个性化（item.personalized === true —— 后端仅在"为你推荐"流内命中偏好时置位；
 *      无画像/未命中/攻略优先/最近更新/相关推荐/城市精选 均为 false/null）；
 *   2. 后端给了真实偏好匹配分 match_score（0~1，= Σ权重×置信度 的 clamp01，不含基础分）；
 *   3. 命中了至少一个偏好标签 matched_preferences（非空，避免"有理由但无命中"的假匹配）。
 *
 * @returns 0~100 的整数百分比；任一条件不满足返回 null（前端隐藏徽标）
 */
export function visibleMatchPercent(item: RecommendationItem): number | null {
  if (!item.personalized) return null;
  if (typeof item.match_score !== "number") return null;
  if (!Array.isArray(item.matched_preferences) || item.matched_preferences.length === 0) return null;
  const clamped = Math.min(1, Math.max(0, item.match_score));
  return Math.round(clamped * 100);
}
