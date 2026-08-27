package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.client.OpenMeteoClient;
import com.yuntu.tripplanner.model.WeatherForecastResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 天气控制器
 */
@Slf4j
@RestController
@RequestMapping("/weather")
public class WeatherController {
    
    private final OpenMeteoClient openMeteoClient;
    
    public WeatherController(OpenMeteoClient openMeteoClient) {
        this.openMeteoClient = openMeteoClient;
    }
    
    /**
     * 获取天气预报
     * 
     * @param city      城市（必填）
     * @param startDate 开始日期（选填）
     * @param endDate   结束日期（选填）
     */
    @GetMapping("/forecast")
    public ResponseEntity<WeatherForecastResponse> getWeatherForecast(
            @RequestParam("city") String city,
            @RequestParam(value = "start_date", required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "end_date", required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        
        try {
            log.info("获取天气预报: city={}, startDate={}, endDate={}", city, startDate, endDate);
            
            // 默认查询未来3-7天
            if (startDate == null) {
                startDate = LocalDate.now();
            }
            
            if (endDate == null) {
                endDate = startDate.plusDays(6);
            }
            
            // 最少3天，最长16天
            long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
            if (days < 3) {
                endDate = startDate.plusDays(2);
            } else if (days > 16) {
                endDate = startDate.plusDays(15);
            }
            
            WeatherForecastResponse response = openMeteoClient.getWeatherForecast(city, startDate, endDate);
            
            if (response != null) {
                return ResponseEntity.ok(response);
            } else {
                WeatherForecastResponse emptyResponse = new WeatherForecastResponse();
                emptyResponse.setCity(city);
                emptyResponse.setSource("none");
                return ResponseEntity.ok(emptyResponse);
            }
            
        } catch (Exception e) {
            log.error("获取天气预报失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}