package com.yuntu.tripplanner.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.model.WeatherForecastResponse;
import com.yuntu.tripplanner.service.CacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Open-Meteo天气API客户端
 */
@Slf4j
@Component
public class OpenMeteoClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AmapClient amapClient;
    private final CacheService cacheService;

    private static final String BASE_URL = "https://api.open-meteo.com/v1/forecast";

    /** 天气缓存 TTL（秒），来自 redis.weather-ttl-seconds */
    private final long weatherCacheTtl;
    
    // 天气代码映射（Map.of 最多 10 对，这里条目多，用 Map.ofEntries）
    private static final Map<Integer, String> WEATHER_CODE_MAP = Map.ofEntries(
            Map.entry(0, "晴"),
            Map.entry(1, "大部分晴朗"),
            Map.entry(2, "多云"),
            Map.entry(3, "阴天"),
            Map.entry(45, "雾"),
            Map.entry(48, "雾凇"),
            Map.entry(51, "小毛毛雨"),
            Map.entry(53, "毛毛雨"),
            Map.entry(55, "大毛毛雨"),
            Map.entry(61, "小雨"),
            Map.entry(63, "中雨"),
            Map.entry(65, "大雨"),
            Map.entry(66, "冻雨"),
            Map.entry(67, "大冻雨"),
            Map.entry(71, "小雪"),
            Map.entry(73, "中雪"),
            Map.entry(75, "大雪"),
            Map.entry(77, "雪粒"),
            Map.entry(80, "小阵雨"),
            Map.entry(81, "阵雨"),
            Map.entry(82, "大阵雨"),
            Map.entry(85, "小阵雪"),
            Map.entry(86, "大阵雪"),
            Map.entry(95, "雷暴"),
            Map.entry(96, "小雷暴伴冰雹"),
            Map.entry(99, "大雷暴伴冰雹")
    );
    
    public OpenMeteoClient(AmapClient amapClient, ObjectMapper objectMapper, CacheService cacheService,
                           @Value("${redis.weather-ttl-seconds:1800}") long weatherCacheTtl) {
        this.amapClient = amapClient;
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
        this.weatherCacheTtl = weatherCacheTtl;
        this.restTemplate = new RestTemplate();
    }
    
    /**
     * 获取天气预报
     *
     * @param city      城市名
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @return 天气预报响应
     */
    public WeatherForecastResponse getWeatherForecast(String city, LocalDate startDate, LocalDate endDate) {
        String cacheKey = String.format("weather:forecast:%s:%s:%s", city,
                startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                endDate.format(DateTimeFormatter.ISO_LOCAL_DATE));

        // 缓存命中直接返回（天气 30 分钟内可视为稳定）
        WeatherForecastResponse cached = cacheService.get(cacheKey, WeatherForecastResponse.class);
        if (cached != null && cached.getDays() != null && !cached.getDays().isEmpty()) {
            log.info("天气缓存命中: {}", cacheKey);
            return cached;
        }

        try {
            // 1. 先通过高德获取城市坐标
            Map<String, Double> coords = amapClient.geocode(city);
            if (coords == null) {
                log.error("无法获取城市坐标: {}", city);
                return null;
            }
            
            Double latitude = coords.get("latitude");
            Double longitude = coords.get("longitude");
            
            // 2. 调用Open-Meteo API
            String url = UriComponentsBuilder.fromHttpUrl(BASE_URL)
                    .queryParam("latitude", latitude)
                    .queryParam("longitude", longitude)
                    .queryParam("start_date", startDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .queryParam("end_date", endDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .queryParam("daily", "temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max")
                    .queryParam("timezone", "Asia/Shanghai")
                    .toUriString();
            
            log.info("Open-Meteo天气查询: {}", url);
            String response = restTemplate.getForObject(url, String.class);
            
            JsonNode root = objectMapper.readTree(response);
            if (root.has("daily")) {
                JsonNode daily = root.get("daily");
                
                WeatherForecastResponse forecast = new WeatherForecastResponse();
                forecast.setCity(city);
                forecast.setSource("open-meteo");
                
                List<WeatherForecastResponse.WeatherDay> days = new ArrayList<>();
                JsonNode dates = daily.path("time");
                JsonNode maxTemps = daily.path("temperature_2m_max");
                JsonNode minTemps = daily.path("temperature_2m_min");
                JsonNode weatherCodes = daily.path("weather_code");
                
                for (int i = 0; i < dates.size(); i++) {
                    WeatherForecastResponse.WeatherDay day = new WeatherForecastResponse.WeatherDay();
                    day.setDate(dates.get(i).asText());
                    
                    // 计算星期
                    LocalDate date = LocalDate.parse(dates.get(i).asText());
                    DayOfWeek weekDay = date.getDayOfWeek();
                    day.setWeek(String.valueOf(weekDay.getValue()));
                    
                    String weatherText = WEATHER_CODE_MAP.getOrDefault(weatherCodes.get(i).asInt(), "未知");
                    day.setDayWeather(weatherText);
                    day.setNightWeather(weatherText);
                    
                    day.setDayTemp(String.valueOf(maxTemps.get(i).asInt()));
                    day.setNightTemp(String.valueOf(minTemps.get(i).asInt()));
                    
                    days.add(day);
                }
                
                forecast.setDays(days);
                cacheService.set(cacheKey, forecast, weatherCacheTtl);
                return forecast;
            }
        } catch (Exception e) {
            log.error("Open-Meteo天气查询失败: {}", e.getMessage());
        }
        return null;
    }
}