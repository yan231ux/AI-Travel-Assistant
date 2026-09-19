import { createRouter, createWebHistory } from "vue-router";

import AppLayout from "../layouts/AppLayout.vue";
import AdminLayout from "../layouts/AdminLayout.vue";
import { hasPermission, isAdmin, isLoggedIn, permissions, syncRole } from "../stores/session";
import { latestItinerary } from "../stores/trip";

/**
 * 路由表（产品化改造批次 C 起对齐 PRODUCT_EVOLUTION_PLAN §3.1/§14.1；
 * UI 升级方案 §9/§10：页面全部动态 import() 分包，首屏只载登录页与布局）。
 * - /login 公开；已登录访问自动跳首页（管理员跳 /admin）
 * - 普通用户端：/ /plan /agent /result /history /profile /recommendations…
 * - 管理后台：/admin/** 独立 AdminLayout（设计方案 §10），requiresAdmin 守卫 + 后端二次校验；
 *   /moderation 旧地址兼容重定向到 /admin/content/review
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("../views/Login.vue"),
      meta: { public: true },
    },
    {
      path: "/",
      component: AppLayout,
      children: [
        { path: "", name: "dashboard", component: () => import("../views/Home.vue") },
        { path: "plan", name: "plan", component: () => import("../views/PlannerView.vue") },
        { path: "agent", name: "agent", component: () => import("../views/AgentProcess.vue") },
        { path: "result", name: "result", component: () => import("../views/Result.vue") },
        { path: "history", name: "history", component: () => import("../views/History.vue") },
        { path: "profile", name: "profile", component: () => import("../views/ProfileView.vue") },
        {
          path: "recommendations",
          name: "recommendations",
          component: () => import("../views/Recommendations.vue"),
        },
        {
          path: "spots/:id",
          name: "spot-detail",
          component: () => import("../views/SpotDetail.vue"),
          props: true,
        },
        { path: "favorites", name: "favorites", component: () => import("../views/Favorites.vue") },
        { path: "community", name: "community", component: () => import("../views/Community.vue") },
        {
          path: "city/:name",
          name: "city-topic",
          component: () => import("../views/CityTopic.vue"),
          props: true,
        },
        {
          path: "users/:id",
          name: "user-home",
          component: () => import("../views/UserHome.vue"),
          props: true,
        },
        {
          path: "users/:id/follows",
          name: "user-follows",
          component: () => import("../views/FollowListView.vue"),
          props: true,
        },
        {
          path: "community/posts/:id",
          name: "post-detail",
          component: () => import("../views/PostDetail.vue"),
          props: true,
        },
        {
          path: "community/create",
          name: "post-create",
          component: () => import("../views/PostEditor.vue"),
        },
        {
          path: "community/edit/:id",
          name: "post-edit",
          component: () => import("../views/PostEditor.vue"),
          props: true,
        },
        { path: "my-posts", name: "my-posts", component: () => import("../views/MyPosts.vue") },
        // 旧管理入口兼容重定向（设计方案 §二：P0-1 后不再复用普通用户布局）
        { path: "moderation", redirect: { name: "admin-review" } },
      ],
    },
    {
      path: "/admin",
      component: AdminLayout,
      meta: { requiresAdmin: true },
      children: [
        { path: "", name: "admin-dashboard", component: () => import("../views/admin/AdminDashboard.vue") },
        {
          path: "content/review",
          name: "admin-review",
          component: () => import("../views/admin/AdminContentReview.vue"),
          meta: { permission: "CONTENT_REVIEW" },
        },
        {
          path: "content/review/:id",
          name: "admin-post-review",
          component: () => import("../views/admin/AdminPostReview.vue"),
          props: true,
          meta: { permission: "CONTENT_REVIEW" },
        },
        {
          path: "content/ai-review",
          name: "admin-ai-review",
          component: () => import("../views/admin/AdminAiReview.vue"),
          meta: { permission: "CONTENT_REVIEW" },
        },
        {
          path: "reports",
          name: "admin-reports",
          component: () => import("../views/admin/AdminReports.vue"),
          meta: { permission: "CONTENT_REVIEW" },
        },
        {
          path: "reports/:id",
          name: "admin-report-detail",
          component: () => import("../views/admin/AdminReportDetail.vue"),
          props: true,
          meta: { permission: "CONTENT_REVIEW" },
        },
        {
          path: "posts",
          name: "admin-posts",
          component: () => import("../views/admin/AdminPosts.vue"),
          meta: { permission: "CONTENT_REVIEW" },
        },
        {
          path: "guides",
          name: "admin-guides",
          component: () => import("../views/admin/AdminGuides.vue"),
          meta: { permission: "GUIDE_MANAGE" },
        },
        {
          path: "guides/create",
          name: "admin-guide-create",
          component: () => import("../views/admin/AdminGuideEditor.vue"),
          meta: { permission: "GUIDE_MANAGE" },
        },
        {
          path: "guides/:id/edit",
          name: "admin-guide-edit",
          component: () => import("../views/admin/AdminGuideEditor.vue"),
          props: true,
          meta: { permission: "GUIDE_MANAGE" },
        },
        {
          path: "spots",
          name: "admin-spots",
          component: () => import("../views/admin/AdminSpots.vue"),
          meta: { permission: "SPOT_GOVERN" },
        },
        {
          path: "users",
          name: "admin-users",
          component: () => import("../views/admin/AdminUsers.vue"),
          meta: { permission: "USER_GOVERN" },
        },
        {
          path: "recommendations",
          name: "admin-recommendations",
          component: () => import("../views/admin/AdminRecommendations.vue"),
          meta: { permission: "RECOMMEND_OPS" },
        },
        {
          path: "audit",
          name: "admin-audit",
          component: () => import("../views/admin/AdminAudit.vue"),
          meta: { permission: "AUDIT_VIEW" },
        },
        {
          path: "charts",
          name: "admin-charts",
          component: () => import("../views/admin/AdminCharts.vue"),
          meta: { permission: "ANALYTICS_VIEW" },
        },
      ],
    },
  ],
});

router.beforeEach(async (to) => {
  const needAdmin = to.matched.some((r) => r.meta.requiresAdmin);

  // 公开页：已登录访问 /login 则送回各自首页（管理员 → /admin）
  if (to.meta.public) {
    if (to.name === "login" && isLoggedIn.value) {
      return { name: isAdmin.value ? "admin-dashboard" : "dashboard" };
    }
    return true;
  }

  // 受保护区：未登录 → 登录页，并记住想去的地址
  if (!isLoggedIn.value) {
    return {
      name: "login",
      query: to.fullPath !== "/" ? { redirect: to.fullPath } : {},
    };
  }

  // 管理端角色只进后台：管理员登录后不参与行程规划等用户端功能，
  // 访问任何用户端路由一律送回 /admin（只保留管理后台这一种工作台）
  if (!needAdmin && isAdmin.value) {
    return { name: "admin-dashboard" };
  }

  // 管理后台守卫：本地会话角色缺失/过期时先同步一次；仍非管理端 → 普通首页
  if (needAdmin && !isAdmin.value) {
    await syncRole();
    if (!isAdmin.value) {
      return { name: "dashboard" };
    }
  }

  // 管理后台单页权限守卫（§11 第五阶段角色细化）：
  // 权限点已知（非空）且不满足 → 回运营总览；权限点未知（旧会话/接口未返回）则不拦，交给服务端 403 兜底
  if (needAdmin && permissions.value.length === 0) {
    await syncRole();
  }
  if (needAdmin) {
    const required = to.matched
      .map((r) => r.meta.permission as string | undefined)
      .find((p): p is string => !!p);
    if (required && permissions.value.length > 0 && !hasPermission(required)) {
      return { name: "admin-dashboard" };
    }
  }

  // 结果页强依赖最新行程；无产物直接访问（如刷新）→ 回规划页
  if (to.name === "result" && !latestItinerary.value) {
    return { name: "plan" };
  }

  return true;
});

export default router;
