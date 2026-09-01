package com.globalfutservice.config;

import com.globalfutservice.security.CurrentAccountArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentAccountArgumentResolver currentAccountResolver;

    public WebMvcConfig(CurrentAccountArgumentResolver currentAccountResolver) {
        this.currentAccountResolver = currentAccountResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentAccountResolver);
    }
}
