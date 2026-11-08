package cn.litx.palettenav.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;

class NavSettingsConfigInitializerTest {

    @Test
    void createsTheConfigMapWhenMissing() {
        var client = mock(ReactiveExtensionClient.class);
        when(client.fetch(ConfigMap.class, NavSettings.CONFIG_MAP_NAME))
            .thenReturn(Mono.empty());
        when(client.create(any(ConfigMap.class)))
            .thenReturn(Mono.just(new ConfigMap()));

        new NavSettingsConfigInitializer(client)
            .initializeOnce()
            .block(Duration.ofSeconds(5));

        var captor = ArgumentCaptor.forClass(ConfigMap.class);
        verify(client).create(captor.capture());
        var created = captor.getValue();
        Assertions.assertEquals(NavSettings.CONFIG_MAP_NAME,
            created.getMetadata().getName());
        Assertions.assertEquals(Map.of(), created.getData());
    }

    @Test
    void leavesAnExistingConfigMapUntouched() {
        var client = mock(ReactiveExtensionClient.class);
        when(client.fetch(ConfigMap.class, NavSettings.CONFIG_MAP_NAME))
            .thenReturn(Mono.just(new ConfigMap()));

        new NavSettingsConfigInitializer(client)
            .initializeOnce()
            .block(Duration.ofSeconds(5));

        verify(client, never()).create(any(ConfigMap.class));
    }
}