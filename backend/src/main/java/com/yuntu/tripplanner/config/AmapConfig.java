package com.yuntu.tripplanner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 高德地图配置类
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "amap")
public class AmapConfig {
    
    /**
     * 高德API Key
     */
    private String apiKey;
    
    /**
     * 高德API Base URL
     */
    private String baseUrl = "https://restapi.amap.com/v3";
}