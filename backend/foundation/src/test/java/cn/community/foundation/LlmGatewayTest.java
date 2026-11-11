package cn.community.foundation;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class LlmGatewayTest {
    @TempDir Path dir;
    HttpServer server;
    LlmGateway gateway;
    Path config;
    AtomicInteger status = new AtomicInteger(200);
    AtomicReference<String> provider = new AtomicReference<>("provider-a");
    AtomicReference<Map<String,Object>> sent = new AtomicReference<>();
    AtomicReference<String> auth = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger delay = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            sent.set(Data.map(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if(delay.get()>0)try{Thread.sleep(delay.get());}catch(InterruptedException e){Thread.currentThread().interrupt();}
            if(status.get()==302) exchange.getResponseHeaders().set("Location","/v1/chat/completions");
            String response=body.get();
            if(response==null)response=Data.json(Map.of("choices",List.of(Map.of("message",Map.of("content",provider.get())))));
            var bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        config=dir.resolve("gateway.properties");
        Files.writeString(config,"enabled=true\nbase-url=http://127.0.0.1:"+server.getAddress().getPort()+"/v1\napi-key=local-test-token\nmodel=parking-assistant\n");
        gateway=new LlmGateway(config.toString());
    }
    @AfterEach void stop(){if(gateway!=null)gateway.close();if(server!=null)server.stop(0);}
    Map<String,Object> call() throws Exception {
        return gateway.complete(List.of(Map.of("role","user","content","test")),List.of(),Duration.ofSeconds(2));
    }
    @Test void cooldownRejectsWithoutAnotherUpstreamCall() throws Exception {
        status.set(429);assertEquals("AI_BUSY",assertThrows(Problem.class,()->call()).code);
        status.set(200);assertEquals("AI_PROVIDER_COOLDOWN",assertThrows(Problem.class,()->call()).code);assertEquals(1,calls.get());
    }
    @Test void parsesRetryAfterSafely(){assertEquals(60,LlmGateway.retrySeconds("invalid"));assertEquals(12,LlmGateway.retrySeconds("12"));assertEquals(1,LlmGateway.retrySeconds("-1"));assertEquals(86400,LlmGateway.retrySeconds("999999"));}
    @Test void fixedAliasSurvivesUpstreamSwitchWithoutNewClient() throws Exception {
        assertTrue(Data.json(call()).contains("provider-a"));
        provider.set("provider-b");
        assertTrue(Data.json(call()).contains("provider-b"));
        assertEquals("parking-assistant",sent.get().get("model"));
        assertEquals("Bearer local-test-token",auth.get());
        assertEquals(false,sent.get().get("stream"));
        assertEquals(2,calls.get());
    }
    @Test void forwardsToolSchemaAndPreservesToolCalls() throws Exception {
        var tool=Map.<String,Object>of("type","function","function",Map.of("name","searchAvailableSpaces","parameters",Map.of("type","object")));
        body.set("{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\",\"function\":{\"name\":\"searchAvailableSpaces\",\"arguments\":\"{}\"}}]}}]}");
        var result=gateway.complete(List.of(Map.of("role","user","content","test")),List.of(tool),Duration.ofSeconds(2));
        assertEquals(List.of(tool),sent.get().get("tools"));assertEquals("required",sent.get().get("tool_choice"));
        assertTrue(Data.json(result).contains("tool_calls"));
    }
    @Test void doesNotLeakUpstreamErrorOrRetry() {
        status.set(401);body.set("provider-secret-key/private-message");
        var error=assertThrows(Problem.class,()->call());
        assertEquals("AI_UNAVAILABLE",error.code);
        assertFalse(error.getMessage().contains("secret"));
        assertEquals(1,calls.get());
    }
    @Test void rateLimitIsExplicit() {
        status.set(429);
        assertEquals("AI_BUSY",assertThrows(Problem.class,()->call()).code);
    }
    @Test void capturedRouteSurvivesConfigurationChangeForSubsequentToolTurn() throws Exception {
        Files.writeString(config, Files.readString(config)+"route-version=1\n");
        var route=gateway.captureRoute();
        String changed=Files.readString(config).replace("parking-assistant","replacement-model")
            .replace("local-test-token","replacement-token").replace("route-version=1","route-version=2");
        Files.writeString(config,changed);
        gateway.complete(route,List.of(Map.of("role","user","content","tool result")),List.of(),Duration.ofSeconds(2));
        assertEquals("parking-assistant",sent.get().get("model"));
        assertEquals("Bearer local-test-token",auth.get());
        assertEquals("1",route.version());
        assertFalse(route.toString().contains("token"));
        call();
        assertEquals("replacement-model",sent.get().get("model"));
        assertEquals("Bearer replacement-token",auth.get());
        assertEquals("2",gateway.captureRoute().version());
    }
    @Test void honorsRemainingDeadline() {
        delay.set(500);
        var error=assertThrows(Problem.class,()->gateway.complete(List.of(Map.of("role","user","content","test")),List.of(),Duration.ofMillis(50)));
        assertEquals("AI_TIMEOUT",error.code);
    }
    @Test void redirectIsNotFollowed() {
        status.set(302);
        assertThrows(Problem.class,()->call());
        assertEquals(1,calls.get());
    }
    @Test void rejectsMalformedAndMissingChoices() {
        body.set("not JSON");assertEquals("AI_INVALID_RESPONSE",assertThrows(Problem.class,()->call()).code);
        body.set("{\"choices\":[]}");assertEquals("AI_INVALID_RESPONSE",assertThrows(Problem.class,()->call()).code);
    }
    @Test void disabledAndExpiredRequestsDoNotReachNetwork() throws Exception {
        assertEquals("AI_TIMEOUT",assertThrows(Problem.class,()->gateway.complete(List.of(),List.of(),Duration.ZERO)).code);
        Files.writeString(config,"enabled=false\n");
        assertFalse(gateway.enabled());
        assertEquals("AI_DISABLED",assertThrows(Problem.class,()->call()).code);
        assertEquals(0,calls.get());
    }
    @Test void rejectsInsecureRemoteUrl() throws Exception {
        Files.writeString(config,"enabled=true\napi-key=test\nbase-url=http://example.com/v1\n");
        assertEquals("AI_CONFIG_INVALID",assertThrows(Problem.class,()->call()).code);
        assertEquals(0,calls.get());
    }
}
