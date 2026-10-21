package cn.community.parkingservice;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ProbeProfileTest {
    @Test void probesAreAbsentWithoutExplicitEnvironmentAndProbeProfiles() {
        for (String[] profiles : new String[][]{{}, {"local"}, {"container"}, {"probe"}}) {
            try (var context = context(profiles)) {
                assertTrue(context.getBeansOfType(ProbeController.class).isEmpty());
                assertTrue(context.getBeansOfType(InternalProbeController.class).isEmpty());
                assertTrue(context.getBeansOfType(ProbeStore.class).isEmpty());
            }
        }
    }
    @Test void probesAreAvailableOnlyForOptInVerification() {
        for (String environment : new String[]{"local", "container"}) {
            try (var context = context(new String[]{environment, "probe"})) {
                assertEquals(1, context.getBeansOfType(ProbeController.class).size());
                assertEquals(1, context.getBeansOfType(InternalProbeController.class).size());
            }
        }
    }
    private AnnotationConfigApplicationContext context(String[] profiles) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("probe.token", "a".repeat(64))));
        context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
        context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
        context.registerBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class));
        context.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
        context.register(ProbeStore.class, ProbeController.class, InternalProbeController.class);
        context.refresh();
        return context;
    }
}
