package com.yuntu.tripplanner.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POI → 偏好标签 确定性映射单测（个性化阶段二）：
 * 高德业态 type 分段落规则优先 / 名称关键词兜底 / 商铺住宅等非游览不产出标签 /
 * 餐厅名称 → 口味标签。
 */
class SpotTagMapperTest {

    @Test
    void scenicType_mapsToNatureStyle() {
        List<String> tags = SpotTagMapper.styleTags("风景名胜;风景名胜;国家级景点", "西湖风景区");
        assertTrue(tags.contains("自然风景"), "风景名胜 type → 自然风景");
    }

    @Test
    void museumType_mapsToHistoryStyle() {
        List<String> tags = SpotTagMapper.styleTags("科教文化;博物馆;纪念馆", "故宫博物院");
        assertTrue(tags.contains("历史文化"));
    }

    @Test
    void pedestrianStreet_typeSegmentMapsToCityWalk() {
        // 宽窄巷子的线索只在高德细分 type 段（特色商业街），名称不含"步行街/老街"
        List<String> tags = SpotTagMapper.styleTags("购物服务;商业街;特色商业街", "宽窄巷子");
        assertTrue(tags.contains("城市漫游"), "type 细分段命中街区词 → 城市漫游");
    }

    @Test
    void emptyType_fallsBackToNameKeywords() {
        List<String> tags = SpotTagMapper.styleTags(null, "洱海公园");
        assertTrue(tags.contains("自然风景"), "type 缺失时按名称关键词兜底");
    }

    @Test
    void shopResidence_noStyleTags() {
        // 商铺/公寓不应映射出旅行风格标签（负反馈不引入标签噪声）
        List<String> tags = SpotTagMapper.styleTags("购物服务;专卖店;家电卖场", "火星人集成灶(河东路店)");
        assertTrue(tags.isEmpty() || !tags.contains("自然风景"));
        List<String> residence = SpotTagMapper.styleTags("商务住宅;住宅区;小区", "阳光花园小区");
        assertTrue(residence.isEmpty(), "住宅区不应映射出风格标签");
    }

    @Test
    void atMostTwoTags_dedupPreserveOrder() {
        List<String> tags = SpotTagMapper.styleTags("风景名胜;湖泊;科教文化;博物馆", "某湖滨博物馆");
        assertTrue(tags.size() <= 2, "风格标签 ≤2");
        assertEquals(tags.size(), List.copyOf(new java.util.LinkedHashSet<>(tags)).size(), "无重复");
    }

    @Test
    void restaurantName_mapsFoodTags() {
        List<String> tags = SpotTagMapper.foodTags("老北京火锅(前门店)");
        assertTrue(tags.contains("火锅"));
        assertFalse(tags.contains("海鲜"), "未命中不产出");
    }

    @Test
    void plainRestaurantName_noFoodTag() {
        assertTrue(SpotTagMapper.foodTags("街角家常菜").isEmpty() || !SpotTagMapper.foodTags("街角家常菜").contains("火锅"),
                "未命中口味词典不产出标签");
    }

    /* ============ 2026-09-09 建筑名胜修正：纯建筑古迹不应被 type "风景名胜" 误打"自然风景" ============ */

    /** 高德 type 仅有"风景名胜"，名称含强历史建筑信号（天安门/天坛/纪念堂/陵/寺/宫/塔/门 等）→ 改为"历史文化" */
    @Test
    void pureArchitecture_overridesScenicToHistory() {
        // 用户截图里的"全 48%"真凶：天安门/天坛/毛主席纪念堂被高德"风景名胜"误打"自然风景"
        assertTrue(SpotTagMapper.styleTags("风景名胜;风景名胜", "天安门").contains("历史文化"));
        assertTrue(SpotTagMapper.styleTags("风景名胜;风景名胜", "天坛公园").contains("历史文化"));
        assertTrue(SpotTagMapper.styleTags("风景名胜;风景名胜", "毛主席纪念堂").contains("历史文化"));
        assertFalse(SpotTagMapper.styleTags("风景名胜;风景名胜", "天安门").contains("自然风景"),
                "纯建筑古迹不该再被误标为自然风景");
    }

    /** 名称含强自然信号（山/海/湖/植物园/草原…）则尊重自然属性，不被强制改成"历史文化" */
    @Test
    void strongNatureSignal_preservedAsNature() {
        // 这些是真正的自然/园林景观，不应被建筑名胜修正误改
        assertTrue(SpotTagMapper.styleTags("风景名胜;风景名胜", "景山公园").contains("自然风景"));
        assertTrue(SpotTagMapper.styleTags("风景名胜;风景名胜", "北海公园").contains("自然风景"));
        assertTrue(SpotTagMapper.styleTags("风景名胜;风景名胜", "中山公园").contains("自然风景"));
        assertTrue(SpotTagMapper.styleTags("风景名胜;风景名胜", "杭州西湖").contains("自然风景"));
    }

    /** 自然+历史双信号都缺（普通 POI 名称）→ 不动 type 推断，保持原"自然风景" */
    @Test
    void plainName_keepsTypeInference() {
        // "省级旅游度假区" 不含山/海/湖/坛/寺 等强信号 → 保持高德 type 推断
        assertTrue(SpotTagMapper.styleTags("风景名胜;风景名胜", "省级旅游度假区").contains("自然风景"),
                "无强自然也强历史信号 → 保持高德 type 推断");
    }
}
