package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.client.AmapClient;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.SpotItem;
import com.yuntu.tripplanner.model.TransportItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
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

    @Test
    void drivingRouteNotAdoptedForMetroLeg() {
        // 上海实测：mode=地铁 的段被驾车路线覆盖，页面出现"地铁·来源=高德路线估算（驾车）"的自相矛盾
        // → 地铁/公交/步行等非用车段一律不用驾车路线覆盖
        TransportItem metro = new TransportItem();
        metro.setMode("地铁");
        metro.setFromPlace("开蔓酒店");
        metro.setToPlace("上海城隍庙");
        DayPlan day = new DayPlan();
        day.setDayIndex(1);
        day.setTransport(new ArrayList<>(List.of(metro)));
        Itinerary it = new Itinerary();
        it.setDestination("上海");
        it.setDays(List.of(day));

        service.enrich(it);

        assertNull(metro.getSource(), "地铁段不得被驾车路线覆盖");
        assertNull(metro.getEstimatedMinutes(), "地铁段不得被填驾车时长");
    }

    @Test
    void drivingRouteAdoptedForCarLeg() {
        // 用车类交通段正常走驾车路线补全（不误伤）
        when(amapClient.geocode("开蔓酒店")).thenReturn(Map.of("longitude", 121.0, "latitude", 31.0));
        when(amapClient.geocode("上海城隍庙")).thenReturn(Map.of("longitude", 121.5, "latitude", 31.2));
        when(amapClient.getDrivingRoute("121.0,31.0", "121.5,31.2"))
                .thenReturn(Map.of("duration", 23, "distance", 10.8));

        TransportItem car = new TransportItem();
        car.setMode("打车");
        car.setFromPlace("开蔓酒店");
        car.setToPlace("上海城隍庙");
        DayPlan day = new DayPlan();
        day.setDayIndex(1);
        day.setTransport(new ArrayList<>(List.of(car)));
        Itinerary it = new Itinerary();
        it.setDestination("上海");
        it.setDays(List.of(day));

        service.enrich(it);

        assertEquals("高德路线估算（驾车）", car.getSource(), "用车段应正常补全驾车路线");
        assertEquals(23, car.getEstimatedMinutes());
        assertEquals(10.8, car.getDistanceKm(), 0.01);
    }

    /* ================= 图片兜底：POI 无照片 → 静态地图快照（真实位置，绝不张冠李戴） ================= */

    @Test
    void missingPhotoFallsBackToStaticMapSnapshot() {
        // 高德 v3 photos 覆盖率极低（实测上海 5 景点只有东方明珠有照片）——
        // POI 匹配成功但没照片、有坐标 → 用景点真实位置的静态地图快照兜底，不留"暂无图片"空块
        when(amapClient.searchPoi("上海", "上海自然博物馆")).thenReturn(List.of(
                Map.of("name", "上海自然博物馆", "address", "北京西路510号",
                        "longitude", 121.4698, "latitude", 31.2805)));
        when(amapClient.staticMapUrl(org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn("https://restapi.amap.com/v3/staticmap?mock=1");

        SpotItem spot = enrichSpot("上海", "上海自然博物馆");

        assertNotNull(spot.getImageUrl(), "无照片但有坐标必须有图片兜底");
        assertTrue(spot.getImageUrl().contains("staticmap"), "兜底图应是高德静态地图快照，实际：" + spot.getImageUrl());
        assertEquals(31.2805, spot.getLatitude(), 0.0001);
    }

    @Test
    void blankImageUrlTreatedAsMissing_realPhotoStillWins() {
        // LLM 留下的空串图片视为没图 → 允许补全；且真实照片优先于静态快照
        SpotItem spot = new SpotItem();
        spot.setName("苏堤");
        spot.setImageUrl("");
        DayPlan day = new DayPlan();
        day.setDayIndex(1);
        day.setSpots(List.of(spot));
        Itinerary it = new Itinerary();
        it.setDestination("杭州");
        it.setDays(List.of(day));
        when(amapClient.searchPoi("杭州", "苏堤")).thenReturn(List.of(
                Map.of("name", "苏堤", "image_url", "http://sudi.jpg", "address", "西湖")));

        service.enrich(it);

        assertEquals("http://sudi.jpg", spot.getImageUrl(), "空串图应视为缺失并用真实照片补上");
    }

    @Test
    void enrichMissingFillsRefilledSpotWithoutTouchingCompleteOnes() {
        // 校验层补位的景点没走过主补全（无图无坐标）→ enrichMissing 只补缺失项；
        // 已有图有坐标的景点不重复打 POI 接口
        SpotItem refilled = new SpotItem();
        refilled.setName("上海市历史博物馆");
        SpotItem complete = new SpotItem();
        complete.setName("东方明珠广播电视塔");
        complete.setImageUrl("http://dongfangmingzhu.jpg");
        complete.setLatitude(121.4998);
        complete.setLongitude(121.4998);
        DayPlan day = new DayPlan();
        day.setDayIndex(3);
        day.setSpots(new ArrayList<>(List.of(refilled, complete)));
        Itinerary it = new Itinerary();
        it.setDestination("上海");
        it.setDays(List.of(day));

        when(amapClient.searchPoi("上海", "上海市历史博物馆")).thenReturn(List.of(
                Map.of("name", "上海市历史博物馆", "address", "南京西路325号",
                        "longitude", 121.4645, "latitude", 31.2337)));
        when(amapClient.staticMapUrl(org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn("https://restapi.amap.com/v3/staticmap?mock=1");

        int n = service.enrichMissing(it);

        assertEquals(1, n, "只补全 1 个缺失景点");
        assertEquals("南京西路325号", refilled.getAddress(), "补位景点应拿到真实地址");
        assertNotNull(refilled.getLatitude(), "补位景点应拿到坐标");
        assertTrue(refilled.getImageUrl().contains("staticmap"), "无照片时用静态快照兜底");
        assertEquals("http://dongfangmingzhu.jpg", complete.getImageUrl(), "完整景点不得被碰");
    }
}
