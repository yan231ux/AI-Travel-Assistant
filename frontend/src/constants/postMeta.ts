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

/**
 * 该状态的内容是否开放"公开互动"（点赞/收藏/不感兴趣/评论/举报）。
 *
 * <p>唯一判定口径：**只有已发布**才能被互动。草稿/审核中/未通过/已隐藏一律关闭——
 * 对未公开内容点赞或评论本身就是不该发生的写入（后端也会拒），前端必须同源收窄；
 * 卡片与详情页共用本判定，禁止各处自行比较 status 字符串。
 */
export function isInteractiveStatus(status?: string | null): boolean {
  return status === "PUBLISHED";
}

/** 封面占位色板（无封面时按帖子 id 取色，稳定不闪） */
const COVER_COLORS = ["#dbe7f5", "#f5e9db", "#e5f0e0", "#f0e0ea", "#e7e0f0", "#dff0f2"];

export function coverColor(postId: number): string {
  return COVER_COLORS[Math.abs(postId) % COVER_COLORS.length];
}
