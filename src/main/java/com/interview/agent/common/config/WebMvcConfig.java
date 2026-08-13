package com.interview.agent.common.config;

import com.interview.agent.common.interceptor.JwtTokenInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final JwtTokenInterceptor jwtTokenInterceptor;
    private final String kbImageDir;

    public WebMvcConfig(JwtTokenInterceptor jwtTokenInterceptor,
                        @Value("${kb.image-dir:kb-images}") String kbImageDir) {
        this.jwtTokenInterceptor = jwtTokenInterceptor;
        this.kbImageDir = kbImageDir;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtTokenInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/auth/**")
                // 图片访问：<img> 标签无法携带 Authorization header，故放行静态图片
                .excludePathPatterns("/api/kb-images/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 知识库图片静态资源：/api/kb-images/xxx.png → ./kb-images/xxx.png
        String location = Paths.get(kbImageDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/api/kb-images/**")
                .addResourceLocations(location);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}