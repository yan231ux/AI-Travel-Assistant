import type { PostStatus, PostType } from "../types";

/** 帖子类型展示元信息（社区阶段二） */
export const POST_TYPE_META: Record<PostType, { label: string; icon: string }> = {
  GUIDE: { label: "城市攻略", icon: "🗺️" },
  SPOT_RECOMMENDATION: { label: "景点推荐", icon: "📍" },
  ITINERARY: { label: "行程分享", icon: "🧳" },
  NOTE: { label: "旅行随笔", icon: "✍️" },
};

/** 帖子状态展示元信息 */
export const POST_STATUS_META: Record<PostStatus, { label: string; cls: string }> = {
  DRAFT: { label: "草稿", cls: "st--draft" },
  PENDING_REVIEW: { label: "审核中", cls: "st--pending" },
  PUBLISHED: { label: "已发布", cls: "st--published" },
  REJECTED: { label: "未通过", cls: "st--rejected" },
  HIDDEN: { label: "已隐藏", cls: "st--hidden" },
};

export function postTypeLabel(type?: string | null): string {
  if (type && type in POST_TYPE_META) {
    return POST_TYPE_META[type as PostType].label;
  }
  return "旅行笔记";
}

export function postStatusLabel(status?: string | null): string {
  if (status && status in POST_STATUS_META) {
    return POST_STATUS_META[status as PostStatus].label;
  }
  return "未知";
}

/** 封面占位色板（无封面时按帖子 id 取色，稳定不闪） */
const COVER_COLORS = ["#dbe7f5", "#f5e9db", "#e5f0e0", "#f0e0ea", "#e7e0f0", "#dff0f2"];

export function coverColor(postId: number): string {
  return COVER_COLORS[Math.abs(postId) % COVER_COLORS.length];
}
