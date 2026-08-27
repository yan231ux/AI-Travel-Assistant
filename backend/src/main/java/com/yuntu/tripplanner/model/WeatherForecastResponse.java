package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 天气预报响应
 */
@Data
public class WeatherForecastResponse {
    
    @JsonProperty("city")
    private String city;
    
    @JsonProperty("province")
    private String province;
    
    @JsonProperty("adcode")
    private String adcode;
    
    @JsonProperty("report_time")
    private LocalDateTime reportTime;
    
    @JsonProperty("source")
    private String source;
    
    @JsonProperty("days")
    private List<WeatherDay> days;
    
    /**
     * 单日天气
     */
    @Data
    public static class WeatherDay {
        
        @JsonProperty("date")
        private String date;
        
        @JsonProperty("week")
        private String week;
        
        @JsonProperty("day_weather")
        private String dayWeather;
        
        @JsonProperty("night_weather")
        private String nightWeather;
        
        @JsonProperty("day_temp")
        private String dayTemp;
        
        @JsonProperty("night_temp")
        private String nightTemp;
        
        @JsonProperty("day_wind")
        private String dayWind;
        
        @JsonProperty("night_wind")
        private String nightWind;
    }
}