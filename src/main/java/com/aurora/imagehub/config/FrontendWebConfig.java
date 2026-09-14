package com.aurora.imagehub.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 托管前端构建产物，并支持已声明页面的刷新；不将 API 或文档路径回退到首页。
 */
@Configuration
public class FrontendWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/");
        registry.addResourceHandler("/index.html", "/favicon.svg")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 仅公开前端页面壳；登录态、图片归属等仍由 /api 业务接口校验。
        for (String path : new String[]{"/", "/login", "/register", "/history", "/profile"}) {
            registry.addViewController(path).setViewName("forward:/index.html");
        }
    }
}
