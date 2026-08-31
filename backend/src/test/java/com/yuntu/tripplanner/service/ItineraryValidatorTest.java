package com.yuntu.tripplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.agent.CollectedData;
import com.yuntu.tripplanner.client.LlmClient;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.HotelItem;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.SpotItem;
import com.yuntu.tripplanner.model.TransportItem;
import com.yuntu.tripplanner.model.TripRequest;
import com.yuntu.tripplanner.model.WeatherForecastResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 校验层跨场景测试：用「无 RAG 城市」（mock RagService，任何景点都查不到攻略卡片）
 * 验证 5 个兜底修复是通用逻辑、不依赖具体城市：
 * 酒店/餐厅当景点移除、预算编造清理、毛毛雨不误判恶劣天气、
 * 交通时长规范化、POI 地址强制覆盖。
 */
@ExtendWith(MockitoExtension.class)
class ItineraryValidatorTest {

    @Mock
    private LlmClient llmClient;
    @Mock
    private RagService ragService;

    private ItineraryValidator validator;

    @BeforeEach
    void setUp() {
        // ragService 不 stub → findSpotCard 默认返回 null → 模拟"攻略库无此景点"，
        // 全部兜底只能靠通用校验逻辑本身，验证其与城市/RAG 无关
        validator = new ItineraryValidator(llmClient, new ObjectMapper(), ragService);
    }

    private TripRequest request(String dest) {
        TripRequest r = new TripRequest();
        r.setDestination(dest);
        r.setTravelers(2);
        r.setBudget(null); // 不触发预算修正 LLM 调用
        return r;
    }

    private SpotItem spot(String name, String addr) {
        SpotItem s = new SpotItem();
        s.setName(name);
        s.setAddress(addr);
        return s;
    }

    private DayPlan day(int idx) {
        DayPlan d = new DayPlan();
        d.setDayIndex(idx);
        d.setSpots(new ArrayList<>());
        d.setTransport(new ArrayList<>());
        d.setNotes(new ArrayList<>());
        return d;
    }

    private CollectedData collectedWithPoi(Map<String, Object> poiResults, WeatherForecastResponse wf) {
        CollectedData c = new CollectedData();
        c.setPoiResults(poiResults);
        if (wf != null) {
            c.setWeatherData(Map.of("forecast", wf));
        }
        return c;
    }

    @Test
    void removesLodgingAndDiningFromSpotsRegardlessOfCity() {
        // 无 RAG 城市：酒店分店名、青旅、餐厅都被错误列为景点 → 全部移除，真景点保留
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("登巴客栈(上海长寿路店)", "西康路1426号"));
        d1.getSpots().add(spot("大隐国际青年旅舍(上海陆家嘴滨江道店)", "浦东大道834弄"));
        d1.getSpots().add(spot("绿茶餐厅(三亚蓝海购物广场店)", "新风路261号"));
        d1.getSpots().add(spot("外滩", "中山东一路"));

        Itinerary it = new Itinerary();
        it.setDestination("惠州");
        it.setDays(List.of(d1));

        validator.validateAndRepair(it, request("惠州"), collectedWithPoi(Map.of(), null));

        assertEquals(1, d1.getSpots().size(), "只应保留真景点");
        assertEquals("外滩", d1.getSpots().get(0).getName());
        assertTrue(d1.getNotes().stream().anyMatch(n -> n.contains("已移除被误列为景点的")),
                "应备注移除说明");
    }

    @Test
    void cleansFakeBudgetNotesWithNewPatterns() {
        DayPlan d1 = day(1);
        Itinerary it = new Itinerary();
        it.setDestination("惠州");
        it.setDays(List.of(d1));
        it.setSourceNotes(new ArrayList<>(List.of(
                "预算建模：住宿按舒适型420元/间×2晚=840元；总和3197元，符合3200元预算",
                "预算明细：住宿796元 + 门票130元 + 餐饮580元 + 市内交通120元 + 应急预留668元 = 3194元，严格控制在3200元内",
                "本地攻略库命中 5 条（RAG）")));

        validator.validateAndRepair(it, request("惠州"), collectedWithPoi(Map.of(), null));

        assertEquals(1, it.getSourceNotes().size(), "编造的预算核算行必须被移除");
        assertTrue(it.getSourceNotes().get(0).contains("本地攻略库命中"), "真实来源说明应保留");
    }

    @Test
    void lightRainIsNotBadWeatherButThunderstormIs() {
        WeatherForecastResponse wf = new WeatherForecastResponse();
        WeatherForecastResponse.WeatherDay d1w = new WeatherForecastResponse.WeatherDay();
        d1w.setDayWeather("雷暴");
        d1w.setNightWeather("雷暴");
        WeatherForecastResponse.WeatherDay d2w = new WeatherForecastResponse.WeatherDay();
        d2w.setDayWeather("小毛毛雨");
        d2w.setNightWeather("小毛毛雨");
        wf.setDays(List.of(d1w, d2w));

        DayPlan d1 = day(1);
        d1.getSpots().add(spot("惠州西湖", "惠城区环城西路"));
        DayPlan d2 = day(2);
        d2.getSpots().add(spot("惠州西湖", "惠城区环城西路"));

        Itinerary it = new Itinerary();
        it.setDestination("惠州");
        it.setDays(List.of(d1, d2));

        Map<String, Object> poi = Map.of("景点", List.of(Map.of("name", "惠州西湖", "address", "惠城区环城西路")));
        validator.validateAndRepair(it, request("惠州"), collectedWithPoi(poi, wf));

        // 雷暴天 → Plan B 触发，备注点名户外景点
        assertTrue(d1.getNotes().stream().anyMatch(n -> n.contains("当日天气「雷暴 雷暴」恶劣") && n.contains("户外景点：惠州西湖")),
                "雷暴天应触发室内化警示且点名户外景点");
        // 小毛毛雨 → 不误判为恶劣天气
        assertFalse(d2.getNotes().stream().anyMatch(n -> n.contains("恶劣")),
                "小毛毛雨不应被判为恶劣天气");
    }

    @Test
    void normalizesTransportDuration() {
        DayPlan d1 = day(1);
        TransportItem t = new TransportItem();
        t.setDuration("11.80 km / 28 分钟");
        t.setMode("打车");
        d1.getTransport().add(t);

        Itinerary it = new Itinerary();
        it.setDestination("惠州");
        it.setDays(List.of(d1));

        validator.validateAndRepair(it, request("惠州"), collectedWithPoi(Map.of(), null));

        assertEquals("28 分钟", t.getDuration(), "时长应统一为纯分钟格式");
    }

    @Test
    void poiAddressOverridesLlmAddressForAnyCity() {
        // 无 RAG：LLM 写的地址是错的，但 POI 池有该景点的真实地址 → 必须覆盖
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("惠州西湖", "隔壁人民广场的地址"));

        Itinerary it = new Itinerary();
        it.setDestination("惠州");
        it.setDays(List.of(d1));

        Map<String, Object> poi = Map.of("景点", List.of(Map.of("name", "惠州西湖", "address", "惠城区环城西路")));
        validator.validateAndRepair(it, request("惠州"), collectedWithPoi(poi, null));

        assertEquals("惠城区环城西路", d1.getSpots().get(0).getAddress(),
                "POI 真实地址必须覆盖 LLM 错地址，与城市/攻略无关");
    }

    @Test
    void detectsAddressUsurpation() {
        // 丽江实测案例：LLM 把「四方街」地址写成木府的「官院巷49号」（POI 池有木府无四方街）
        // → 地址被其他 POI 占用，必须置「待核实」而不是输出错误地址
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("四方街", "光义街官院巷49号"));

        Itinerary it = new Itinerary();
        it.setDestination("丽江");
        it.setDays(List.of(d1));
        it.setSourceNotes(new ArrayList<>());

        Map<String, Object> poi = Map.of("景点", List.of(
                Map.of("name", "木府", "address", "光义街官院巷49号")));
        validator.validateAndRepair(it, request("丽江"), collectedWithPoi(poi, null));

        assertEquals("（地址待核实）", d1.getSpots().get(0).getAddress(),
                "地址被其他 POI 占用时必须置待核实，不能输出错误地址");
        assertTrue(d1.getNotes().stream().anyMatch(n -> n.contains("地址疑似错用「木府」的地址")),
                "应备注说明地址疑似错用");
    }

    @Test
    void geoReferenceNotFlaggedButRealMismatchIs() {
        // ① 回民街地址"莲湖区钟楼附近"是地理参照 → 不应报错位、不应清描述
        DayPlan d1 = day(1);
        SpotItem huimin = spot("回民街", "莲湖区钟楼附近");
        huimin.setDescription("西安最有名的小吃街，主街游客多价格高，本地人更爱去洒金桥。");
        d1.getSpots().add(huimin);

        Itinerary it = new Itinerary();
        it.setDestination("西安");
        it.setDays(List.of(d1));
        it.setSourceNotes(new ArrayList<>());

        Map<String, Object> poi = Map.of("景点", List.of(
                Map.of("name", "钟楼", "address", "西安市中心"),
                Map.of("name", "回民街", "address", "莲湖区钟楼附近")));
        validator.validateAndRepair(it, request("西安"), collectedWithPoi(poi, null));

        assertEquals("莲湖区钟楼附近", huimin.getAddress(), "地理参照地址不应被改");
        assertFalse(d1.getNotes().stream().anyMatch(n -> n.contains("疑似混入")),
                "「钟楼附近」是位置参照，不应报张冠李戴");

        // ② 兵马俑描述真混入"大雁塔"（无方位词）→ 必须检测并清描述
        DayPlan d2 = day(2);
        SpotItem bingma = spot("兵马俑", "临潼区秦陵北路");
        bingma.setDescription("大雁塔是玄奘为保存佛经而建，西安的标志性建筑。");
        d2.getSpots().add(bingma);

        Itinerary it2 = new Itinerary();
        it2.setDestination("西安");
        it2.setDays(List.of(d2));
        it2.setSourceNotes(new ArrayList<>());
        Map<String, Object> poi2 = Map.of("景点", List.of(
                Map.of("name", "大雁塔", "address", "雁塔区"),
                Map.of("name", "兵马俑", "address", "临潼区秦陵北路")));
        validator.validateAndRepair(it2, request("西安"), collectedWithPoi(poi2, null));

        assertFalse(bingma.getDescription().contains("大雁塔"),
                "描述真混入其他景点内容时必须被清理");
        assertTrue(d2.getNotes().stream().anyMatch(n -> n.contains("疑似混入")),
                "真错位应正常报警示");
    }

    @Test
    void economyLodgingDowngradedWhenLevelTooHigh() {
        // 通用校验：高档型/舒适型绝不能选青旅/民宿（杭州案例"高档型住旅舍"）
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("苏堤", "西湖"));
        HotelItem hotel = new HotelItem();
        hotel.setName("可见时光·望达斯旅舍(杭州西湖湖滨河坊街店)");
        hotel.setLevel("高档型");
        hotel.setEstimatedCost(800.0);
        d1.setHotel(hotel);

        Itinerary it = new Itinerary();
        it.setDestination("杭州");
        it.setDays(List.of(d1));
        it.setSourceNotes(new ArrayList<>());

        validator.validateAndRepair(it, request("杭州"), collectedWithPoi(Map.of(), null));

        assertEquals("经济型", hotel.getLevel(), "青旅/旅舍必须按实际降为经济型");
        assertEquals(200.0, hotel.getEstimatedCost(), "价格应同步降到经济型区间");
        assertTrue(it.getSourceNotes().stream().anyMatch(n -> n.contains("已按实际调整为经济型")),
                "应警示档次不匹配并说明已调整");
    }

    @Test
    void hotelNameWithLocationDoesNotRemoveRealSpot() {
        // 通用校验：酒店名里的地理定位词（西湖/河坊街）不能导致真实景点被误删
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("西湖", "西湖景区"));
        d1.getSpots().add(spot("苏堤", "西湖"));
        HotelItem hotel = new HotelItem();
        hotel.setName("可见时光·望达斯旅舍(杭州西湖湖滨河坊街店)");
        hotel.setLevel("经济型");
        hotel.setEstimatedCost(200.0);
        d1.setHotel(hotel);

        Itinerary it = new Itinerary();
        it.setDestination("杭州");
        it.setDays(List.of(d1));
        it.setSourceNotes(new ArrayList<>());

        validator.validateAndRepair(it, request("杭州"), collectedWithPoi(Map.of(), null));

        assertEquals(2, d1.getSpots().size(), "「西湖」「苏堤」是真实景点，不能被酒店名里的定位词误删");
        assertFalse(d1.getNotes().stream().anyMatch(n -> n.contains("已移除")),
                "不应触发任何移除警示");
    }

    @Test
    void shortNameMismatchDetected() {
        // 无 RAG 城市实测：LLM 把「东坡祠」描述整段抄成西湖简介，但写的是简称"惠州西湖"
        //（POI 完整名"惠州西湖风景名胜区"）——必须按简称也能检出并清理
        DayPlan d1 = day(1);
        SpotItem dongpo = spot("东坡祠", "环城西路2号");
        dongpo.setDescription("惠州西湖是中国著名风景区之一，以山水相依、湖光山色著称。景区内有苏堤、丰湖书院等历史遗迹。");
        d1.getSpots().add(dongpo);

        Itinerary it = new Itinerary();
        it.setDestination("惠州");
        it.setDays(List.of(d1));
        it.setSourceNotes(new ArrayList<>());

        Map<String, Object> poi = Map.of("景点", List.of(
                Map.of("name", "惠州西湖风景名胜区", "address", "环城西路2号"),
                Map.of("name", "东坡祠", "address", "环城西路2号")));
        validator.validateAndRepair(it, request("惠州"), collectedWithPoi(poi, null));

        assertFalse(dongpo.getDescription().contains("惠州西湖"),
                "描述抄了其他景点（含简称）必须被清理");
        assertTrue(d1.getNotes().stream().anyMatch(n -> n.contains("疑似混入")),
                "简称抄内容也应正常报警示");
    }

    @Test
    void flagsSevereBudgetMismatchWithActionableAdvice() {
        // 极端不符：用户预算 30 元，但 LLM 生成高档型酒店 800/晚×3 天 → 2400，repairBudget
        // 无法把 POI 真实价格压到接近 0；系统应程序强制降级酒店为经济型，并诚实告知剩余缺口
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("惠州西湖", "惠城区环城西路"));
        HotelItem hotel = new HotelItem();
        hotel.setName("高档酒店");
        hotel.setLevel("高档型");
        hotel.setEstimatedCost(800.0);
        d1.setHotel(hotel);

        Itinerary it = new Itinerary();
        it.setDestination("惠州");
        it.setDays(List.of(d1));
        it.setEstimatedBudget(2735.0);
        it.setSourceNotes(new ArrayList<>());

        TripRequest req = request("惠州");
        req.setBudget(30.0);

        validator.validateAndRepair(it, req, collectedWithPoi(Map.of(), null));

        assertEquals(200.0, hotel.getEstimatedCost(), "极端预算不符必须程序强制降酒店为经济型 200/晚");
        assertEquals("经济型", hotel.getLevel(), "酒店等级应同步降为经济型");
        assertTrue(it.getSourceNotes().stream().anyMatch(n ->
                n.contains("您的预算") && n.contains("远低于行程估算") && n.contains("已自动将 1 晚酒店降为「经济型」")),
                "警示应说明已自动降级酒店并给剩余缺口建议");
    }

    @Test
    void flagsMildBudgetMismatchWithoutExtremeLabel() {
        // 中度不符（1.5~5 倍）：温和提示，不应用「远低」措辞
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("惠州西湖", "惠城区环城西路"));
        Itinerary it = new Itinerary();
        it.setDestination("惠州");
        it.setDays(List.of(d1));
        it.setEstimatedBudget(1000.0);
        it.setSourceNotes(new ArrayList<>());

        TripRequest req = request("惠州");
        req.setBudget(500.0);  // 估算 1000/预算 500 = 2 倍

        validator.validateAndRepair(it, req, collectedWithPoi(Map.of(), null));

        assertTrue(it.getSourceNotes().stream().anyMatch(n ->
                n.contains("已超出您的预算") && !n.contains("远低")),
                "中度不符应给温和提示（不应用「远低」措辞）");
    }
}
