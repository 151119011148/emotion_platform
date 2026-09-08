package com.emotion.market;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 行情层唯一碰网络的类。所有失败都收敛成 null，绝不向上抛异常——
 * 拉取是尽力而为的补数操作，单个上游挂掉不该让整次请求失败。
 */
@Component
public class HttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpFetcher.class);

    private final RestTemplate restTemplate;
    private final int retries;

    public HttpFetcher(RestTemplate marketRestTemplate,
                       @Value("${market.retries:1}") int retries) {
        this.restTemplate = marketRestTemplate;
        this.retries = Math.max(0, retries);
    }

    public String getText(String rawUrl) {
        byte[] bytes = getBytes(rawUrl);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** 上游返回的字节原样交出，编码（GBK 等）由调用方决定怎么解。 */
    public byte[] getBytes(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            log.warn("行情源 URL 非法，已跳过: {}", rawUrl);
            return null;
        }

        log.info("请求行情源: {}", uri);
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                // 必须走 URI 重载：传 String 会被当成 URI 模板重编码，弄坏 sort=fbt%3Aasc 和 fs=m:0+t:6
                ResponseEntity<byte[]> response =
                        restTemplate.exchange(uri, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return response.getBody();
                }
                log.warn("行情源返回异常状态 uri={} status={}", uri, response.getStatusCode());
            } catch (Exception e) {
                log.warn("行情源请求失败 uri={} 第{}次 原因={}", uri, attempt + 1, e.getClass().getSimpleName());
            }
        }
        return null;
    }
}
