package com.myshop.config;

import com.myshop.service.UploadStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.CacheControl;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.core.io.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Arrays;
import java.time.Duration;
import java.util.regex.Pattern;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final Pattern IMMUTABLE_MEDIA = Pattern.compile(
            "(?i)(?:[0-9a-f]{64}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(?:jpg|jpeg|png|gif|webp|bmp)");

    /** CORS 允许的来源列表，逗号分隔。默认允许 localhost 开发环境。 */
    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:3001}")
    private String corsAllowedOrigins;

    private static final String[] ALLOWED_HEADERS = {
            "Content-Type",
            "Accept",
            "X-Session-Token",
            "X-Message-Viewer",
            "X-CSRF-Token",
            "X-Device-Id",
            "X-Turnstile-Token"
    };

    private final UploadStorage uploadStorage;

    public WebMvcConfig(UploadStorage uploadStorage) {
        this.uploadStorage = uploadStorage;
    }

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);  // 连接超时 5 秒
        factory.setReadTimeout(15_000);    // 读取超时 15 秒
        return new RestTemplate(factory);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        if (origins.length == 0 || (origins.length == 1 && "*".equals(origins[0]))) {
            // 通配符模式：不允许 credentials
            registry.addMapping("/**")
                    .allowedOriginPatterns("*")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders(ALLOWED_HEADERS)
                    .exposedHeaders("X-Session-Token")
                    .allowCredentials(false)
                    .maxAge(3600);
        } else {
            registry.addMapping("/**")
                    .allowedOrigins(origins)
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders(ALLOWED_HEADERS)
                    .exposedHeaders("X-Session-Token")
                    .allowCredentials(true)
                    .maxAge(3600);
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String uri = request.getRequestURI();
                String context = request.getContextPath();
                String path = uri.startsWith(context) ? uri.substring(context.length()) : "";
                if (("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                        && path.startsWith("/uploads/")) {
                    String filename = path.substring("/uploads/".length());
                    if (IMMUTABLE_MEDIA.matcher(filename).matches() && uploadStorage.isSafePublicFile(filename)) {
                        response.setHeader("Cache-Control", CacheControl.maxAge(Duration.ofDays(365))
                                .cachePublic().immutable().getHeaderValue());
                    }
                }
                return true;
            }
        }).addPathPatterns("/uploads/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = "file:" + uploadStorage.directory().toString().replace('\\', '/') + "/";
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location)
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        if (!uploadStorage.isSafePublicFile(resourcePath)) return null;
                        return super.getResource(resourcePath, location);
                    }
                });
    }
}
