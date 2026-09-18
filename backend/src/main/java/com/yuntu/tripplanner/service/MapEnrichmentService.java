package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.client.AmapClient;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.HotelItem;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.SpotItem;
import com.yuntu.tripplanner.model.TransportItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 地图数据补全服务
 *
 * 在 LLM 生成行程后，为每个景点/酒店调用高德 POI 搜索，
 * 补全图片 URL、经纬度、POI ID 和详细地址，供前端地图展示；
 * 为交通项调用高德路线 API，用真实时长/距离覆盖 LLM 编造的数据。
 * 各地点补全互不依赖，放入专用线程池并行执行（15 秒总超时），
 * 不再串行阻塞主请求线程。
 */
@Slf4j
@Service
public class MapEnrichmentService {

    private final AmapClient amapClient;
    private final Executor toolExecutor;

    /** 地图补全总超时（秒） */
    private static final int ENRICH_TIMEOUT_SECONDS = 15;

    /** 模糊地点表述（交通补全时跳过，无法可靠地理编码） */
    private static final List<String> VAGUE_PLACE_WORDS =
            List.of("出发点", "起点", "终点", "市区", "市中心", "酒店附近", "附近", "市中心区");

    public MapEnrichmentService(AmapClient amapClient,
                                @Qualifier("toolExecutor") Executor toolExecutor) {
        this.amapClient = amapClient;
        this.toolExecutor = toolExecutor;
    }

    /**
     * 补全行程的地图信息（图片、坐标、地址）
     */
    public Itinerary enrich(Itinerary itinerary) {
        if (itinerary == null || itinerary.getDays() == null) {
            return itinerary;
        }

        String city = itinerary.getDestination();
        AtomicInteger enriched = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (var day : itinerary.getDays()) {
            if (day.getSpots() != null) {
                for (SpotItem spot : day.getSpots()) {
                    // 不同 spot 是独立对象，可安全跨线程写入
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            if (enrichSpot(spot, city)) {
                                enriched.incrementAndGet();
                            }
                        } catch (Exception e) {
                            log.debug("补全景点失败: {} - {}", spot.getName(), e.getMessage());
                        }
                    }, toolExecutor));
                }
            }

            if (day.getHotel() != null) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        if (enrichHotel(day.getHotel(), city)) {
                            enriched.incrementAndGet();
                        }
                    } catch (Exception e) {
                        log.debug("补全酒店失败: {}", e.getMessage());
                    }
                }, toolExecutor));
            }
            // 交通补全：LLM 生成的起终点若为明确地点名，调用高德路线 API 覆盖真实时长/距离；
            // 起终点模糊（如"出发点""市区"）无法可靠地理编码时跳过，保留 LLM 文本并标为估算
            if (day.getTransport() != null && !day.getTransport().isEmpty()) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        enrichTransport(day);
                    } catch (Exception e) {
                        log.debug("补全交通失败: {}", e.getMessage());
                    }
                }, toolExecutor));
            }
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(ENRICH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("地图补全被中断");
        } catch (Exception e) {
            log.warn("部分地图补全超时（{}s），基于已有结果继续", ENRICH_TIMEOUT_SECONDS);
        }

        if (enriched.get() > 0 && itinerary.getSourceNotes() != null) {
            itinerary.getSourceNotes().add("已补充高德地图地址、坐标或路线估算信息");
        }
        return itinerary;
    }

    /**
     * 二次补全（只处理缺失项）：校验层的补位/替换发生在主补全之后，新换进来的景点
     * 还没拿到地址/坐标/图片（实测上海第 3 天补位的博物馆无图无坐标、悬在"其他安排"）。
     *
     * <p>只对 <b>缺图片或缺坐标</b> 的景点补全，其余跳过，避免对全量景点重复打 POI 接口；
     * 有坐标仍无照片时由 {@link #enrichSpot} 内部的静态地图快照兜底。
     * 不追加来源说明（主补全已加过同款，重复加会刷屏）。
     *
     * @return 本次实际补全成功的景点数（日志/排查用）
     */
    public int enrichMissing(Itinerary itinerary) {
        if (itinerary == null || itinerary.getDays() == null || itinerary.getDestination() == null) {
            return 0;
        }
        int n = 0;
        for (DayPlan day : itinerary.getDays()) {
            if (day.getSpots() == null) {
                continue;
            }
            for (SpotItem spot : day.getSpots()) {
                boolean missing = spot.getName() != null && !spot.getName().isBlank()
                        && ((spot.getImageUrl() == null || spot.getImageUrl().isBlank())
                        || spot.getLatitude() == null || spot.getLongitude() == null);
                if (!missing) {
                    continue;
                }
                try {
                    if (enrichSpot(spot, itinerary.getDestination())) {
                        n++;
                    }
                } catch (Exception e) {
                    log.debug("二次补全景点失败: {} - {}", spot.getName(), e.getMessage());
                }
            }
        }
        if (n > 0) {
            log.info("二次补全：{} 个补位景点拿到地址/坐标/图片", n);
        }
        return n;
    }

    private boolean enrichSpot(SpotItem spot, String city) {
        if (spot.getName() == null || spot.getName().isEmpty()) {
            return false;
        }
        boolean updated = false;
        Map<String, Object> place = pickBestPlace(spot.getName(), city);
        if (place != null) {
            // 地址强制用高德真实地址覆盖（LLM 可能编造地址，如把 POI 介绍文字当地址）；高德地址为空时保留原值
            String amapAddress = (String) place.getOrDefault("address", "");
            if (amapAddress != null && !amapAddress.isBlank()) {
                spot.setAddress(amapAddress);
                updated = true;
            }
            // 图片：只有高德 POI 真的返回了照片才采用（空串/缺失都视为没图，允许后续兜底）
            if (spot.getImageUrl() == null || spot.getImageUrl().isBlank()) {
                Object img = place.get("image_url");
                if (img != null && !img.toString().isBlank()) {
                    spot.setImageUrl(img.toString());
                    updated = true;
                }
            }
            if (spot.getLatitude() == null) {
                spot.setLatitude((Double) place.getOrDefault("latitude", null));
                updated = true;
            }
            if (spot.getLongitude() == null) {
                spot.setLongitude((Double) place.getOrDefault("longitude", null));
                updated = true;
            }
            if (spot.getPoiId() == null) {
                spot.setPoiId((String) place.getOrDefault("poi_id", ""));
                updated = true;
            }
            // 高德业态 type 写入景点（供校验层判定"商铺/公寓等非游览场所不得当景点"）
            if (spot.getPoiType() == null) {
                Object poiType = place.get("type");
                if (poiType != null && !poiType.toString().isBlank()) {
                    spot.setPoiType(poiType.toString());
                    updated = true;
                }
            }
        }
        // 图片兜底：没有真实照片但有坐标 → 高德静态地图快照（真实位置+标注点，绝不张冠李戴）。
        // 高德 v3 photos 覆盖率极低（实测上海 5 景点只有东方明珠有照片），没有这层兜底
        // 结果页大部分卡片是"暂无图片"空块，观感上像数据坏了。
        if ((spot.getImageUrl() == null || spot.getImageUrl().isBlank())
                && spot.getLatitude() != null && spot.getLongitude() != null) {
            spot.setImageUrl(amapClient.staticMapUrl(spot.getLongitude(), spot.getLatitude()));
            updated = true;
        }
        return updated;
    }

    private boolean enrichHotel(HotelItem hotel, String city) {
        if (hotel.getName() == null || hotel.getName().isEmpty()) {
            return false;
        }
        Map<String, Object> place = pickBestPlace(hotel.getName(), city);
        if (place == null) {
            return false;
        }
        // 酒店地址同样用高德真实地址覆盖（LLM 可能编造地址）
        String amapHotelAddress = (String) place.getOrDefault("address", "");
        if (amapHotelAddress != null && !amapHotelAddress.isBlank()) {
            hotel.setAddress(amapHotelAddress);
        }
        // 酒店经纬度同样补全（供校验层判断"酒店是否离景点过远"，如惠州实测住海边却玩城区）
        Double lat = (Double) place.get("latitude");
        Double lng = (Double) place.get("longitude");
        if (lat != null && lng != null) {
            hotel.setLatitude(lat);
            hotel.setLongitude(lng);
        }
        return true;
    }

    /**
     * 交通补全：对起终点均为明确地点的交通项，调高德路线 API 覆盖真实时长/距离。
     * 高德驾车路线返回的 tolls 是过路费而非打车费，故不冒充总费用（置空，避免 LLM 编的数字误导）。
     */
    private void enrichTransport(DayPlan day) {
        if (day.getTransport() == null) {
            return;
        }
        for (TransportItem t : day.getTransport()) {
            if (t.getFromPlace() == null || t.getToPlace() == null
                    || isVague(t.getFromPlace()) || isVague(t.getToPlace())) {
                continue;
            }
            // 只用驾车路线覆盖"用车类"交通段（驾车/打车/出租/网约车/未标注）。
            // 地铁/公交/步行/骑行的时间与驾车差异大，覆盖后会出现
            // "mode=地铁、来源=高德路线估算（驾车）"的自相矛盾（上海实测：地铁段被填驾车 23 分钟）。
            if (!isCarMode(t.getMode())) {
                continue;
            }
            if (t.getMode() == null || t.getMode().isBlank()) {
                t.setMode("驾车");
            }
            Map<String, Double> origin = amapClient.geocode(t.getFromPlace());
            Map<String, Double> dest = amapClient.geocode(t.getToPlace());
            if (origin == null || dest == null) {
                continue;
            }
            String o = origin.get("longitude") + "," + origin.get("latitude");
            String d = dest.get("longitude") + "," + dest.get("latitude");
            Map<String, Object> route = amapClient.getDrivingRoute(o, d);
            if (route == null) {
                continue;
            }
            Integer mins = (Integer) route.get("duration");
            Double dist = (Double) route.get("distance");
            // 合理性校验：城市内交通时长 0<x<=180 分钟、距离 <=100km，否则视为接口异常值（如绕行/高速），不采用
            if ((mins != null && (mins <= 0 || mins > 180))
                    || (dist != null && dist > 100)) {
                continue;
            }
            if (mins != null) {
                t.setEstimatedMinutes(mins);
                t.setDuration("约" + mins + "分钟");
            }
            if (dist != null) {
                t.setDistanceKm(Math.round(dist * 10.0) / 10.0);
            }
            t.setEstimatedCost(null);
            t.setSource("高德路线估算（驾车）");
        }
    }

    /** 起终点是否模糊（无法可靠地理编码的地点表述） */
    private boolean isVague(String s) {
        if (s == null || s.isBlank()) {
            return true;
        }
        return VAGUE_PLACE_WORDS.stream().anyMatch(s::contains);
    }

    /** 是否适合用驾车路线估算的交通方式（地铁/公交/步行/骑行/轨道交通的时间模型与驾车完全不同） */
    private boolean isCarMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return true; // 未标注 → 默认按驾车估算
        }
        String m = mode.trim();
        return !(m.contains("地铁") || m.contains("公交") || m.contains("步行") || m.contains("骑行")
                || m.contains("单车") || m.contains("高铁") || m.contains("动车") || m.contains("火车"));
    }

    /**
     * 优先选择名称匹配且带图片的 POI。
     * ⚠️ 高德模糊匹配可能返回名称完全不沾边的 POI（导致图片/地址张冠李戴，
     * 实测「东坡祠」拿到西湖景区的图、「大云寺」拿到书法图）。
     * 因此只采用 POI 名与景点名有包含关系的；名称不匹配 → 返回 null（放弃补全，宁缺毋错）。
     */
    private Map<String, Object> pickBestPlace(String keyword, String city) {
        List<Map<String, Object>> results = amapClient.searchPoi(city, keyword);
        if (results == null || results.isEmpty()) {
            return null;
        }
        String normKey = normalizePlaceName(keyword);
        if (normKey.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> r : results) {
            Object nameObj = r.get("name");
            if (nameObj == null) {
                continue;
            }
            String poiName = normalizePlaceName(nameObj.toString());
            if (poiName.isEmpty()) {
                continue;
            }
            if (poiName.equals(normKey) || poiName.contains(normKey) || normKey.contains(poiName)) {
                matched.add(r);
            }
        }
        if (matched.isEmpty()) {
            log.debug("POI 名称与景点「{}」不匹配，放弃补全（防张冠李戴）", keyword);
            return null;
        }
        // 优先返回带图片的
        for (Map<String, Object> result : matched) {
            Object img = result.get("image_url");
            if (img != null && !img.toString().isEmpty()) {
                return result;
            }
        }
        return matched.get(0);
    }

    /** POI 名称规范化（匹配用）：去空白、全半角括号、常见景区后缀 */
    private String normalizePlaceName(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("[\\s\\u3000（）()]", "");
        return t.replaceAll("(风景名胜区|风景区|景区|公园|古镇|老街|景点)$", "");
    }
}
