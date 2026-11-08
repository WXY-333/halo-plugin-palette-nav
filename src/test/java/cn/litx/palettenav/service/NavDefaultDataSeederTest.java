package cn.litx.palettenav.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.litx.palettenav.extension.NavCategory;
import cn.litx.palettenav.extension.NavLink;
import cn.litx.palettenav.extension.NavQuickLink;
import cn.litx.palettenav.extension.NavSearchEngine;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Extension;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class NavDefaultDataSeederTest {

    @Test
    void seedsDefaultsAndMarksStateWhenEmpty() {
        var client = emptyInstance();
        when(client.fetch(ConfigMap.class, NavDefaultDataService.STATE_CONFIG_MAP))
            .thenReturn(Mono.empty());
        when(client.<Extension>list(any(), any(), any()))
            .thenReturn(Flux.empty());

        new NavDefaultDataService(client).initializeOnce().block(Duration.ofSeconds(5));

        verify(client, atLeastOnce()).create(isA(NavCategory.class));
        verify(client, atLeastOnce()).create(isA(NavLink.class));
        verify(client, atLeastOnce()).create(isA(NavSearchEngine.class));
        verify(client, atLeastOnce()).create(isA(NavQuickLink.class));
        verify(client, atLeastOnce()).create(isA(ConfigMap.class));
    }

    @Test
    void leavesExistingDataUntouchedButStillMarksState() {
        var client = emptyInstance();
        when(client.fetch(ConfigMap.class, NavDefaultDataService.STATE_CONFIG_MAP))
            .thenReturn(Mono.empty());
        when(client.<NavCategory>list(eq(NavCategory.class), any(), any()))
            .thenReturn(Flux.just(new NavCategory()));
        when(client.<NavLink>list(eq(NavLink.class), any(), any()))
            .thenReturn(Flux.empty());
        when(client.<NavSearchEngine>list(eq(NavSearchEngine.class), any(), any()))
            .thenReturn(Flux.empty());
        when(client.<NavQuickLink>list(eq(NavQuickLink.class), any(), any()))
            .thenReturn(Flux.empty());

        new NavDefaultDataService(client).initializeOnce().block(Duration.ofSeconds(5));

        verify(client, never()).create(isA(NavCategory.class));
        verify(client, never()).create(isA(NavLink.class));
        verify(client, atLeastOnce()).create(isA(ConfigMap.class));
    }

    @Test
    void doesNothingWhenMarkerExists() {
        var client = mock(ReactiveExtensionClient.class);
        when(client.fetch(ConfigMap.class, NavDefaultDataService.STATE_CONFIG_MAP))
            .thenReturn(Mono.just(marker(NavDefaultDataService.STATE_INITIALIZED)));

        new NavDefaultDataService(client).initializeOnce().block(Duration.ofSeconds(5));

        verify(client, never()).create(any(Extension.class));
    }

    @Test
    void resumesAnInterruptedSeedWithoutRecreatingExistingEntries() {
        var client = emptyInstance();
        when(client.fetch(ConfigMap.class, NavDefaultDataService.STATE_CONFIG_MAP))
            .thenReturn(Mono.just(marker(NavDefaultDataService.STATE_SEEDING)));
        // The previous attempt stopped after the categories were written.
        when(client.fetch(eq(NavCategory.class), anyString()))
            .thenAnswer(invocation -> Mono.just(new NavCategory()));
        when(client.<Extension>update(any()))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        new NavDefaultDataService(client).initializeOnce().block(Duration.ofSeconds(5));

        verify(client, never()).create(isA(NavCategory.class));
        verify(client, atLeastOnce()).create(isA(NavLink.class));
        verify(client, atLeastOnce()).create(isA(NavSearchEngine.class));
        verify(client, atLeastOnce()).create(isA(NavQuickLink.class));
        verify(client, atLeastOnce()).update(isA(ConfigMap.class));
    }

    @Test
    void leavesTheSeedMarkerWhenTheImportFailsHalfway() {
        var client = emptyInstance();
        when(client.fetch(ConfigMap.class, NavDefaultDataService.STATE_CONFIG_MAP))
            .thenReturn(Mono.empty());
        when(client.<Extension>list(any(), any(), any())).thenReturn(Flux.empty());
        doReturn(Mono.error(new IllegalStateException("storage is temporarily down")))
            .when(client).create(any(NavCategory.class));

        new NavDefaultDataService(client).initializeOnce()
            .onErrorResume(error -> Mono.empty())
            .block(Duration.ofSeconds(5));

        // Only the "seeding" marker was written; resuming is the next start's job.
        verify(client, atLeastOnce()).create(isA(ConfigMap.class));
        verify(client, never()).create(isA(NavLink.class));
    }

    @Test
    void doesNotSeedWhenTheSeedingMarkerCannotBeWritten() {
        var client = emptyInstance();
        when(client.<Extension>list(any(), any(), any())).thenReturn(Flux.empty());
        doReturn(Mono.error(new IllegalStateException("config map storage is down")))
            .when(client).create(isA(ConfigMap.class));

        new NavDefaultDataService(client).initializeOnce()
            .onErrorResume(error -> Mono.empty())
            .block(Duration.ofSeconds(5));

        // Without the marker a partially written seed could never be resumed, so the
        // import must not start at all.
        verify(client, never()).create(isA(NavCategory.class));
        verify(client, never()).create(isA(NavLink.class));
    }

    /**
     * Stubs the per-entity existence checks as "nothing exists yet" and echoes creates.
     */
    private static ReactiveExtensionClient emptyInstance() {
        var client = mock(ReactiveExtensionClient.class);
        when(client.fetch(eq(NavCategory.class), anyString())).thenReturn(Mono.empty());
        when(client.fetch(eq(NavLink.class), anyString())).thenReturn(Mono.empty());
        when(client.fetch(eq(NavSearchEngine.class), anyString())).thenReturn(Mono.empty());
        when(client.fetch(eq(NavQuickLink.class), anyString())).thenReturn(Mono.empty());
        when(client.fetch(eq(ConfigMap.class), anyString())).thenReturn(Mono.empty());
        when(client.<Extension>create(any()))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        return client;
    }

    private static ConfigMap marker(String state) {
        var configMap = new ConfigMap();
        var metadata = new Metadata();
        metadata.setName(NavDefaultDataService.STATE_CONFIG_MAP);
        configMap.setMetadata(metadata);
        configMap.setData(Map.of("state", state, "version", "2"));
        return configMap;
    }
}