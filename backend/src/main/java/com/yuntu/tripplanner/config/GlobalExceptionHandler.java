package com.yuntu.tripplanner.config;

import com.yuntu.tripplanner.exception.CityValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    /**
     * 处理参数验证异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException ex) {
        log.error("参数验证失败", ex);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", "参数验证失败");
        response.put("message", ex.getBindingResult().getFieldError() != null ? 
                ex.getBindingResult().getFieldError().getDefaultMessage() : "参数错误");
        
        return ResponseEntity.badRequest().body(response);
    }
    
    /**
     * 处理目的地城市名校验失败（假城市 / 拼错无命中）→ 400，message 含纠错建议
     */
    @ExceptionHandler(CityValidationException.class)
    public ResponseEntity<Map<String, Object>> handleCityValidationException(CityValidationException ex) {
        log.warn("目的地校验失败: {}", ex.getMessage());

        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", "目的地校验失败");
        response.put("message", ex.getMessage());

        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 处理所有异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex) {
        log.error("系统异常", ex);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", "系统错误");
        response.put("message", ex.getMessage());
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}