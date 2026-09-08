package com.yuntu.tripplanner.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业态/游览性判定回归测试（三亚"火星主题"实测案例锁定）：
 * 集成灶门店/公寓等非游览场所不得被当作"点名景点"，
 * 同时步行街/老街等可游览街区不得被误伤。
 */
class SightseeingFilterTest {

    // ---- 高德 type 业态判定 ----
    @Test
    void shopPoiIsNonSightseeingByType() {
        // 「火星人集成灶(三亚河东路店)」高德 type = 购物服务;专卖店 → 不是景点
        assertTrue(SightseeingFilter.isNonSightseeing(
                "购物服务;专卖店;家电卖场", "火星人集成灶(三亚河东路店)"));
        assertEquals("商铺类", SightseeingFilter.kindOfNonSightseeing(
                "购物服务;专卖店;家电卖场", "火星人集成灶(三亚河东路店)"));
    }

    @Test
    void apartmentPoiIsNonSightseeingByType() {
        // 「来自火星公寓(裕民路分店)」高德 type = 商务住宅;住宅区 → 不是景点
        assertTrue(SightseeingFilter.isNonSightseeing(
                "商务住宅;住宅区;别墅", "来自火星公寓(裕民路分店)"));
        assertEquals("公寓/住宅类", SightseeingFilter.kindOfNonSightseeing(
                "商务住宅;住宅区;别墅", "来自火星公寓(裕民路分店)"));
    }

    @Test
    void scenicPoiIsSightseeingByType() {
        // 真景点高德 type = 风景名胜 → 必须保留为可游览对象
        assertFalse(SightseeingFilter.isNonSightseeing(
                "风景名胜;风景名胜;国家级景点", "鹿回头风景区"));
        assertFalse(SightseeingFilter.isNonSightseeing(
                "科教文化服务;博物馆;纪念馆", "上海四行仓库抗战纪念馆"));
    }

    @Test
    void pedestrianStreetIsExceptionFromShoppingType() {
        // 购物服务里的步行街/老街是可游览街区，不能按购物排除
        assertFalse(SightseeingFilter.isNonSightseeing(
                "购物服务;商业街;步行街", "北京路步行街"));
        assertFalse(SightseeingFilter.isNonSightseeing(
                "购物服务;商业街;特色商业街", "宽窄巷子"));
    }

    // ---- type 缺失时的名称兜底 ----
    @Test
    void nameFallbackCatchesLodgingAndDining() {
        assertTrue(SightseeingFilter.isNonSightseeing(null, "亿点足迹青年旅舍(三亚商品街店)"));
        assertTrue(SightseeingFilter.isNonSightseeing(null, "来自火星公寓(裕民路分店)"));
        assertTrue(SightseeingFilter.isNonSightseeing(null, "绿茶餐厅(三亚蓝海购物广场店)"));
        assertEquals("住宿类", SightseeingFilter.kindOfNonSightseeing(null, "亿点足迹青年旅舍(三亚商品街店)"));
    }

    @Test
    void nameFallbackCatchesShopSuffix() {
        assertTrue(SightseeingFilter.isNonSightseeing(null, "三亚特产店"));
        assertEquals("商铺类", SightseeingFilter.kindOfNonSightseeing(null, "三亚特产店"));
    }

    @Test
    void nameFallbackDoesNotHarmRealSpots() {
        assertFalse(SightseeingFilter.isNonSightseeingName("椰梦长廊"));
        assertFalse(SightseeingFilter.isNonSightseeingName("鹿回头风景区"));
        assertFalse(SightseeingFilter.isNonSightseeingName("红花湖"));
        assertFalse(SightseeingFilter.isNonSightseeingName("四行仓库"));
    }

    // ---- 同品牌多分店根名去重 ----
    @Test
    void brandCoreGroupsBranchStores() {
        assertEquals(SightseeingFilter.brandCore("火星人集成灶(三亚河东路店)"),
                SightseeingFilter.brandCore("火星人集成灶(林旺路店)"),
                "同品牌分店应归到同一根名（只收一个）");
        assertEquals("火星人集成灶", SightseeingFilter.brandCore("火星人集成灶(三亚河东路店)"));
        assertEquals("椰梦长廊", SightseeingFilter.brandCore("椰梦长廊"),
                "真景点根名保持不变");
    }
}
