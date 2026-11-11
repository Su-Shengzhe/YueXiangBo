package cn.community.foundation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** One OpenAI-compatible transport. Provider credentials and mappings belong to the gateway. */
@Component
@Profile("business")
public final class LlmGateway implements AutoCloseable {
    private static final System.Logger LOG=System.getLogger(LlmGateway.class.getName());
    private final Path configFile;
    private final java.util.concurrent.ConcurrentMap<String,java.time.Instant> cooldowns=new java.util.concurrent.ConcurrentHashMap<>();
    private String account(Route route){return Data.hash(route.properties.getProperty("base-url","")+"\n"+route.properties.getProperty("api-key",""));}
    public String accountKey(Route route){return account(route);}
    public java.time.Instant cooldownUntil(Route route){return cooldowns.get(account(route));}
    public void checkAdmission(Route route){var until=cooldowns.get(account(route));Problem.require(until==null||!java.time.Instant.now().isBefore(until),429,"AI_PROVIDER_COOLDOWN","供应商正在限流冷却，请稍后再试");}
    static long retrySeconds(String value){
        try{return Math.max(1,Math.min(86400,Long.parseLong(value)));}catch(Exception ignored){}
        try{return Math.max(1,Math.min(86400,Duration.between(java.time.Instant.now(),java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds()));}catch(Exception ignored){return 60;}
    }
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public LlmGateway(@Value("${llm.gateway.config-file:deploy/secrets/llm-gateway.properties}") String file) {
        configFile = Path.of(file);
    }

    private Properties config() {
        var p = new Properties();
        if (!Files.exists(configFile)) return p;
        try (var reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            p.load(reader);
            return p;
        } catch (IOException | IllegalArgumentException e) {
            throw new Problem(503, "AI_CONFIG_INVALID", "模型网关配置不可读取");
        }
    }

    public boolean enabled() { return Boolean.parseBoolean(config().getProperty("enabled", "false")); }

    /** Immutable request-local configuration; credentials never appear in diagnostic output. */
    public static final class Route {
        private final Properties properties;
        private Route(Properties source) { properties = new Properties(); properties.putAll(source); }
        public String version() { return properties.getProperty("route-version", "unversioned"); }
        @Override public String toString() { return "LlmRoute[version=" + version() + "]"; }
    }

    public Route captureRoute() { return new Route(config()); }
    public Route captureRoute(Properties configuration) { return new Route(configuration); }

    /** Caller supplies only trusted, sanitized messages/tool schemas, and enforces the remaining total deadline. */
    public Map<String, Object> complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                        Duration remaining) throws InterruptedException {
        return complete(captureRoute(), messages, tools, remaining);
    }

    /** Reuse this route for all model calls within one accepted user message. */
    public Map<String, Object> complete(Route route, List<Map<String, Object>> messages,
                                        List<Map<String, Object>> tools, Duration remaining) throws InterruptedException {
        var p = route.properties;
        checkAdmission(route);
        Problem.require(Boolean.parseBoolean(p.getProperty("enabled", "false")), 503, "AI_DISABLED", "模型网关尚未启用");
        String key = p.getProperty("api-key", "").trim();
        String alias = p.getProperty("model", "parking-assistant").trim();
        Problem.require(!key.isBlank() && !alias.isBlank(), 503, "AI_CONFIG_INVALID", "模型网关配置不完整");
        Problem.require(!remaining.isNegative() && !remaining.isZero(), 504, "AI_TIMEOUT", "模型调用已超过截止时间");
        final URI endpoint;
        try {
            URI base = URI.create(p.getProperty("base-url", "http://127.0.0.1:3300/v1"));
            boolean localHttp = "http".equals(base.getScheme()) && base.getHost() != null && Set.of("127.0.0.1", "localhost", "[::1]").contains(base.getHost());
            if (!("https".equals(base.getScheme()) || localHttp) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) throw new IllegalArgumentException();
            endpoint = URI.create(base.toString().replaceAll("/+$", "") + "/chat/completions");
        } catch (IllegalArgumentException e) {
            throw new Problem(503, "AI_CONFIG_INVALID", "模型网关地址必须使用HTTPS或本机回环地址");
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("model", alias);
        body.put("messages", messages);
        body.put("stream", false);
        if("disabled".equals(p.getProperty("thinking-mode")))body.put("thinking",Map.of("type","disabled"));
        if(p.getProperty("max-output-tokens")!=null)body.put("max_tokens",Integer.parseInt(p.getProperty("max-output-tokens")));
        if (!tools.isEmpty()) {body.put("tools", tools);body.put("tool_choice","required");}
        Duration timeout = remaining.compareTo(Duration.ofSeconds(20)) > 0 ? Duration.ofSeconds(20) : remaining;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Data.json(body), StandardCharsets.UTF_8)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()<200||response.statusCode()>=300)
                LOG.log(System.Logger.Level.WARNING,"Model upstream HTTP status {0}, route {1}",response.statusCode(),route.version());
            // Never expose upstream bodies: they can include provider credentials or private inputs.
            if (response.statusCode() == 429) {
                var until=java.time.Instant.now().plusSeconds(retrySeconds(response.headers().firstValue("Retry-After").orElse("60")));
                cooldowns.merge(account(route),until,(a,b)->a.isAfter(b)?a:b);
                throw new Problem(429, "AI_BUSY", "模型网关限流或额度不足");
            }
            if (response.statusCode() == 402) throw new Problem(503, "AI_PAYMENT_REQUIRED", "供应商要求付费，调用已暂停");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new Problem(503, "AI_UNAVAILABLE", "模型网关暂不可用，请使用普通业务入口");
            Map<String, Object> result;
            try { result = Data.map(response.body()); }
            catch (RuntimeException e) { throw new Problem(502, "AI_INVALID_RESPONSE", "模型网关响应格式无效"); }
            Problem.require(result != null && result.get("choices") instanceof List<?> choices && !choices.isEmpty(),
                502, "AI_INVALID_RESPONSE", "模型网关响应缺少结果");
            return result;
        } catch (HttpTimeoutException e) {
            throw new Problem(504, "AI_TIMEOUT", "模型网关响应超时");
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING,"Model transport failure {0}, route {1}",e.getClass().getSimpleName(),route.version());
            throw new Problem(503, "AI_UNAVAILABLE", "模型网关连接失败");
        } catch (IllegalArgumentException e) {
            throw new Problem(503, "AI_CONFIG_INVALID", "模型网关配置无效");
        }
    }

    @jakarta.annotation.PreDestroy
    @Override public void close() { http.shutdownNow(); }
}
