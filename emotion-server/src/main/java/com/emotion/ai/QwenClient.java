package com.emotion.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * 阿里千问（DashScope）的<b>兼容模式</b>客户端：{@code POST {base}/chat/completions}，
 * 请求体与返回体都是 OpenAI 那一套，所以以后换模型只动这一层。
 *
 * <p>key 只从配置进（{@code ${QWEN_API_KEY:}}），不进代码、不进 CI、不落日志——
 * 仓库是公开的，任何一处写死都会永久留在历史里。
 *
 * <p>失败一律收敛成 {@link LlmException} 的一句人话，与 {@code market/HttpFetcher} 的
 * 「网络失败不往上抛」同一条路子：一次草稿生成失败不该把别的接口带崩。
 */
@Component
public class QwenClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(QwenClient.class);
    /** 上游错误体可能很长（还带请求 id），截一段就够定位，别让日志和返回值刷屏。 */
    private static final int BRIEF = 200;

    private final RestTemplate http;
    private final ObjectMapper json;
    private final String url;
    private final String model;
    private final String apiKey;
    private final double temperature;
    private final int maxTokens;

    public QwenClient(@Qualifier("aiRestTemplate") RestTemplate http,
                      ObjectMapper json,
                      @Value("${ai.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
                      @Value("${ai.qwen.model:qwen-plus}") String model,
                      @Value("${ai.qwen.api-key:}") String apiKey,
                      @Value("${ai.qwen.temperature:0.7}") double temperature,
                      @Value("${ai.qwen.max-tokens:400}") int maxTokens) {
        this.http = http;
        this.json = json;
        this.url = trimTrailingSlash(baseUrl) + "/chat/completions";
        this.model = model;
        this.apiKey = apiKey;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public String complete(String system, String user) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new LlmException("没有配置千问的 API Key（写进本地 application-local.yml 的 "
                    + "ai.qwen.api-key，或给环境变量 QWEN_API_KEY），草稿这次没生成。");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey.trim());
        try {
            ResponseEntity<String> response = http.postForEntity(url,
                    new HttpEntity<Map<String, Object>>(body(system, user), headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new LlmException("千问返回 HTTP " + response.getStatusCode().value()
                        + "：" + brief(response.getBody()));
            }
            return contentOf(response.getBody());
        } catch (HttpStatusCodeException e) {
            // 401/429/400 的正文里是上游给的原因，不含 key；正文只截一段。
            log.warn("千问调用失败 HTTP {}", e.getRawStatusCode());
            throw new LlmException("千问返回 HTTP " + e.getRawStatusCode() + "：" + brief(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            throw new LlmException("千问没接通：" + e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> body(String system, String user) {
        List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
        messages.add(message("system", system));
        messages.add(message("user", user));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        // 非流式：草稿要整段拿回来过门卫，边出边渲染反而会把半截违规文本递到人眼前。
        body.put("stream", false);
        return body;
    }

    private static Map<String, String> message(String role, String content) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("role", role);
        m.put("content", content == null ? "" : content);
        return m;
    }

    /** 取 {@code choices[0].message.content}；空 choices（内容被上游拦了）要说清是哪一步空的。 */
    private String contentOf(String raw) {
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (Exception e) {
            throw new LlmException("千问返回体读不出 JSON：" + brief(raw));
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            JsonNode err = root.path("message");
            throw new LlmException("千问没有产出内容" + (err.isTextual() ? "：" + brief(err.asText()) : ""));
        }
        String content = choices.get(0).path("message").path("content").asText("");
        if (content.trim().isEmpty()) {
            throw new LlmException("千问产出了一段空文本，这次没有草稿");
        }
        return content;
    }

    private static String trimTrailingSlash(String base) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private static String brief(String text) {
        if (text == null) {
            return "（空）";
        }
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() <= BRIEF ? one : one.substring(0, BRIEF) + "…";
    }
}
