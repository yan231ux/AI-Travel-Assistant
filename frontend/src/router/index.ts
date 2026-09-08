import { createRouter, createWebHistory } from "vue-router";

import AppLayout from "../layouts/AppLayout.vue";
import { isLoggedIn } from "../stores/session";
import { latestItinerary } from "../stores/trip";
import AgentProcess from "../views/AgentProcess.vue";
import CityTopic from "../views/CityTopic.vue";
import Community from "../views/Community.vue";
import Favorites from "../views/Favorites.vue";
import History from "../views/History.vue";
import Home from "../views/Home.vue";
import Login from "../views/Login.vue";
import Moderation from "../views/Moderation.vue";
import MyPosts from "../views/MyPosts.vue";
import PlannerView from "../views/PlannerView.vue";
import PostDetail from "../views/PostDetail.vue";
import PostEditor from "../views/PostEditor.vue";
import ProfileView from "../views/ProfileView.vue";
import Recommendations from "../views/Recommendations.vue";
import Result from "../views/Result.vue";
import SpotDetail from "../views/SpotDetail.vue";
import UserHome from "../views/UserHome.vue";

/**
 * 路由表（产品化改造批次 C 起，对齐 PRODUCT_EVOLUTION_PLAN §3.1/§14.1）：
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
      component: Login,
      meta: { public: true },
    },
    {
      path: "/",
      component: AppLayout,
      children: [
        { path: "", name: "dashboard", component: Home },
        { path: "plan", name: "plan", component: PlannerView },
        { path: "agent", name: "agent", component: AgentProcess },
        { path: "result", name: "result", component: Result },
        { path: "history", name: "history", component: History },
        { path: "profile", name: "profile", component: ProfileView },
        { path: "recommendations", name: "recommendations", component: Recommendations },
        { path: "spots/:id", name: "spot-detail", component: SpotDetail, props: true },
        { path: "favorites", name: "favorites", component: Favorites },
        { path: "community", name: "community", component: Community },
        { path: "city/:name", name: "city-topic", component: CityTopic, props: true },
        { path: "users/:id", name: "user-home", component: UserHome, props: true },
        { path: "community/posts/:id", name: "post-detail", component: PostDetail, props: true },
        { path: "community/create", name: "post-create", component: PostEditor },
        { path: "community/edit/:id", name: "post-edit", component: PostEditor, props: true },
        { path: "my-posts", name: "my-posts", component: MyPosts },
        { path: "moderation", name: "moderation", component: Moderation },
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
