package com.yuntu.tripplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.agent.CollectedData;
import com.yuntu.tripplanner.client.LlmClient;
import com.yuntu.tripplanner.model.CandidateEvidence;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.HotelItem;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.MealItem;
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

    private SpotItem spot(String name, String addr, String desc) {
        SpotItem s = spot(name, addr);
        s.setDescription(desc);
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
    void overspendBudget_collapsesHotelLocally() {
        // Q3：总花费超预算 20% → 本地压缩住宿（等比）+ 降档次标注，把总预算压回预算内，不调 LLM
        TripRequest req = request("惠州");
        req.setBudget(600.0);

        DayPlan d1 = day(1);
        d1.getSpots().add(spot("惠州西湖", "惠城区环城西路"));
        HotelItem hotel = new HotelItem();
        hotel.setName("惠州某大酒店");
        hotel.setLevel("豪华型");
        hotel.setEstimatedCost(800.0);
        d1.setHotel(hotel);
        TransportItem t = new TransportItem();
        t.setMode("打车");
        t.setEstimatedCost(50.0);
        d1.getTransport().add(t);

        Itinerary it = new Itinerary();
        it.setDestination("惠州");
        it.setDays(List.of(d1));

        validator.validateAndRepair(it, req, collectedWithPoi(Map.of(), null));

        // 非住宿支出 50，预算 600 → 住宿可用 550；800×0.6875=550，档位标注按每晚价落到高档型
        assertTrue(it.getEstimatedBudget() != null && it.getEstimatedBudget() <= 600 * 1.2 + 0.01,
                "收敛后总预算不得超过预算的 120%");
        assertEquals(550.0, hotel.getEstimatedCost(), 0.01, "住宿应等比压缩到预算水位");
        assertEquals("高档型", hotel.getLevel(), "档次标注应随每晚价下调");
        assertTrue(it.getSourceNotes().stream().anyMatch(n -> n.contains("住宿压缩") || n.contains("超预算")),
                "需在来源说明中诚实告知压缩动作");
    }

    @Test
    void withinBudget_hotelUntouched() {
        // Q3：未超支 → 住宿与预算完全不动
        TripRequest req = request("惠州");
        req.setBudget(2000.0);

        DayPlan d1 = day(1);
        HotelItem hotel = new HotelItem();
        hotel.setName("惠州某酒店");
        hotel.setLevel("豪华型");
        hotel.setEstimatedCost(800.0);
        d1.setHotel(hotel);

        Itinerary it = new Itinerary();
        it.setDestination("惠州");
        it.setDays(List.of(d1));

        validator.validateAndRepair(it, req, collectedWithPoi(Map.of(), null));

        assertEquals(800.0, hotel.getEstimatedCost(), 0.01, "未超支时住宿不得被压缩");
        assertEquals("豪华型", hotel.getLevel(), "未超支时档次不得被降");
    }

    @Test
    void hotelLevelForPrice_brackets() {
        assertEquals("豪华型", ItineraryValidator.hotelLevelForPrice(700));
        assertEquals("高档型", ItineraryValidator.hotelLevelForPrice(450));
        assertEquals("舒适型", ItineraryValidator.hotelLevelForPrice(250));
        assertEquals("经济型", ItineraryValidator.hotelLevelForPrice(120));
    }

    @Test
    void transportWithoutCost_backfilledByDistance() {
        // Q2：高德路线补全会置空金额（过路费≠车费），LLM 金额也常缺失 →
        // 校验层按 出行方式×距离 补算并在来源标注，保证预算合计不漏算跨区大段交通费
        DayPlan d1 = day(1);
        TransportItem taxi = new TransportItem();
        taxi.setMode("出租车");
        taxi.setFromPlace("惠州西湖");
        taxi.setToPlace("罗浮山");
        taxi.setDistanceKm(50.0);
        d1.getTransport().add(taxi);
        TransportItem metro = new TransportItem();
        metro.setMode("地铁");
        metro.setDistanceKm(20.0);
        d1.getTransport().add(metro);

        Itinerary it = new Itinerary();
        it.setDestination("惠州");
        it.setDays(List.of(d1));

        validator.validateAndRepair(it, request("惠州"), collectedWithPoi(Map.of(), null));

        assertEquals(13.0 + 2.3 * 47, taxi.getEstimatedCost(), 0.01,
                "50km 出租车按计价补算（起步 13 含 3km + 2.3 元/km）");
        assertTrue(taxi.getSource() != null && taxi.getSource().contains("补算"),
                "补算段来源必须标注");
        assertEquals(9.0, metro.getEstimatedCost(), 0.01, "地铁 20km 按 3 + 0.3×20 = 9 元计价");
    }

    @Test
    void transportWithoutCostAndDistance_backfilledByMinutes() {
        // Q2：无距离也缺金额 → 按时长×典型速度折算距离再计价；步行段金额为 0
        DayPlan d1 = day(1);
        TransportItem rail = new TransportItem();
        rail.setMode("高铁");
        rail.setEstimatedMinutes(120); // 250km/h × 2h ≈ 500km → 0.45 元/km × 500 = 225
        d1.getTransport().add(rail);
        TransportItem walk = new TransportItem();
        walk.setMode("步行");
        walk.setEstimatedMinutes(30); // 5km/h × 0.5h = 2.5km → 步行 0 元
        d1.getTransport().add(walk);

        Itinerary it = new Itinerary();
        it.setDestination("杭州");
        it.setDays(List.of(d1));

        validator.validateAndRepair(it, request("杭州"), collectedWithPoi(Map.of(), null));

        assertEquals(225.0, rail.getEstimatedCost(), 0.01, "高铁 2h≈500km 按 0.45 元/km 补算");
        assertEquals(0.0, walk.getEstimatedCost(), 0.0001, "步行段金额为 0（非缺失）");
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

    @Test
    void dedupeCrossSpotImageAndAddressReuse() {
        // 惠州实测：高德把「博物馆」「大云寺」的图和地址都串成了「惠州西湖」的
        //（名称含"博物馆""大云寺"挡不住 pickBestPlace）——行程级兜底必须清理串用
        DayPlan d1 = day(1);
        SpotItem xihu = spot("惠州西湖", "环城西路2号");
        xihu.setImageUrl("http://xihu.jpg");
        SpotItem museum = spot("惠州博物馆", "环城西路2号");
        museum.setImageUrl("http://xihu.jpg");   // 串用西湖图
        SpotItem dayun = spot("大云寺", "环城西路2号");
        dayun.setImageUrl("http://xihu.jpg");     // 串用西湖图
        d1.getSpots().addAll(List.of(xihu, museum, dayun));

        Itinerary it = new Itinerary();
        it.setDestination("惠州");
        it.setDays(List.of(d1));
        it.setSourceNotes(new ArrayList<>());

        // poi 池为空 → 隔离验证"行程级 dedupe 兜底"本身（不依赖 verifySpotAddresses 的 POI 映射）
        validator.validateAndRepair(it, request("惠州"), collectedWithPoi(Map.of(), null));

        // 首个（owner=西湖）保持原样
        assertEquals("http://xihu.jpg", xihu.getImageUrl());
        assertEquals("环城西路2号", xihu.getAddress());
        // 复用者：图片清空、地址标待核实
        assertNull(museum.getImageUrl(), "博物馆串用的西湖图应被清空");
        assertEquals("（地址待核实）", museum.getAddress(), "博物馆串用的西湖地址应标待核实");
        assertNull(dayun.getImageUrl(), "大云寺串用的西湖图应被清空");
        assertEquals("（地址待核实）", dayun.getAddress(), "大云寺串用的西湖地址应标待核实");
        assertTrue(it.getSourceNotes().stream().anyMatch(n -> n.contains("数据交叉校验")),
                "应在来源说明汇总交叉校验结果");
    }

    @Test
    void warnsWhenHotelFarFromAllSpots() {
        // 惠州实测：酒店选在惠东巽寮湾(海边)，景点全在惠城区/博罗，跨县约 50km+ → 应警示
        DayPlan d1 = day(1);
        SpotItem xihu = spot("西湖景区", "环城西路2号");
        xihu.setLatitude(23.11); xihu.setLongitude(114.39);
        SpotItem honghua = spot("红花湖", "上排红花湖路92号");
        honghua.setLatitude(23.10); honghua.setLongitude(114.38);
        d1.getSpots().addAll(List.of(xihu, honghua));

        HotelItem hotel = new HotelItem();
        hotel.setName("惠东巽寮湾屿海云天假日酒店");
        hotel.setLatitude(22.68); hotel.setLongitude(114.85); // 惠东县海边，距城区~60km
        d1.setHotel(hotel);

        Itinerary it = new Itinerary();
        it.setDestination("惠州");
        it.setDays(List.of(d1));
        it.setSourceNotes(new ArrayList<>());

        validator.validateAndRepair(it, request("惠州"), collectedWithPoi(Map.of(), null));

        assertTrue(it.getSourceNotes().stream().anyMatch(n -> n.contains("酒店") && n.contains("公里")),
                "酒店距所有景点过远时应警示跨片区");
    }

    @Test
    void noWarnWhenHotelNearSpots() {
        // 同在城市片区内（酒店就在景点附近）→ 不应误报
        DayPlan d1 = day(1);
        SpotItem xihu = spot("西湖景区", "环城西路2号");
        xihu.setLatitude(23.11); xihu.setLongitude(114.39);
        SpotItem honghua = spot("红花湖", "上排红花湖路92号");
        honghua.setLatitude(23.10); honghua.setLongitude(114.38);
        d1.getSpots().addAll(List.of(xihu, honghua));

        HotelItem hotel = new HotelItem();
        hotel.setName("惠州西湖某酒店");
        hotel.setLatitude(23.12); hotel.setLongitude(114.40); // 城区内，距景点<5km
        d1.setHotel(hotel);

        Itinerary it = new Itinerary();
        it.setDestination("惠州");
        it.setDays(List.of(d1));
        it.setSourceNotes(new ArrayList<>());

        validator.validateAndRepair(it, request("惠州"), collectedWithPoi(Map.of(), null));

        assertFalse(it.getSourceNotes().stream().anyMatch(n -> n.contains("距行程主要景点")),
                "酒店就在景点附近不应误报跨片区");
    }

    /* ================= Q1：每日就餐空间合理性（餐厅须贴近当天活动区域） ================= */

    /** 造一条 POI 记录（带经纬度） */
    private Map<String, Object> poiWithCoord(String name, double lat, double lng) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        m.put("latitude", lat);
        m.put("longitude", lng);
        m.put("address", name + "附近");
        return m;
    }

    /** 造一条 POI 记录（只有名称，无经纬度 → 不参与空间判定） */
    private Map<String, Object> poiNoCoord(String name) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        return m;
    }

    /** 临潼当日行程：上午兵马俑 + 下午华清宫（两景点相距很近，中心在临潼） */
    private DayPlan lintongDay() {
        DayPlan d1 = day(1);
        SpotItem bingmayong = spot("秦始皇兵马俑博物馆", "临潼区秦陵北路");
        bingmayong.setLatitude(34.3853);
        bingmayong.setLongitude(109.2785);
        SpotItem huaqing = spot("华清宫", "临潼区华清路38号");
        huaqing.setLatitude(34.3626);
        huaqing.setLongitude(109.2139);
        d1.getSpots().addAll(List.of(bingmayong, huaqing));
        d1.setMeals(new ArrayList<>());
        return d1;
    }

    private MealItem meal(String name) {
        MealItem m = new MealItem();
        m.setName(name);
        m.setMealType("午餐");
        m.setEstimatedCost(80.0);
        return m;
    }

    private Itinerary itineraryOf(DayPlan day) {
        Itinerary it = new Itinerary();
        it.setDestination("西安");
        it.setDays(List.of(day));
        it.setSourceNotes(new ArrayList<>());
        return it;
    }

    @Test
    void crossDistrictMeal_replacedByNearbyCandidate() {
        // 西安实测：上午临潼兵马俑、中午回市区吃火锅、下午再回临潼华清宫 —— 物理上来不及。
        // 候选池里有临潼本地面馆 → 确定性替换为就近餐厅。
        DayPlan d1 = lintongDay();
        d1.getMeals().add(meal("大秦御鼎火锅(博乐里购物广场店)"));
        Itinerary it = itineraryOf(d1);

        Map<String, Object> restaurants = Map.of("餐厅", List.of(
                poiWithCoord("大秦御鼎火锅(博乐里购物广场店)", 34.22, 108.95), // 市区，距临潼 ~32km
                poiWithCoord("临潼老碗面(华清池店)", 34.37, 109.23)));        // 临潼景区旁 ~1km

        validator.validateAndRepair(it, request("西安"), collectedWithPoi(restaurants, null));

        assertEquals("临潼老碗面(华清池店)", d1.getMeals().get(0).getName(),
                "跨区餐厅应被替换为当天活动区域内的候选");
        assertTrue(d1.getMeals().get(0).getSource().contains("已按当天活动区域就近调整"),
                "替换来源需可追溯");
        assertTrue(it.getSourceNotes().stream().anyMatch(n -> n.contains("跨区就餐不现实")),
                "应在来源说明中解释为何调整");
    }

    @Test
    void crossDistrictMeal_withoutNearbyCandidate_warnsHonestly() {
        // 候选池没有景区附近餐厅（高德按关键词召回可能覆盖不到）→ 不做无效替换，改为如实警示
        DayPlan d1 = lintongDay();
        d1.getMeals().add(meal("大秦御鼎火锅(博乐里购物广场店)"));
        Itinerary it = itineraryOf(d1);

        Map<String, Object> restaurants = Map.of("餐厅", List.of(
                poiWithCoord("大秦御鼎火锅(博乐里购物广场店)", 34.22, 108.95)));

        validator.validateAndRepair(it, request("西安"), collectedWithPoi(restaurants, null));

        assertEquals("大秦御鼎火锅(博乐里购物广场店)", d1.getMeals().get(0).getName(),
                "无就近候选时不得乱换");
        assertTrue(it.getSourceNotes().stream().anyMatch(n -> n.contains("可能来不及就近用餐")),
                "应如实警示跨区就餐，而不是静默放行");
    }

    @Test
    void mealNearDaySpots_notTouched() {
        // 午餐就在景区旁 → 不替换、不警示（避免把正常行程改坏）
        DayPlan d1 = lintongDay();
        d1.getMeals().add(meal("临潼老碗面(华清池店)"));
        Itinerary it = itineraryOf(d1);

        Map<String, Object> restaurants = Map.of("餐厅", List.of(
                poiWithCoord("临潼老碗面(华清池店)", 34.37, 109.23),
                poiWithCoord("大秦御鼎火锅(博乐里购物广场店)", 34.22, 108.95)));

        validator.validateAndRepair(it, request("西安"), collectedWithPoi(restaurants, null));

        assertEquals("临潼老碗面(华清池店)", d1.getMeals().get(0).getName());
        assertFalse(it.getSourceNotes().stream().anyMatch(n -> n.contains("就餐") || n.contains("就近用餐")),
                "就近用餐不应产生任何调整/警示");
    }

    @Test
    void mealWithoutCoordinates_notJudged_noFalsePositive() {
        // 餐厅在候选中但缺经纬度 → 数据不足，不做空间判定（宁可不动，也不误报）
        DayPlan d1 = lintongDay();
        d1.getMeals().add(meal("某本地餐馆"));
        Itinerary it = itineraryOf(d1);

        Map<String, Object> restaurants = Map.of("餐厅", List.of(
                poiNoCoord("某本地餐馆"),
                poiWithCoord("临潼老碗面(华清池店)", 34.37, 109.23)));

        validator.validateAndRepair(it, request("西安"), collectedWithPoi(restaurants, null));

        assertEquals("某本地餐馆", d1.getMeals().get(0).getName());
        assertFalse(it.getSourceNotes().stream().anyMatch(n -> n.contains("就近用餐")),
                "缺坐标不应误报跨区");
    }

    @Test
    void keepsRelationalDescriptionsButRemovesTrueSubjectCopies() {
        // 大理实测修复：攻略原文里"提到其他地点但只是地理参照"的句子曾被整句误删——
        // "可俯瞰大理古城""比大理古城低8-10°C""才村→磻溪村→喜洲古镇这一段最美"。
        // 新逻辑按"主语位"判定：其他地点名不占句首主语位 → 合法语境保留；
        // 真正以其他地点为主语、整句介绍该地点的抄写（三塔简介混入"大理古城是南诏都城…"）仍清理。
        DayPlan d1 = day(1);
        SpotItem cangshan = spot("苍山", "大理市大理镇");
        cangshan.setDescription("苍山十九峰连绵，可俯瞰大理古城与洱海全景。山顶气温比大理古城低8-10°C，建议带外套。");
        SpotItem langdao = spot("洱海生态廊道", "大理市银桥镇磻溪村");
        langdao.setDescription("才村→磻溪村→喜洲古镇这一段是廊道风景最好的路段，全程约46公里。骑行是最佳打开方式。");
        SpotItem santata = spot("崇圣寺三塔", "大理市三塔路");
        santata.setDescription("大理古城是南诏与大理国的都城，素有文献名邦之称。");
        d1.getSpots().addAll(List.of(cangshan, langdao, santata));

        Itinerary it = new Itinerary();
        it.setDestination("大理");
        it.setDays(List.of(d1));
        it.setSourceNotes(new ArrayList<>());

        Map<String, Object> poi = Map.of("景点", List.of(
                Map.of("name", "大理古城", "address", "大理市复兴路"),
                Map.of("name", "苍山", "address", "大理市大理镇"),
                Map.of("name", "洱海生态廊道", "address", "大理市银桥镇磻溪村"),
                Map.of("name", "崇圣寺三塔", "address", "大理市三塔路"),
                Map.of("name", "才村", "address", "大理市大理镇才村"),
                Map.of("name", "磻溪村", "address", "大理市银桥镇磻溪村"),
                Map.of("name", "喜洲古镇", "address", "大理市喜洲镇")));
        validator.validateAndRepair(it, request("大理"), collectedWithPoi(poi, null));

        // 合法地理语境句全部保留
        assertTrue(cangshan.getDescription().contains("可俯瞰大理古城"),
                "「可俯瞰大理古城」是合法视角语境，不得删");
        assertTrue(cangshan.getDescription().contains("比大理古城低"),
                "「比大理古城低8-10°C」是合法对比语境，不得删");
        assertTrue(langdao.getDescription().contains("才村→磻溪村→喜洲古镇"),
                "「才村→磻溪村→喜洲古镇这一段最美」是合法路线语境，不得删");
        // 真正的主语位抄写句仍被清理
        assertFalse(santata.getDescription().contains("大理古城"),
                "「大理古城是南诏都城…」是以其他景点为主语的整句抄写，必须清理");
        // 警示只针对真串用，不误伤保留句
        assertTrue(d1.getNotes().stream().anyMatch(n -> n.contains("崇圣寺三塔") && n.contains("疑似混入")),
                "真串用应报警示");
        assertFalse(d1.getNotes().stream().anyMatch(n -> n.contains("苍山」") || n.contains("洱海生态廊道」")),
                "合法语境景点不应被误报警示");
    }

    @Test
    void removesShopAndApartmentFromSpotsByPoiType() {
        // 三亚"火星主题"实测：集成灶门店(带高德type)与公寓被 LLM 误列为景点 →
        // 校验层按业态移除，真景点(椰梦长廊)保留
        DayPlan d1 = day(1);
        SpotItem jcz = spot("火星人集成灶(三亚河东路店)", "中恒建材城5号门五栋B16");
        jcz.setPoiType("购物服务;专卖店;家电卖场");
        SpotItem apt = spot("来自火星公寓(裕民路分店)", "裕民路");
        SpotItem dream = spot("椰梦长廊", "天涯区");
        d1.getSpots().addAll(List.of(jcz, apt, dream));

        Itinerary it = new Itinerary();
        it.setDestination("三亚");
        it.setDays(List.of(d1));
        it.setSourceNotes(new ArrayList<>());

        Map<String, Object> poi = Map.of("景点", List.of(
                Map.of("name", "椰梦长廊", "address", "天涯区"),
                Map.of("name", "鹿回头风景区", "address", "鹿岭路")));
        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(poi, null));

        assertEquals(1, d1.getSpots().size(), "商铺与公寓都应被移除，只留真景点");
        assertEquals("椰梦长廊", d1.getSpots().get(0).getName());
        assertTrue(d1.getNotes().stream().anyMatch(n -> n.contains("已移除被误列为景点的")
                        && n.contains("火星人集成灶") && n.contains("来自火星公寓")),
                "移除备注应点名两个非游览场所");
    }

    @Test
    void refillsDayAfterRemovingAllNonSightseeingSpots() {
        // 极端场景：某天 LLM 只排了商铺+公寓两个"景点"，全被移除 → 当天不能空，
        // 应从真实景点候选池自动补位（三亚 D3 实测：移除"来自火星公寓"后当天无景点）
        DayPlan d1 = day(1);
        SpotItem jcz = spot("火星人集成灶(林旺路店)", "海榆东线");
        jcz.setPoiType("购物服务;专卖店");
        d1.getSpots().add(jcz);

        Itinerary it = new Itinerary();
        it.setDestination("三亚");
        it.setDays(List.of(d1));
        it.setSourceNotes(new ArrayList<>());

        Map<String, Object> poi = Map.of("景点", List.of(
                Map.of("name", "椰梦长廊", "address", "天涯区"),
                Map.of("name", "鹿回头风景区", "address", "鹿岭路")));
        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(poi, null));

        assertEquals(1, d1.getSpots().size(), "移除后必须补位，当天不能无景点");
        String name = d1.getSpots().get(0).getName();
        assertTrue(name.equals("椰梦长廊") || name.equals("鹿回头风景区"),
                "补位的应是真实景点候选，实际补入：" + name);
        assertTrue(d1.getNotes().stream().anyMatch(n -> n.contains("已自动补入真实景点")),
                "应备注自动补位说明");
    }

    @Test
    void hotelLevelMismatchWarnsOnlyOnce() {
        // 同一住宿问题(每晚 hotel 相同)不得在 source_notes 重复刷屏(3天3条) → 只警示一次
        DayPlan d1 = day(1);
        DayPlan d2 = day(2);
        DayPlan d3 = day(3);
        for (DayPlan d : List.of(d1, d2, d3)) {
            HotelItem h = new HotelItem();
            h.setName("亿点足迹青年旅舍(三亚商品街店)");
            h.setLevel("舒适型");
            h.setEstimatedCost(320.0);
            d.setHotel(h);
            d.getSpots().add(spot("椰梦长廊", "天涯区"));
        }

        Itinerary it = new Itinerary();
        it.setDestination("三亚");
        it.setDays(List.of(d1, d2, d3));
        it.setSourceNotes(new ArrayList<>());

        Map<String, Object> poi = Map.of("景点", List.of(
                Map.of("name", "椰梦长廊", "address", "天涯区")));
        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(poi, null));

        long warns = it.getSourceNotes().stream()
                .filter(n -> n.contains("已按实际调整为经济型")).count();
        assertEquals(1, warns, "同一住宿档次问题只应警示一次");
        for (DayPlan d : List.of(d1, d2, d3)) {
            assertEquals("经济型", d.getHotel().getLevel(), "青旅/民宿必须按实际降为经济型");
        }
    }

    /* ================= 简介跨景点复用兜底（三亚实测场景） ================= */

    private static final String DESC_FALLBACK = "（该景点简介暂未匹配到真实资料，请参考官方介绍）";

    /** 三亚实测：椰梦长廊的简介被整段复制给三亚湾、海月广场 → 复用者降级兜底，首发者保留 */
    @Test
    void identicalDescriptionAcrossSpots_laterOnesFallback() {
        String sanyaDesc = "三亚最长的海岸线，椰梦长廊20多公里椰林步道，是看日落的最佳地点——傍晚整条海岸线都是橘色天空。晚上本地人散步锻炼，烟火气足。";
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("椰梦长廊", "天涯区（三亚市区西侧沿海）", sanyaDesc));
        DayPlan d2 = day(2);
        d2.getSpots().add(spot("三亚湾", "天涯区（三亚市区西侧沿海）", sanyaDesc)); // 整段复制
        DayPlan d3 = day(3);
        d3.getSpots().add(spot("海月广场", "天涯区（三亚市区西侧沿海）", sanyaDesc)); // 整段复制

        Itinerary it = new Itinerary();
        it.setDestination("三亚");
        it.setDays(List.of(d1, d2, d3));
        it.setSourceNotes(new ArrayList<>());

        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(Map.of(), null));

        assertEquals(sanyaDesc, d1.getSpots().get(0).getDescription(), "首发景点（椰梦长廊）保留原简介");
        assertEquals(DESC_FALLBACK, d2.getSpots().get(0).getDescription(), "复用者三亚湾应降级");
        assertEquals(DESC_FALLBACK, d3.getSpots().get(0).getDescription(), "复用者海月广场应降级");
        assertTrue(it.getSourceNotes().stream().anyMatch(n -> n.contains("简介") && n.contains("数据交叉校验")),
                "来源说明需汇总简介串用");
    }

    /** 标点/空白/全半角差异不影响"完全相同"判定（规范化后相同仍兜底） */
    @Test
    void descriptionSameAfterNormalization_stillFallback() {
        String a = "大东海是离市区最近的海湾，沙质平缓、游泳方便，是性价比最高的海滨。";
        String b = "大东海是离市区最近的海湾，沙质平缓、游泳方便，是性价比最高的海滨！"; // 仅句末标点差异
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("大东海旅游区", "吉阳区", a));
        DayPlan d2 = day(2);
        d2.getSpots().add(spot("小东海", "吉阳区", b));

        Itinerary it = new Itinerary();
        it.setDestination("三亚");
        it.setDays(List.of(d1, d2));
        it.setSourceNotes(new ArrayList<>());

        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(Map.of(), null));

        assertEquals(a, d1.getSpots().get(0).getDescription(), "首发保留");
        assertEquals(DESC_FALLBACK, d2.getSpots().get(0).getDescription(), "仅标点差异也视为完全相同");
    }

    /** 高相似（编辑距离 ≥0.90，长度 ≥40 字符）→ 降级 */
    @Test
    void highlySimilarDescription_fallback() {
        String a = "南山文化旅游区以108米海上观音闻名，园区依山面海、绿化极好，适合慢慢逛大半天，建议坐电瓶车节省体力。";
        String b = "南山文化旅游区以108米海上观音闻名，园区依山面海、绿化极好，适合慢慢逛大半天，建议坐电瓶车节省脚力。"; // 仅末词差异
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("南山文化旅游区", "崖州区", a));
        DayPlan d2 = day(2);
        d2.getSpots().add(spot("南山寺", "崖州区", b));

        Itinerary it = new Itinerary();
        it.setDestination("三亚");
        it.setDays(List.of(d1, d2));
        it.setSourceNotes(new ArrayList<>());

        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(Map.of(), null));

        assertEquals(DESC_FALLBACK, d2.getSpots().get(0).getDescription(), "高度相似应降级");
    }

    /** 名称互为包含（同一景点不同称呼）时简介相同不处理 */
    @Test
    void relatedNames_sameDescription_notTreatedAsReuse() {
        String desc = "惠州西湖由丰湖、平湖等组成，以苏东坡寓惠遗迹著称。";
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("惠州西湖", "环城西路", desc));
        DayPlan d2 = day(2);
        d2.getSpots().add(spot("西湖", "环城西路", desc)); // 名称互含

        Itinerary it = new Itinerary();
        it.setDestination("惠州");
        it.setDays(List.of(d1, d2));
        it.setSourceNotes(new ArrayList<>());

        validator.validateAndRepair(it, request("惠州"), collectedWithPoi(Map.of(), null));

        assertEquals(desc, d1.getSpots().get(0).getDescription(), "互含不处理");
        assertEquals(desc, d2.getSpots().get(0).getDescription(), "互含同一景点，简介相同属正常");
    }

    /** 地址各不相同，但简介重复 → 仍应触发（覆盖面独立于地址去重） */
    @Test
    void descriptionReuse_detectedEvenWhenAddressesDiffer() {
        String desc = "鹿回头山顶公园是三亚看全景与日落的最佳地点，可俯瞰三亚湾与市区，建议傍晚前往。";
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("鹿回头", "鹿岭路", desc));
        DayPlan d2 = day(2);
        d2.getSpots().add(spot("凤凰岭", "凤凰路", desc)); // 地址不同，但简介整段相同

        Itinerary it = new Itinerary();
        it.setDestination("三亚");
        it.setDays(List.of(d1, d2));
        it.setSourceNotes(new ArrayList<>());

        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(Map.of(), null));

        assertEquals(DESC_FALLBACK, d2.getSpots().get(0).getDescription(),
                "简介重复与地址是否重复无关，地址正常也应兜底");
    }

    /** 地址已"待核实"的景点：简介相似度阈值放宽（0.82 而非 0.90）也能拦截 */
    @Test
    void pendingAddress_similarDescription_looserThresholdFallback() {
        // 两段简介约 87% 相似（差异约 6/48 字符）：常规阈值 0.90 不命中，
        // 地址已"待核实"（多重异常信号）时阈值降到 0.82 → 命中降级
        String base = "蜈支洲岛是三亚最热门的离岛，海水清澈见底、沙滩细白，水上项目齐全，是潜水和玩水的好去处，岛上还适合环岛散步。";
        String variant = "蜈支洲岛是三亚最热门的离岛，海水清澈见底、沙滩洁白，水上项目丰富，是潜水与玩水的首选地，岛上还适合环岛散步。";
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("蜈支洲岛", "海棠区", base));
        DayPlan d2 = day(2);
        d2.getSpots().add(spot("西岛", "（地址待核实）", variant));

        Itinerary it = new Itinerary();
        it.setDestination("三亚");
        it.setDays(List.of(d1, d2));
        it.setSourceNotes(new ArrayList<>());

        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(Map.of(), null));

        assertEquals(DESC_FALLBACK, d2.getSpots().get(0).getDescription(), "地址待核实 + 较高相似 → 放宽阈值兜底");
    }

    /** 地理参照句（可俯瞰/途经）场景：各简介自身独立，不应被误判为复用（与主语位语义不冲突） */
    @Test
    void distinctDescriptionsWithGeoReferences_notFallback() {
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("苍山", "大理市大理镇", "苍山十九峰连绵，可俯瞰大理古城与洱海全景。山顶气温比大理古城低8-10°C，建议带外套。"));
        DayPlan d2 = day(2);
        d2.getSpots().add(spot("生态廊道", "大理市才村码头", "才村到磻溪村再到喜洲古镇这一段是廊道风景最好的路段，骑行是最佳打开方式。"));

        Itinerary it = new Itinerary();
        it.setDestination("大理");
        it.setDays(List.of(d1, d2));
        it.setSourceNotes(new ArrayList<>());

        validator.validateAndRepair(it, request("大理"), collectedWithPoi(Map.of(), null));

        assertTrue(d1.getSpots().get(0).getDescription().contains("可俯瞰大理古城"), "参照句应保留");
        assertTrue(d2.getSpots().get(0).getDescription().contains("喜洲古镇"), "参照句应保留");
        assertTrue(it.getSourceNotes().stream().noneMatch(n -> n.contains("简介")),
                "独立简介不得触发复用警告");
    }

    /** 共享通用短句（如"适合拍照"）不误报 */
    @Test
    void sharedShortSlogan_notFallback() {
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("三亚湾", "天涯区三亚湾路", "适合拍照"));
        DayPlan d2 = day(2);
        d2.getSpots().add(spot("大东海", "吉阳区榆亚路", "适合拍照"));

        Itinerary it = new Itinerary();
        it.setDestination("三亚");
        it.setDays(List.of(d1, d2));
        it.setSourceNotes(new ArrayList<>());

        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(Map.of(), null));

        assertEquals("适合拍照", d2.getSpots().get(0).getDescription(), "通用短句不得降级");
    }

    /** 大景区内多个子景点共享区域背景（都提及"位于XX沿海"）但不雷同 → 不误伤 */
    @Test
    void geoSubSpots_sharedAreaBackground_notFallback() {
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("三亚湾", "天涯区三亚湾路", "三亚湾位于三亚市区西南沿海，椰林成带，是傍晚散步看晚霞的好地方。"));
        DayPlan d2 = day(2);
        d2.getSpots().add(spot("海月广场", "天涯区解放路", "海月广场位于三亚湾畔，广场开阔、近海，本地人跳舞健身，生活气息浓。"));
        DayPlan d3 = day(3);
        d3.getSpots().add(spot("椰梦长廊", "天涯区滨海路", "椰梦长廊是沿三亚湾而建的海滨步道，绵延二十余公里，可骑行可漫步。"));

        Itinerary it = new Itinerary();
        it.setDestination("三亚");
        it.setDays(List.of(d1, d2, d3));
        it.setSourceNotes(new ArrayList<>());

        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(Map.of(), null));

        assertTrue(d1.getSpots().get(0).getDescription().contains("晚霞"), "子景点独立简介保留");
        assertTrue(d2.getSpots().get(0).getDescription().contains("广场开阔"), "子景点独立简介保留");
        assertTrue(d3.getSpots().get(0).getDescription().contains("骑行"), "子景点独立简介保留");
        assertTrue(it.getSourceNotes().stream().noneMatch(n -> n.contains("简介")),
                "共享区域背景但简介不同 → 不触发简介串用警告");
    }

    /* ================= 生成后一致性修复（2026-09-18 三亚案例评审） =================
     * 共同根因两条：① 数据被改了、引用它的文案/行没跟着改；② 约束只在候选排序阶段生效，
     * 生成后没人校验结构是否闭合。下面按这两条各覆盖一组。
     * ========================================================================= */

    private Itinerary itineraryOf(DayPlan day, String dest) {
        Itinerary it = new Itinerary();
        it.setDestination(dest);
        it.setDays(List.of(day));
        it.setSourceNotes(new ArrayList<>());
        return it;
    }

    private CandidateEvidence evidence(String name, int visited) {
        CandidateEvidence ev = new CandidateEvidence();
        ev.setItemName(name);
        ev.setBucket("景点");
        ev.setVisited(visited);
        return ev;
    }

    private HotelItem hotel(String name, String level, double cost) {
        HotelItem h = new HotelItem();
        h.setName(name);
        h.setLevel(level);
        h.setEstimatedCost(cost);
        return h;
    }

    /** ① 交通段端点与当天行程毫无关系（"幽灵餐厅"当端点）→ 删段 + 如实说明 */
    @Test
    void transportLegWithForeignEndpoints_droppedWithHonestNote() {
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("椰梦长廊", "天涯区（三亚市区西侧沿海）"));
        TransportItem phantom = new TransportItem();
        phantom.setMode("打车");
        phantom.setFromPlace("城市乐园");
        phantom.setToPlace("绿茶餐厅(三亚蓝海购物广场店)");
        d1.getTransport().add(phantom);

        Itinerary it = itineraryOf(d1, "三亚");
        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(Map.of(), null));

        assertTrue(d1.getTransport() == null || d1.getTransport().isEmpty(),
                "端点不在当天行程里的交通段必须被移除，不能展示不存在的路线");
        assertTrue(d1.getNotes() != null && d1.getNotes().stream()
                        .anyMatch(n -> n.contains("与当天行程不符的交通段")),
                "移除要如实说明，不能静默消失");
    }

    /** ① 端点写得不准但明显指向当天某地点（相似度够）→ 改写成当天真实名称，不删段 */
    @Test
    void transportLegWithSimilarAnchor_rewrittenToRealPlace() {
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("亚龙湾热带天堂森林公园", "亚龙湾路"));
        d1.setMeals(new ArrayList<>(List.of(meal("大东海海鲜广场"))));
        TransportItem t = new TransportItem();
        t.setMode("打车");
        t.setFromPlace("亚龙湾天堂森林公园"); // 名称变体：不相等，但明显是同一个地方
        t.setToPlace("大东海");                // 与「大东海海鲜广场」名称互含 → 视为同一地点，无需改写
        d1.getTransport().add(t);

        Itinerary it = itineraryOf(d1, "三亚");
        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(Map.of(), null));

        assertEquals(1, d1.getTransport().size(), "能对上当天地点的交通段不该被删");
        assertEquals("亚龙湾热带天堂森林公园", t.getFromPlace(), "名称变体应改写成当天真实名称");
        assertEquals("大东海", t.getToPlace(), "名称互含即视为同一地点，不硬改");
    }

    /** ② 结构缺口：当天漏排午餐/晚餐 → 用候选池里的真实餐厅补位；返程日不补晚餐 */
    @Test
    void missingMeals_filledFromCandidates() {
        DayPlan d1 = day(1);
        SpotItem s1 = spot("亚龙湾", "亚龙湾路");
        s1.setLatitude(18.22);
        s1.setLongitude(109.63);
        d1.getSpots().add(s1);

        DayPlan d2 = day(2);
        SpotItem s2 = spot("天涯海角", "天涯区");
        s2.setLatitude(18.29);
        s2.setLongitude(109.34);
        d2.getSpots().add(s2);

        Itinerary it = new Itinerary();
        it.setDestination("三亚");
        it.setDays(List.of(d1, d2));
        it.setSourceNotes(new ArrayList<>());

        Map<String, Object> restaurants = Map.of("餐厅", List.of(
                poiWithCoord("亚龙湾海鲜广场", 18.23, 109.64),
                poiWithCoord("天涯镇海鲜店", 18.30, 109.35),
                poiWithCoord("天涯海角渔村餐厅", 18.30, 109.36)));
        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(restaurants, null));

        assertEquals(2, d1.getMeals().size(), "第 1 天午餐与晚餐都应补上");
        assertTrue(d1.getMeals().stream().anyMatch(m -> "午餐".equals(m.getMealType())),
                "缺午餐应补午餐");
        assertEquals(1, d2.getMeals().size(), "返程日只补午餐，不补晚餐");
        assertEquals("午餐", d2.getMeals().get(0).getMealType());
        assertTrue(it.getSourceNotes().stream().anyMatch(n -> n.contains("已补入")),
                "补位动作要在来源说明里可追溯");
    }

    /** 假期中的最后一天只缺午餐 → 只补午餐（不因为"没晚餐"误补） */
    @Test
    void lastDayWithoutDinner_notFilled() {
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("椰梦长廊", "天涯区"));

        Itinerary it = itineraryOf(d1, "三亚");
        Map<String, Object> restaurants = Map.of("餐厅", List.of(poiWithCoord("三亚湾海鲜广场", 18.25, 109.50)));
        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(restaurants, null));

        assertEquals(1, d1.getMeals().size(), "最后一天只补午餐");
        assertEquals("午餐", d1.getMeals().get(0).getMealType());
    }

    /** ② 历史去过的景点要"不安排"：候选池里还有没去过的新景点 → 直接换掉 */
    @Test
    void visitedSpot_replacedByUnvisitedCandidate() {
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("鹿回头风景区", "鹿岭路"));

        Itinerary it = itineraryOf(d1, "三亚");
        CollectedData c = collectedWithPoi(Map.of("景点", List.of(
                Map.of("name", "鹿回头风景区", "address", "鹿岭路"),
                Map.of("name", "椰梦长廊", "address", "天涯区"))), null);
        c.setCandidateEvidence(List.of(evidence("鹿回头风景区", 1)));

        validator.validateAndRepair(it, request("三亚"), c);

        assertEquals("椰梦长廊", d1.getSpots().get(0).getName(),
                "历史去过的景点应换成候选池里没去过的新景点");
        assertTrue(it.getSourceNotes().stream().anyMatch(n -> n.contains("历史去过") && n.contains("换成新地点")),
                "换掉的动作要可解释，而不是静默替换");
    }

    /** ② 用户点名的景点优先于历史去重：保留 + 讲清原因 */
    @Test
    void visitedButRequestedSpot_keptWithExplanation() {
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("鹿回头风景区", "鹿岭路"));

        Itinerary it = itineraryOf(d1, "三亚");
        CollectedData c = collectedWithPoi(Map.of("景点", List.of(
                Map.of("name", "鹿回头风景区", "address", "鹿岭路"),
                Map.of("name", "椰梦长廊", "address", "天涯区"))), null);
        c.setCandidateEvidence(List.of(evidence("鹿回头风景区", 1)));
        c.setRequestedSpots(new ArrayList<>(List.of("鹿回头风景区")));

        validator.validateAndRepair(it, request("三亚"), c);

        assertEquals("鹿回头风景区", d1.getSpots().get(0).getName(),
                "点名优先：不得被历史去重规则换掉");
        assertTrue(it.getSourceNotes().stream().anyMatch(n -> n.contains("是你点名的景点")),
                "保留原因要讲清，避免看起来像「规则时灵时不灵」");
    }

    /** ② 历史去过但无新景点可换 → 保留 + 如实说明（不粉饰成"已避开重复"） */
    @Test
    void visitedSpotWithoutReplacement_keptWithHonestNote() {
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("鹿回头风景区", "鹿岭路"));

        Itinerary it = itineraryOf(d1, "三亚");
        CollectedData c = collectedWithPoi(Map.of(), null);
        c.setCandidateEvidence(List.of(evidence("鹿回头风景区", 1)));

        validator.validateAndRepair(it, request("三亚"), c);

        assertEquals("鹿回头风景区", d1.getSpots().get(0).getName(), "无候选时不得凭空空换");
        assertTrue(d1.getNotes().stream().anyMatch(n -> n.contains("已无新的真实景点可替换")),
                "换不掉要如实说明原因，不静默粉饰");
    }

    /** ① 餐次被换掉后，引用旧店名的交通段端点必须一起改（"幽灵餐厅"的正解） */
    @Test
    void mealReplacement_propagatesToTransportEndpoints() {
        MealItem lunch1 = meal("人人捞火锅(商品街店)");
        lunch1.setSource("高德POI");
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("椰梦长廊", "天涯区"));
        d1.setMeals(new ArrayList<>(List.of(lunch1)));

        MealItem lunch2 = meal("人人捞火锅(商品街店)");
        lunch2.setSource("高德POI");
        DayPlan d2 = day(2);
        d2.getSpots().add(spot("鹿回头风景区", "鹿岭路"));
        d2.setMeals(new ArrayList<>(List.of(lunch2)));
        TransportItem leg = new TransportItem();
        leg.setMode("打车");
        leg.setFromPlace("人人捞火锅(商品街店)");
        leg.setToPlace("鹿回头风景区");
        d2.getTransport().add(leg);

        Itinerary it = new Itinerary();
        it.setDestination("三亚");
        it.setDays(List.of(d1, d2));
        it.setSourceNotes(new ArrayList<>());

        Map<String, Object> restaurants = Map.of("餐厅", List.of(
                poiNoCoord("人人捞火锅(商品街店)"),
                poiNoCoord("朋派烤肉自助料理(蓝海购物广场店)")));
        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(restaurants, null));

        assertNotEquals("人人捞火锅(商品街店)", d2.getMeals().get(0).getName(),
                "跨天重复的餐厅应被换掉");
        assertEquals(d2.getMeals().get(0).getName(), leg.getFromPlace(),
                "交通段端点必须跟着改名——不能留下「当了端点却从不是任何一餐」的幽灵餐厅");
        assertEquals(1, d2.getTransport().size(), "端点改名后不再落空，交通段应保留而不是被删");
    }

    /** ① 住宿口径收口：LLM 写的过期金额/档次断言清掉，改由系统按最终数据重述一条 */
    @Test
    void staleLodgingClaim_removedAndRestatedFromRealData() {
        TripRequest req = request("三亚");
        req.setBudget(8000.0);

        DayPlan d1 = day(1);
        d1.getSpots().add(spot("椰梦长廊", "天涯区"));
        d1.setHotel(hotel("三亚湾海景假日酒店", "舒适型", 320.0));

        Itinerary it = itineraryOf(d1, "三亚");
        it.setTips(new ArrayList<>(List.of(
                "住宿建议：选择高档型酒店，约 780 元/晚，4 晚共 3120 元（占预算 39%）",
                "防晒：三亚紫外线强，正午尽量待在室内")));

        validator.validateAndRepair(it, req, collectedWithPoi(Map.of(), null));

        assertTrue(it.getTips().stream().noneMatch(t -> t.contains("780")),
                "与最终数据矛盾的住宿断言必须移除（同一页面不能有两套数字）");
        assertTrue(it.getTips().stream().anyMatch(t -> t.startsWith("住宿：")
                        && t.contains("三亚湾海景假日酒店") && t.contains("320")),
                "改由系统按最终数据重述一条住宿事实");
        assertTrue(it.getTips().stream().anyMatch(t -> t.contains("防晒")),
                "与住宿无关的正常提示不得误删");
    }

    /** ① 系统住宿事实行必须幂等：同一行程被校验两次也只留一条 */
    @Test
    void systemLodgingTip_notDuplicatedOnRepeatValidation() {
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("椰梦长廊", "天涯区"));
        d1.setHotel(hotel("三亚湾海景假日酒店", "舒适型", 320.0));

        Itinerary it = itineraryOf(d1, "三亚");
        CollectedData c = collectedWithPoi(Map.of(), null);

        validator.validateAndRepair(it, request("三亚"), c);
        validator.validateAndRepair(it, request("三亚"), c);

        long n = it.getTips() == null ? 0 : it.getTips().stream().filter(t -> t.startsWith("住宿：")).count();
        assertEquals(1, n, "系统住宿事实行不能每校验一次就追加一条");
    }

    /* ================= 生成后结构对齐 / 档次落地 / 摘要口径 / 预算诚实说明 =================
     * 对应 GENERATION_QUALITY_PLAN.md 第一批修复 A/B/C/D，逐一锁死回归。
     * ========================================================================= */

    /** 构造带出行日期跨度的请求（syncStructure 依赖日期算期望天数） */
    private TripRequest datedRequest(String dest, String start, String end) {
        TripRequest r = request(dest);
        r.setStartDate(java.time.LocalDate.parse(start));
        r.setEndDate(java.time.LocalDate.parse(end));
        return r;
    }

    /** A：请求 5 天，模型只输出 3 天 → 天数补齐到 5，day_index 连续，trip_days=5 */
    @Test
    void syncStructure_padsMissingDaysToMatchDateSpan() {
        TripRequest req = datedRequest("三亚", "2026-09-18", "2026-09-22"); // 5 天
        req.setBudget(8000.0);

        DayPlan d1 = day(1);
        d1.setDate("2026-09-18");
        d1.getSpots().add(spot("椰梦长廊", "天涯区"));
        d1.setHotel(hotel("三亚湾海景假日酒店", "舒适型", 320.0));
        DayPlan d2 = day(2);
        d2.setDate("2026-09-19");
        d2.getSpots().add(spot("鹿回头风景区", "鹿岭路"));
        d2.setHotel(hotel("三亚湾海景假日酒店", "舒适型", 320.0));
        DayPlan d3 = day(3);
        d3.setDate("2026-09-20");
        d3.getSpots().add(spot("大东海旅游区", "吉阳区"));
        d3.setHotel(hotel("三亚湾海景假日酒店", "舒适型", 0.0)); // 末晚退房不计费

        Itinerary it = new Itinerary();
        it.setDestination("三亚");
        it.setDays(new ArrayList<>(List.of(d1, d2, d3)));
        it.setSourceNotes(new ArrayList<>());

        Map<String, Object> poi = Map.of("景点", List.of(
                Map.of("name", "蜈支洲岛", "address", "海棠区"),
                Map.of("name", "天涯海角", "address", "天涯区"),
                Map.of("name", "南山文化旅游区", "address", "崖州区")));
        validator.validateAndRepair(it, req, collectedWithPoi(poi, null));

        assertEquals(5, it.getDays().size(), "请求 5 天必须补齐到 5 天");
        assertEquals(5, it.getTripDays(), "trip_days 必须与真实天数一致，不能再「页面 5 天正文 3 天」");
        for (int i = 0; i < 5; i++) {
            assertEquals(i + 1, it.getDays().get(i).getDayIndex(), "day_index 必须 1..5 连续");
        }
        // 补齐的两天应从候选池拿到真实景点（完整日 ≥2 下限）
        assertTrue(it.getDays().get(3).getSpots().size() >= 2, "补齐的完整日应有 ≥2 景点");
    }

    /** A：请求天数少于模型输出 → 截断多余天数 */
    @Test
    void syncStructure_truncatesExtraDays() {
        TripRequest req = datedRequest("三亚", "2026-09-18", "2026-09-19"); // 2 天
        DayPlan d1 = day(1);
        d1.setDate("2026-09-18");
        d1.getSpots().add(spot("椰梦长廊", "天涯区"));
        DayPlan d2 = day(2);
        d2.setDate("2026-09-19");
        d2.getSpots().add(spot("鹿回头风景区", "鹿岭路"));
        DayPlan d3 = day(3);
        d3.setDate("2026-09-20");
        d3.getSpots().add(spot("大东海旅游区", "吉阳区"));

        Itinerary it = itineraryOf(d1, "三亚");
        it.setDays(new ArrayList<>(List.of(d1, d2, d3)));

        validator.validateAndRepair(it, req, collectedWithPoi(Map.of(), null));

        assertEquals(2, it.getDays().size(), "请求 2 天应截断多余的 1 天");
        assertEquals(2, it.getTripDays());
    }

    /** B：用户选高档型，LLM 拿经济型公寓交差 → 换候选池里的真实酒店并配高档价 */
    @Test
    void upgradeHotelTier_replacesEconomyApartmentWithRealHotel() {
        TripRequest req = request("三亚");
        req.setHotelLevel("高档型");
        req.setBudget(8000.0);

        DayPlan d1 = day(1);
        d1.getSpots().add(spot("椰梦长廊", "天涯区"));
        d1.setHotel(hotel("三亚湾海忆时光海景公寓", "经济型", 200.0));

        Itinerary it = itineraryOf(d1, "三亚");
        Map<String, Object> poi = Map.of("酒店", List.of(
                Map.of("name", "三亚湾海景度假酒店", "address", "三亚湾路"),
                Map.of("name", "三亚某青年旅舍", "address", "市区")));
        validator.validateAndRepair(it, req, collectedWithPoi(poi, null));

        assertEquals("三亚湾海景度假酒店", d1.getHotel().getName(),
                "高档型不能拿经济公寓交差，应换成候选池里的真实酒店");
        assertEquals("高档型", d1.getHotel().getLevel(), "档次应落到用户所选的高档型");
        assertTrue(d1.getHotel().getEstimatedCost() >= 400, "高档酒店价应在高档区间（≥400）");
        assertFalse(d1.getHotel().getName().contains("公寓"), "不应再是公寓");
    }

    /** C：摘要写了「5 天·精选高档酒店」，正文实为 3 天经济型 → 清除过期断言并校正天数 */
    @Test
    void cleanStaleSummaryClaims_scrubsStaleLodgingAndDayCount() {
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("椰梦长廊", "天涯区"));
        d1.setHotel(hotel("三亚湾海景假日酒店", "经济型", 200.0));

        Itinerary it = itineraryOf(d1, "三亚");
        it.setSummary("5天三亚深度游，精选高档酒店，畅游椰风海韵与热带风情。");

        validator.validateAndRepair(it, request("三亚"), collectedWithPoi(Map.of(), null));

        assertFalse(it.getSummary().contains("高档酒店"),
                "摘要里与正文矛盾的住宿档次断言必须清除");
        assertFalse(it.getSummary().contains("5天"),
                "摘要里的天数必须校正为真实天数（1 天）");
    }

    /** D：预算只用 16% → 加「预算没花出去」的诚实说明，而不是静默 */
    @Test
    void checkBudgetUnderuse_addsHonestNote() {
        TripRequest req = request("三亚");
        req.setBudget(8000.0);

        DayPlan d1 = day(1);
        d1.getSpots().add(spot("椰梦长廊", "天涯区"));
        d1.setHotel(hotel("三亚湾海景假日酒店", "经济型", 200.0));

        Itinerary it = itineraryOf(d1, "三亚");
        validator.validateAndRepair(it, req, collectedWithPoi(Map.of(), null));

        assertTrue(it.getSourceNotes().stream().anyMatch(n -> n.contains("占您预算")),
                "预算严重没用满时必须诚实说明，不能一声不吭让用户以为价格在搞笑");
    }

    /* ================= 生成观感兜底（2026-09-18 上海随机行程评审） =================
     * 用户标准："模拟真实旅行规划需求，至少一眼看上去问题不大"。
     * ========================================================================= */

    private TransportItem leg(String from, String to) {
        TransportItem t = new TransportItem();
        t.setMode("地铁");
        t.setFromPlace(from);
        t.setToPlace(to);
        return t;
    }

    /** ⑤ 自环交通段（酒店→酒店）必须删除；剩余段按当天活动时间轴排序（时间不倒流） */
    @Test
    void selfLoopLegDropped_andLegsOrderedByTimeline() {
        DayPlan d1 = day(1);
        SpotItem morning = spot("上海四行仓库抗战纪念馆", "光复路21号");
        morning.setStartTime("09:30");
        morning.setEndTime("12:00");
        SpotItem afternoon = spot("上海城隍庙", "方浜中路249号");
        afternoon.setStartTime("14:00");
        afternoon.setEndTime("15:30");
        d1.getSpots().addAll(List.of(morning, afternoon));
        MealItem lunch = meal("巴奴毛肚火锅(人广来福士店)");
        lunch.setStartTime("12:45");
        d1.setMeals(new ArrayList<>(List.of(lunch)));
        d1.setHotel(hotel("开蔓酒店(上海延吉中路地铁站店)", "舒适型", 320.0));

        String h = "开蔓酒店(上海延吉中路地铁站店)";
        // 输入顺序故意错乱：自环段、时间倒流的往返对
        d1.setTransport(new ArrayList<>(List.of(
                leg(h, h),                  // 自环废段
                leg("上海城隍庙", "巴奴毛肚火锅(人广来福士店)"),   // 14:00 出发 → 应在午餐段之后
                leg(h, "上海城隍庙"),        // 酒店出发 → 应最前
                leg("上海四行仓库抗战纪念馆", "巴奴毛肚火锅(人广来福士店)"), // 09:30 出发
                leg("巴奴毛肚火锅(人广来福士店)", "上海城隍庙"),   // 12:45 出发
                leg("上海城隍庙", h))));     // 返回酒店

        Itinerary it = itineraryOf(d1, "上海");
        validator.validateAndRepair(it, request("上海"), collectedWithPoi(Map.of(), null));

        List<TransportItem> legs = d1.getTransport();
        assertEquals(5, legs.size(), "自环段应被删除，其余 5 段保留");
        assertTrue(legs.stream().noneMatch(t -> t.getFromPlace().equals(t.getToPlace())),
                "不得残留起终点相同的自环段");
        assertEquals(h, legs.get(0).getFromPlace(), "酒店出发段应排最前");
        assertEquals("上海城隍庙", legs.get(0).getToPlace());
        assertEquals("上海四行仓库抗战纪念馆", legs.get(1).getFromPlace(), "09:30 的段排第二");
        assertEquals("巴奴毛肚火锅(人广来福士店)", legs.get(2).getFromPlace(), "12:45 午餐出发段排第三");
        assertEquals("上海城隍庙", legs.get(3).getFromPlace(), "14:00 的段排第四（时间不倒流）");
        assertEquals(h, legs.get(4).getToPlace(), "返回酒店段排最后");
        assertTrue(d1.getNotes().stream().anyMatch(n -> n.contains("起终点相同")),
                "删除自环段要如实说明");
    }

    /** ⑤ 无时间的补位景点必须有默认档期（否则前端归入"其他安排"，当天看起来是空的）+ 空简介兜底 */
    @Test
    void untimedRefilledSpotGetsDefaultSlotAndDescription() {
        DayPlan d1 = day(1);
        SpotItem museum = spot("上海市历史博物馆", "南京西路325号"); // 补位景点：无时间无简介
        d1.getSpots().add(museum);
        MealItem lunch = meal("楼下本帮面馆");
        lunch.setStartTime("11:30");
        d1.setMeals(new ArrayList<>(List.of(lunch)));

        Itinerary it = itineraryOf(d1, "上海");
        validator.validateAndRepair(it, request("上海"), collectedWithPoi(Map.of(), null));

        assertNotNull(museum.getStartTime(), "补位景点必须有时间，否则第 3 天只剩一顿饭的空洞观感");
        assertNotNull(museum.getEndTime(), "结束时间也要补齐（前端按 start-end 展示）");
        assertNotNull(museum.getDescription());
        assertFalse(museum.getDescription().isBlank(), "空简介必须给兜底文案，不能光秃秃'暂无说明'");
        // 与午餐（11:30 起，按 90 分钟占位到 13:00）不重叠：要么 11:30 前结束，要么 13:00 后开始
        boolean beforeLunch = museum.getEndTime().compareTo("11:30") <= 0;
        boolean afterLunch = museum.getStartTime().compareTo("13:00") >= 0;
        assertTrue(beforeLunch || afterLunch, "补的档期不得与已有活动重叠，实际 " + museum.getStartTime() + "-" + museum.getEndTime());
    }

    /** ⑤ 全国连锁正餐/火锅品牌（海底捞等）应被本地候选替换——连锁不同分店按名称去重拦不住 */
    @Test
    void nationalChainRestaurant_replacedByLocalCandidate() {
        DayPlan d1 = day(1);
        d1.getSpots().add(spot("外滩", "中山东一路"));
        d1.setMeals(new ArrayList<>(List.of(meal("海底捞火锅(外滩店)"))));

        Itinerary it = itineraryOf(d1, "上海");
        Map<String, Object> restaurants = Map.of("餐厅", List.of(
                poiNoCoord("海底捞火锅(外滩店)"),
                poiNoCoord("老正兴菜馆(福州路店)")));
        validator.validateAndRepair(it, request("上海"), collectedWithPoi(restaurants, null));

        assertEquals("老正兴菜馆(福州路店)", d1.getMeals().get(0).getName(),
                "全国连锁（海底捞等）应被候选池里的本地餐厅替换");
    }
}
