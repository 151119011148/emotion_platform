package com.emotion.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class MarketConfig {

    /**
     * 行情源是免费的第三方接口，必须显式设超时：默认的 HttpURLConnection 无读超时，
     * 上游一旦挂起会把拉取线程永久占住。
     */
    @Bean
    public RestTemplate marketRestTemplate(RestTemplateBuilder builder,
                                           @Value("${market.connect-timeout-ms:1500}") int connectTimeoutMs,
                                           @Value("${market.read-timeout-ms:3500}") int readTimeoutMs) {
        return builder
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(connectTimeoutMs);
                    factory.setReadTimeout(readTimeoutMs);
                    return factory;
                })
                .additionalInterceptors((request, body, execution) -> {
                    HttpHeaders headers = request.getHeaders();
                    headers.set(HttpHeaders.USER_AGENT,
                            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36");
                    headers.set(HttpHeaders.REFERER, "http://quote.eastmoney.com/");
                    // JDK 的 keep-alive 连接池会复用被限流器掐断的 socket，表现为反复 "Empty reply"
                    headers.set(HttpHeaders.CONNECTION, "close");
                    return execution.execute(request, body);
                })
                .build();
    }

    @Bean(name = "marketExecutor", destroyMethod = "shutdown")
    public ExecutorService marketExecutor(@Value("${market.pool-size:5}") int poolSize) {
        final AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "market-fetch-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(poolSize, factory);
    }
}
