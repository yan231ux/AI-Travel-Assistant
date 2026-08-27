package com.yuntu.tripplanner;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * AI旅游助手 - AI旅行规划系统
 * 
 * @author Trae AI Assistant
 * @version 1.0.0
 */
@SpringBootApplication
@EnableAsync
@MapperScan("com.yuntu.tripplanner.repository")
public class TripPlannerApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(TripPlannerApplication.class, args);
    }
}