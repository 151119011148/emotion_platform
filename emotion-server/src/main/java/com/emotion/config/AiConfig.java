package com.emotion.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 大模型出网的那条线。单独一个 {@code RestTemplate}，不复用 {@code marketRestTemplate}，两个原因：
 * <ul>
 *   <li>行情源的读超时是三秒半，一次模型补全正常就要跑十几秒——共用会把每次调用都变成超时；</li>
 *   <li>行情那台带的是东财口径的 UA/Referer/{@code Connection: close} 拦截器，
 *       发给 DashScope 没有意义，还多一层看得见的怪行为。</li>
 * </ul>
 */
@Configuration
public class AiConfig {

    @Bean(name = "aiRestTemplate")
    public RestTemplate aiRestTemplate(RestTemplateBuilder builder,
                                       @Value("${ai.connect-timeout-ms:5000}") int connectTimeoutMs,
                                       @Value("${ai.read-timeout-ms:60000}") int readTimeoutMs) {
        return builder
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(connectTimeoutMs);
                    factory.setReadTimeout(readTimeoutMs);
                    return factory;
                })
                .build();
    }
}
