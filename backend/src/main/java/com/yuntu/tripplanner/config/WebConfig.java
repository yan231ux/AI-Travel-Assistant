package com.yuntu.tripplanner.config;

import com.yuntu.tripplanner.security.JwtAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Web配置类 - 处理跨域 + JWT 鉴权拦截器
 *
 * 允许来源由 cors.allowed-origins 配置（逗号分隔），不开放通配 origin；
 * 不启用 credentials（系统无 Cookie 会话，鉴权走 Authorization 头），降低被任意站点调用的风险。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOrigins;

    /** 图片上传存储目录（与 UploadService 同源配置）；对外以 /uploads/** 静态映射访问 */
    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    private final JwtAuthInterceptor jwtAuthInterceptor;

    public WebConfig(JwtAuthInterceptor jwtAuthInterceptor) {
        this.jwtAuthInterceptor = jwtAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 仅注册/登录/管理员登录免鉴权；/auth/me 需要 JWT，才能从 UserContext 同步真实角色。
        // /uploads/** 为图片静态资源：<img> 加载不带 Authorization 头，必须放行；
        // 上传接口在 /file/**（不在排除内），仍需登录。
        // /system/health 为运行健康检查（审查报告 P2-6）：部署/演示前探测依赖状态，须免登录。
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/**")
            .excludePathPatterns("/auth/register", "/auth/login", "/auth/admin-login",
                    "/uploads/**", "/system/health");
    }

    /** 上传图片静态映射：/uploads/xxx.jpg → 文件系统 ${app.upload-dir}/xxx.jpg */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = "file:" + Paths.get(uploadDir).toAbsolutePath().normalize().toString().replace("\\", "/") + "/";
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
