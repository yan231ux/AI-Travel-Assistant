import type { ThemeConfig } from "ant-design-vue/es/config-provider/context";

/**
 * Ant Design Vue 主题令牌（UI 视觉升级方案 §4）。
 * ⚠️ 与 ./tokens.css 的 :root 变量保持同源：改品牌色时两处同步修改。
 *
 * 配色策略（方案 §4.2）：
 * - 珊瑚橙 = 主行动按钮/重点强调（colorPrimary，antd primary 按钮即 CTA）；
 * - 青绿   = 信息链接/次级强调（colorLink/colorInfo），避免全站单一绿色；
 * - 深青   = 品牌与导航文字；
 * - 不使用紫色渐变。
 */
export const antdTheme: ThemeConfig = {
  token: {
    colorPrimary: "#d9775d",        // 珊瑚橙：主行动
    colorInfo: "#2f7770",           // 青绿：信息强调
    colorLink: "#2f7770",           // 内联链接用青绿（克制）
    colorSuccess: "#3c8c70",
    colorWarning: "#c98a2d",
    colorError: "#c65d51",
    colorTextBase: "#17211f",
    colorBgLayout: "#f7f5ef",       // 暖白页面底
    colorBgContainer: "#ffffff",
    colorBorder: "rgba(23,33,31,0.15)",
    colorBorderSecondary: "rgba(23,33,31,0.08)",
    borderRadius: 8,
    borderRadiusLG: 12,
    borderRadiusSM: 6,
    fontFamily:
      '-apple-system, BlinkMacSystemFont, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Noto Sans SC", "Helvetica Neue", Arial, sans-serif',
    fontSize: 14,
  },
};
