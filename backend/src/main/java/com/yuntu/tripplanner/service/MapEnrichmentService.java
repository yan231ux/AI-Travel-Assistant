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

    private boolean enrichSpot(SpotItem spot, String city) {
        if (spot.getName() == null || spot.getName().isEmpty()) {
            return false;
        }
        Map<String, Object> place = pickBestPlace(spot.getName(), city);
        if (place == null) {
            return false;
        }
        // 地址强制用高德真实地址覆盖（LLM 可能编造地址，如把 POI 介绍文字当地址）；高德地址为空时保留原值
        String amapAddress = (String) place.getOrDefault("address", "");
        if (amapAddress != null && !amapAddress.isBlank()) {
            spot.setAddress(amapAddress);
        }
        if (spot.getImageUrl() == null) {
            spot.setImageUrl((String) place.getOrDefault("image_url", ""));
        }
        if (spot.getLatitude() == null) {
            spot.setLatitude((Double) place.getOrDefault("latitude", null));
        }
        if (spot.getLongitude() == null) {
            spot.setLongitude((Double) place.getOrDefault("longitude", null));
        }
        if (spot.getPoiId() == null) {
            spot.setPoiId((String) place.getOrDefault("poi_id", ""));
        }
        return true;
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

    /**
     * 优先选择名称匹配且带图片的 POI
     */
    private Map<String, Object> pickBestPlace(String keyword, String city) {
        List<Map<String, Object>> results = amapClient.searchPoi(city, keyword);
        if (results == null || results.isEmpty()) {
            return null;
        }

        // 优先返回带图片的
        for (Map<String, Object> result : results) {
            Object img = result.get("image_url");
            if (img != null && !img.toString().isEmpty()) {
                return result;
            }
        }
        return results.get(0);
    }
}
