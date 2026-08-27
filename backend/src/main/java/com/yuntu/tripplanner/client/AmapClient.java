package com.yuntu.tripplanner.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.config.AmapConfig;
import com.yuntu.tripplanner.model.AmapGeocode;
import com.yuntu.tripplanner.service.CacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 高德地图API客户端（使用 JDK HttpClient，行为接近 curl）
 */
@Slf4j
@Component
public class AmapClient {

    private final AmapConfig amapConfig;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final CacheService cacheService;

    /** 地图数据缓存 TTL（秒），来自 redis.map-ttl-seconds */
    private final long mapCacheTtl;

    public AmapClient(AmapConfig amapConfig, ObjectMapper objectMapper, CacheService cacheService,
                      @Value("${redis.map-ttl-seconds:86400}") long mapCacheTtl) {
        this.amapConfig = amapConfig;
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
        this.mapCacheTtl = mapCacheTtl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * GET 请求高德 API，返回 JSON 根节点
     */
    private JsonNode get(String path, Map<String, String> params) throws Exception {
        StringBuilder url = new StringBuilder(amapConfig.getBaseUrl()).append(path).append("?key=")
                .append(URLEncoder.encode(amapConfig.getApiKey(), StandardCharsets.UTF_8));
        for (Map.Entry<String, String> e : params.entrySet()) {
            url.append("&").append(e.getKey()).append("=")
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        }

        log.info("高德请求: {}", url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        log.info("高德响应: {}", response.body().substring(0, Math.min(response.body().length(), 200)));

        JsonNode root = objectMapper.readTree(response.body());
        if (!"1".equals(root.path("status").asText())) {
            log.error("高德接口错误: {} {}", root.path("info").asText(), root.path("infocode").asText());
            throw new RuntimeException("高德接口错误: " + root.path("info").asText());
        }
        return root;
    }

    /**
     * POI搜索（多关键词合并：餐厅/景点类别用多个关键词扩大候选池，
     * 解决"5天行程把候选池用光导致重复无法替换"的问题；按名称去重）
     */
    public List<Map<String, Object>> searchPoi(String destination, String category) {
        // v2：多关键词合并版，避免命中旧版单关键词的 Redis 缓存
        String cacheKey = "map:place:v2:" + destination + ":" + category;
        List<Map<String, Object>> cached = cacheService.get(cacheKey, List.class);
        if (cached != null && !cached.isEmpty()) {
            log.info("高德POI缓存命中: {}", cacheKey);
            return cached;
        }

        try {
            List<String> keywords = expandKeywords(category);
            List<Map<String, Object>> results = new ArrayList<>();
            Set<String> seenNames = new HashSet<>();
            for (String kw : keywords) {
                Map<String, String> params = new HashMap<>();
                params.put("keywords", kw);
                params.put("city", destination);
                params.put("extensions", "all");
                params.put("offset", "15");
                params.put("page", "1");
                JsonNode root = getWithRetry("/place/text", params);

                if (root.has("pois") && root.get("pois").isArray()) {
                    for (JsonNode poi : root.get("pois")) {
                        String name = poi.path("name").asText();
                        if (name == null || name.isBlank() || !seenNames.add(name)) {
                            continue;
                        }
                        Map<String, Object> poiData = new HashMap<>();
                        poiData.put("name", poi.path("name").asText());
                        poiData.put("address", poi.path("address").asText());
                        poiData.put("type", poi.path("type").asText());
                        poiData.put("location", poi.path("location").asText());

                        String location = poi.path("location").asText();
                        if (location != null && location.contains(",")) {
                            String[] coords = location.split(",");
                            poiData.put("longitude", Double.parseDouble(coords[0]));
                            poiData.put("latitude", Double.parseDouble(coords[1]));
                        }

                        if (poi.has("photos") && poi.get("photos").isArray() && poi.get("photos").size() > 0) {
                            JsonNode firstPhoto = poi.get("photos").get(0);
                            poiData.put("image_url", firstPhoto.path("url").asText());
                        }

                        poiData.put("poi_id", poi.path("id").asText());
                        results.add(poiData);
                    }
                }
            }
            if (!results.isEmpty()) {
                cacheService.set(cacheKey, results, mapCacheTtl);
            }
            return results;
        } catch (Exception e) {
            log.error("高德POI搜索失败: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * 类别 → 搜索关键词列表（多关键词扩大候选池；默认单关键词原样）。
     * 关键词数量克制：免费 key 有 QPS 限制（CUQPS 10021），关键词过多易触发限流。
     */
    private List<String> expandKeywords(String category) {
        if (category == null) {
            return List.of("景点");
        }
        return switch (category) {
            case "餐厅" -> List.of("餐厅", "火锅", "小面");
            case "景点" -> List.of("景点", "景区");
            default -> List.of(category);
        };
    }

    /**
     * 带限流重试的 GET：免费 key 有 QPS 限制（CUQPS_HAS_EXCEEDED_THE_LIMIT / infocode 10021），
     * 命中限流时等待 300ms 重试一次，减少并发打爆配额的失败。
     */
    private JsonNode getWithRetry(String path, Map<String, String> params) throws Exception {
        try {
            return get(path, params);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("CUQPS_HAS_EXCEEDED_THE_LIMIT")) {
                log.warn("高德QPS限流，300ms后重试: {}", path);
                Thread.sleep(300);
                return get(path, params);
            }
            throw e;
        }
    }

    /**
     * 地理编码 - 地址转坐标（委托 {@link #geocodeInfo}，城市名校验也复用同一次调用）
     */
    public Map<String, Double> geocode(String address) {
        AmapGeocode info = geocodeInfo(address);
        if (info == null) {
            return null;
        }
        Map<String, Double> result = new HashMap<>();
        result.put("longitude", info.longitude());
        result.put("latitude", info.latitude());
        return result;
    }

    /**
     * 地理编码 - 返回完整解析结果（坐标 + level + 省/市 + 格式化地址）。
     * <p>城市不存在/解析失败返回 null（城市名校验的钩子）。带 Redis 缓存，失败不缓存。
     */
    public AmapGeocode geocodeInfo(String address) {
        String cacheKey = "map:geocode:info:" + address;
        AmapGeocode cached = cacheService.get(cacheKey, AmapGeocode.class);
        if (cached != null) {
            log.info("高德地理编码缓存命中: {}", cacheKey);
            return cached;
        }

        try {
            Map<String, String> params = new HashMap<>();
            params.put("address", address);
            params.put("city", address);
            JsonNode root = get("/geocode/geo", params);

            if (root.has("geocodes") && root.get("geocodes").isArray() && root.get("geocodes").size() > 0) {
                JsonNode geo = root.get("geocodes").get(0);
                String location = geo.path("location").asText();
                if (location != null && location.contains(",")) {
                    String[] coords = location.split(",");
                    AmapGeocode result = new AmapGeocode(
                            Double.parseDouble(coords[0]),
                            Double.parseDouble(coords[1]),
                            geo.path("province").asText(null),
                            geo.path("city").asText(null),
                            geo.path("level").asText(null),
                            geo.path("formatted_address").asText(null));
                    cacheService.set(cacheKey, result, mapCacheTtl);
                    return result;
                }
            }
        } catch (Exception e) {
            log.error("高德地理编码失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 驾车路线规划
     */
    public Map<String, Object> getDrivingRoute(String origin, String destination) {
        String cacheKey = "map:route:" + origin + ":" + destination;
        Map<String, Object> cached = cacheService.get(cacheKey, Map.class);
        if (cached != null && !cached.isEmpty()) {
            log.info("高德路线缓存命中: {}", cacheKey);
            return cached;
        }

        try {
            Map<String, String> params = new HashMap<>();
            params.put("origin", origin);
            params.put("destination", destination);
            params.put("strategy", "0");
            JsonNode root = get("/direction/driving", params);

            if (root.has("route") && root.get("route").has("paths")) {
                JsonNode paths = root.get("route").get("paths");
                if (paths.isArray() && paths.size() > 0) {
                    JsonNode path = paths.get(0);
                    Map<String, Object> result = new HashMap<>();
                    result.put("distance", path.path("distance").asDouble() / 1000.0);
                    result.put("duration", path.path("duration").asInt() / 60);
                    result.put("cost", path.path("tolls").asDouble());
                    cacheService.set(cacheKey, result, mapCacheTtl);
                    return result;
                }
            }
        } catch (Exception e) {
            log.error("高德路线规划失败: {}", e.getMessage());
        }
        return null;
    }
}
