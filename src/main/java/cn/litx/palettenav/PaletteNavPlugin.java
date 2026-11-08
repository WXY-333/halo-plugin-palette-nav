package cn.litx.palettenav;

import cn.litx.palettenav.config.NavSettingsConfigInitializer;
import cn.litx.palettenav.extension.NavCategory;
import cn.litx.palettenav.extension.NavLink;
import cn.litx.palettenav.extension.NavQuickLink;
import cn.litx.palettenav.extension.NavSearchEngine;
import cn.litx.palettenav.extension.NavVisitStat;
import cn.litx.palettenav.extension.NavVisitVisitor;
import cn.litx.palettenav.service.NavDefaultDataService;
import cn.litx.palettenav.service.NavIconSpriteService;
import cn.litx.palettenav.service.NavStatsService;
import cn.litx.palettenav.service.NavVisitRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * Palette Nav plugin entry point.
 *
 * <p>{@code @GVK} is only metadata: every custom model must be registered with the
 * {@link SchemeManager} so that Halo can create the CRUD APIs and the extension store.</p>
 */
@Slf4j
@Component
public class PaletteNavPlugin extends BasePlugin {

    private final SchemeManager schemeManager;
    private final NavVisitRecorder visitRecorder;
    private final NavStatsService statsService;
    private final NavDefaultDataService defaultDataService;
    private final NavSettingsConfigInitializer settingsConfigInitializer;
    private final NavIconSpriteService iconSpriteService;

    public PaletteNavPlugin(PluginContext pluginContext, SchemeManager schemeManager,
        NavVisitRecorder visitRecorder, NavStatsService statsService,
        NavDefaultDataService defaultDataService,
        NavSettingsConfigInitializer settingsConfigInitializer,
        NavIconSpriteService iconSpriteService) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.visitRecorder = visitRecorder;
        this.statsService = statsService;
        this.defaultDataService = defaultDataService;
        this.settingsConfigInitializer = settingsConfigInitializer;
        this.iconSpriteService = iconSpriteService;
    }

    @Override
    public void start() {
        // Business settings live in a ConfigMap that is not spec.configMapName (so
        // uninstalling with "delete config" keeps them); create it if it is missing.
        settingsConfigInitializer.initialize();
        schemeManager.register(NavCategory.class);
        schemeManager.register(NavLink.class);
        schemeManager.register(NavSearchEngine.class);
        schemeManager.register(NavQuickLink.class);
        schemeManager.register(NavVisitStat.class);
        schemeManager.register(NavVisitVisitor.class);
        // Schemes must be registered before the defaults can query the store.
        defaultDataService.initialize();
        // Parse the icon sprite now so the first /nav request does not block the
        // event-loop thread on ~900 KB of classpath reads and regex scans.
        iconSpriteService.initialize();
        visitRecorder.start();
        statsService.start();
        log.info("Palette Nav plugin {} v{} started", context.getName(), context.getVersion());
    }

    @Override
    public void stop() {
        // Drain queued visits first, then write the buffered counters.
        visitRecorder.stop();
        statsService.stop();
        schemeManager.unregister(Scheme.buildFromType(NavCategory.class));
        schemeManager.unregister(Scheme.buildFromType(NavLink.class));
        schemeManager.unregister(Scheme.buildFromType(NavSearchEngine.class));
        schemeManager.unregister(Scheme.buildFromType(NavQuickLink.class));
        schemeManager.unregister(Scheme.buildFromType(NavVisitStat.class));
        schemeManager.unregister(Scheme.buildFromType(NavVisitVisitor.class));
        log.info("Palette Nav plugin {} v{} stopped", context.getName(), context.getVersion());
    }
}
