package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.client.AmapClient;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.SpotItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 地图补全测试：POI 名与景点名必须匹配才补图/补地址，防止图片张冠李戴
 *（实测「东坡祠」拿到西湖景区的图、「大云寺」拿到书法图）。
 */
@ExtendWith(MockitoExtension.class)
class MapEnrichmentServiceTest {

    @Mock
    private AmapClient amapClient;

    private MapEnrichmentService service;

    @BeforeEach
    void setUp() {
        // 同步 executor，保证测试确定性
        service = new MapEnrichmentService(amapClient, Runnable::run);
    }

    private SpotItem enrichSpot(String city, String spotName) {
        SpotItem spot = new SpotItem();
        spot.setName(spotName);
        DayPlan day = new DayPlan();
        day.setDayIndex(1);
        day.setSpots(List.of(spot));
        Itinerary it = new Itinerary();
        it.setDestination(city);
        it.setDays(List.of(day));
        service.enrich(it);
        return spot;
    }

    @Test
    void doesNotReuseOtherPlacesImage() {
        // 东坡祠：高德返回 [西湖(有图), 东坡祠(无图)] → 不得拿西湖的图给东坡祠
        when(amapClient.searchPoi("惠州", "东坡祠")).thenReturn(List.of(
                Map.of("name", "惠州西湖风景名胜区", "image_url", "http://xihu.jpg", "address", "环城西路2号"),
                Map.of("name", "东坡祠", "address", "环城西路2号")));

        SpotItem spot = enrichSpot("惠州", "东坡祠");

        assertTrue(spot.getImageUrl() == null || spot.getImageUrl().isBlank(),
                "名称不匹配的 POI 图不得复用给本景点");
        assertEquals("环城西路2号", spot.getAddress(), "名称匹配的 POI 地址可正常补全");
    }

    @Test
    void ignoresUnrelatedPoiName() {
        // 大云寺：高德返回名称完全不沾边的 POI → 放弃补全（无图无地址）
        when(amapClient.searchPoi("惠州", "大云寺")).thenReturn(List.of(
                Map.of("name", "东江二路美食城", "image_url", "http://food.jpg", "address", "东江二路")));

        SpotItem spot = enrichSpot("惠州", "大云寺");

        assertTrue(spot.getImageUrl() == null || spot.getImageUrl().isBlank(), "不匹配的 POI 不得补图");
        assertNull(spot.getAddress(), "不匹配的 POI 不得补地址");
    }

    @Test
    void usesMatchedPoiWithImage() {
        // 匹配且带图 → 正常补全（不误伤）
        when(amapClient.searchPoi("杭州", "苏堤")).thenReturn(List.of(
                Map.of("name", "苏堤", "image_url", "http://sudi.jpg", "address", "西湖")));

        SpotItem spot = enrichSpot("杭州", "苏堤");

        assertEquals("http://sudi.jpg", spot.getImageUrl());
        assertEquals("西湖", spot.getAddress());
    }
}
