import { createRouter, createWebHistory } from "vue-router";

import AppLayout from "../layouts/AppLayout.vue";
import { isLoggedIn } from "../stores/session";
import { latestItinerary } from "../stores/trip";

/**
 * 路由表（产品化改造批次 C 起，对齐 PRODUCT_EVOLUTION_PLAN §3.1/§14.1；
 * UI 升级方案 §9/§10：页面组件全部动态 import() 分包，首屏只载登录页与布局）：
 * - /login 公开；已登录访问自动跳首页 /
 * - / 首页 Dashboard（登录后默认落地，不再重定向到规划表单）
 * - /plan 行程生成表单、/agent /result /history /profile 保留
 * - /recommendations 发现、/spots/:id 详情、/favorites 我的收藏
 * - /community 社区（阶段二）：帖子流 / 帖子详情 / 发帖 / 我的帖子 / 内容审核（管理员）
 * - 结果页不作为固定主导航（依赖内存中的最新行程）
 * - 未登录访问工作区路由统一去登录页
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
        { path: "moderation", name: "moderation", component: () => import("../views/Moderation.vue") },
      ],
    },
  ],
});

router.beforeEach((to) => {
  // 公开页：已登录访问 /login 则送回首页
  if (to.meta.public) {
    if (to.name === "login" && isLoggedIn.value) {
      return { name: "dashboard" };
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

  // 结果页强依赖最新行程；无产物直接访问（如刷新）→ 回规划页
  if (to.name === "result" && !latestItinerary.value) {
    return { name: "plan" };
  }

  return true;
});

export default router;
