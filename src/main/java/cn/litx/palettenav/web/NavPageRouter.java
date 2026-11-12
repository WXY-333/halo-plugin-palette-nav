package cn.litx.palettenav.web;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import cn.litx.palettenav.config.NavSettings;
import cn.litx.palettenav.config.NavSettingsService;
import cn.litx.palettenav.config.NavSiteInfoService;
import cn.litx.palettenav.constants.NavConstants;
import cn.litx.palettenav.dto.NavDashboardStats;
import cn.litx.palettenav.service.NavFooterSanitizer;
import cn.litx.palettenav.service.NavIconRenderer;
import cn.litx.palettenav.service.NavIconSpriteService;
import cn.litx.palettenav.service.NavMediaType;
import cn.litx.palettenav.service.NavLinkMetaService;
import cn.litx.palettenav.service.NavBackgroundService;
import cn.litx.palettenav.service.NavQueryService;
import cn.litx.palettenav.service.NavStatsService;
import cn.litx.palettenav.service.NavUrlValidator;
import cn.litx.palettenav.service.NavVisitRecorder;
import cn.litx.palettenav.service.NavYiyanService;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.PluginContext;
import run.halo.app.theme.router.ModelConst;

/**
 * Public standalone page: {@code GET /nav}.
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class NavPageRouter {

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private final String assetRevision = UUID.randomUUID().toString();

    private final NavQueryService queryService;
    private final NavIconSpriteService iconSpriteService;
    private final NavIconRenderer iconRenderer;
    private final NavStatsService statsService;
    private final NavFooterSanitizer footerSanitizer;
    private final NavSettingsService settingsService;
    private final NavSiteInfoService siteInfoService;
    private final NavYiyanService yiyanService;
    private final NavVisitRecorder visitRecorder;
    private final PluginContext pluginContext;
    private final NavBackgroundService backgroundService;
    private final cn.litx.palettenav.service.NavLogoService logoService;

    @Bean
    RouterFunction<ServerResponse> paletteNavPageRoute() {
        return route()
            .GET("/nav", this::render)
            .GET("/nav/", this::render)
            .GET("/nav/background", this::backgroundImage)
            .GET("/nav/background-image", this::backgroundAsset)
            .GET("/nav/logo-source", this::logoSource)
            .build();
    }

    private Mono<ServerResponse> logoSource(ServerRequest request) {
        return settingsService.get().flatMap(settings -> {
            if (!Boolean.TRUE.equals(settings.getAppearance().getRoundedLogo())) {
                return ServerResponse.notFound().build();
            }
            return logoService.fetch(resolveLogo(settings.getGeneral().getLogoUrl()))
                .flatMap(asset -> ServerResponse.ok()
                    .header(HttpHeaders.CONTENT_TYPE, asset.mediaType())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:")
                    .cacheControl(CacheControl.noStore())
                    .bodyValue(asset.bytes()))
                .switchIfEmpty(ServerResponse.notFound().build());
        });
    }

    private Mono<ServerResponse> backgroundImage(ServerRequest request) {
        return settingsService.get().flatMap(settings -> {
            var appearance = settings.getAppearance();
            var fallback = NavMediaType.VIDEO.equals(appearance.getBackgroundType())
                ? NavConstants.DEFAULT_BACKGROUND_ASSET
                : effectiveBackground(appearance.getBackgroundUrl());
            var contextPath = request.exchange().getRequest().getPath().contextPath().value();
            return (appearance.getRandomImageApi().startsWith("/")
                ? Mono.just(contextRelative(appearance.getRandomImageApi(), contextPath))
                : appearance.getRandomImageApi().isBlank() ? Mono.<String>empty()
                : Mono.just(contextPath + "/nav/background-image?_pn=" + System.currentTimeMillis()))
                .defaultIfEmpty(contextRelative(fallback, contextPath))
                .flatMap(url -> ServerResponse.ok().cacheControl(CacheControl.noStore())
                    .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                    .header("X-Content-Type-Options", "nosniff")
                    .bodyValue(url));
        });
    }

    private Mono<ServerResponse> backgroundAsset(ServerRequest request) {
        return settingsService.get().flatMap(settings -> {
            var appearance = settings.getAppearance();
            return backgroundService.image(appearance.getRandomImageApi(), appearance.getRandomImageJsonPath(),
                appearance.getRandomImageCacheSeconds())
                .flatMap(asset -> ServerResponse.ok().cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_TYPE, asset.mediaType())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:")
                    .bodyValue(asset.bytes()))
                .switchIfEmpty(ServerResponse.notFound().build());
        });
    }

    private Mono<ServerResponse> render(ServerRequest request) {
        var contextPath = request.exchange().getRequest().getPath().contextPath().value();
        var userAgent = request.headers().firstHeader(HttpHeaders.USER_AGENT);
        request.exchange().getAttributes().put(ModelConst.NO_CACHE, true);

        return Mono.zip(
                settingsService.get().flatMap(settings -> {
                    if (!Boolean.TRUE.equals(settings.getGeneral().getShowFooterStats())) {
                        return Mono.just(new PageSettings(settings, null));
                    }
                    // Statistics are optional: a slow store must not prevent navigation.
                    return statsService.dashboardStats()
                        .timeout(Duration.ofSeconds(2))
                        .map(stats -> new PageSettings(settings, stats))
                        .onErrorReturn(new PageSettings(settings, null));
                }),
                siteInfoService.get(),
                queryService.loadVisiblePageData(userAgent, contextPath)
            )
            .flatMap(tuple -> {
                var settings = tuple.getT1().settings();
                var siteInfo = tuple.getT2();
                var data = tuple.getT3();
                var general = settings.getGeneral();
                var appearance = settings.getAppearance();

                var model = new HashMap<String, Object>();
                model.put(ModelConst.TEMPLATE_ID, NavConstants.TEMPLATE_ID);
                model.put("categories", data.getCategories());
                model.put("searchEngines", data.getSearchEngines());
                model.put("quickLinks", data.getQuickLinks());
                var spriteIds = new LinkedHashSet<>(data.getSpriteIds());
                spriteIds.add(iconRenderer.spriteId(general.getIcpIcon()));
                spriteIds.add(iconRenderer.spriteId(general.getGonganbeiIcon()));
                model.put("iconSpriteHtml", iconSpriteService.render(spriteIds));
                model.put("icpIconHtml", iconRenderer.render(general.getIcpIcon(), ""));
                model.put("gonganbeiIconHtml", iconRenderer.render(general.getGonganbeiIcon(), ""));
                model.put("footerStats", tuple.getT1().stats());
                model.put("contextPath", contextPath);
                model.put("pluginVersion", pluginContext.getVersion() + "-" + assetRevision);
                model.put("defaultColor", appearance.getDefaultColor());
                model.put("defaultNightMode", appearance.getDefaultNightMode());
                model.put("showClock", appearance.getShowClock());
                model.put("showYiyan", appearance.getShowYiyan());
                model.put("showCategoryIcons", appearance.getShowCategoryIcons());
                model.put("enableHoverPreview", appearance.getEnableHoverPreview());
                model.put("roundedLogo", appearance.getRoundedLogo());
                model.put("previewLoadingImage",
                    NavUrlValidator.isSafeImageUrl(appearance.getPreviewLoadingImage())
                        ? contextRelative(appearance.getPreviewLoadingImage().trim(), contextPath)
                        : "");
                model.put("yanText", Boolean.TRUE.equals(appearance.getShowYiyan())
                    ? yiyanService.randomQuote() : "");
                var mergedBackground = NavUrlValidator.trim(appearance.getBackgroundUrl());
                var randomBackground = randomImageUrl(appearance.getRandomImageApi());
                var jsonBackground = !randomBackground.isEmpty();
                var videoBackground = NavMediaType.VIDEO.equals(appearance.getBackgroundType())
                    && NavUrlValidator.isSafeImageUrl(mergedBackground);
                model.put("defaultBackgroundMedia", appearance.getDefaultBackgroundMedia());
                model.put("randomBackgroundResolver", "");
                model.put("randomBackgroundEnabled", jsonBackground);
                model.put("backgroundDefault", NavConstants.DEFAULT_BACKGROUND_ASSET);
                model.put("backgroundFallback", NavMediaType.VIDEO.equals(appearance.getBackgroundType())
                    ? NavConstants.DEFAULT_BACKGROUND_ASSET
                    : NavUrlValidator.isSafeImageUrl(mergedBackground)
                        ? effectiveBackground(mergedBackground) : "");
                model.put("backgroundUrl", jsonBackground ? appearance.getRandomImageApi().startsWith("/")
                    ? appearance.getRandomImageApi() : "/nav/background-image?_pn=" + System.currentTimeMillis()
                    : !randomBackground.isEmpty() ? randomBackground : videoBackground
                    ? NavConstants.DEFAULT_BACKGROUND_ASSET
                    : effectiveBackground(mergedBackground));
                model.put("backgroundVideoUrl", videoBackground
                    ? contextRelative(mergedBackground, contextPath)
                    : "");
                model.put("baiduSuggestions", settings.getSearch().getEnableBaiduSuggestions());
                model.put("logoUrl", resolveLogo(general.getLogoUrl()));
                model.put("brandText", resolveBrandText(general.getBrandText(),
                    general.getSiteName(), siteInfo.title()));
                model.put("copyright", footerSanitizer.sanitize(general.getCopyright()));
                model.put("icp", general.getIcp());
                model.put("gonganbei", general.getGonganbei());
                model.put("gonganbeiCode", firstDigits(general.getGonganbei()));
                model.put("seoDescription", general.getSiteDescription());
                model.put("seoKeywords", general.getSiteKeywords());
                model.put("siteName", general.getSiteName());

                return ServerResponse.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Referrer-Policy", "strict-origin-when-cross-origin")
                    .render(NavConstants.TEMPLATE_ID, model)
                    // Count only pages that actually rendered. The recorder queues the
                    // visit; its single drain subscription runs off the request path,
                    // so a slow stats update never delays or fails the response.
                    .doOnSuccess(ignored -> {
                        if (Boolean.FALSE.equals(general.getEnableVisitStats())) {
                            return;
                        }
                        visitRecorder.record(
                            request.headers().firstHeader("X-Forwarded-For"),
                            request.headers().firstHeader("X-Real-IP"),
                            remoteAddress(request),
                            userAgent,
                            general.getTrustedProxyIps());
                    });
            });
    }

    private record PageSettings(NavSettings.Snapshot settings,
                                NavDashboardStats stats) {
    }

    /** A fresh URL per page bypasses browser caching on random-image endpoints. */
    static String randomImageUrl(String configured) {
        if (!NavUrlValidator.isSafeImageUrl(configured)) {
            return "";
        }
        var url = configured.trim();
        var fragment = url.indexOf('#');
        if (fragment >= 0) {
            url = url.substring(0, fragment);
        }
        return url + (url.contains("?") ? "&" : "?") + "_pn=" + UUID.randomUUID();
    }

    private String resolveLogo(String configured) {
        if (NavUrlValidator.isSafeImageUrl(configured)) {
            return configured.trim();
        }
        return NavConstants.DEFAULT_LOGO_ASSET;
    }

    private String resolveBrandText(String configured, String siteName, String siteTitle) {
        var direct = NavUrlValidator.trim(configured);
        if (!direct.isEmpty()) {
            return direct;
        }
        var title = NavUrlValidator.trim(siteName);
        if (title.isEmpty()) {
            title = NavUrlValidator.trim(siteTitle);
        }
        if (title.isEmpty()) {
            return "";
        }
        var dash = title.indexOf('-');
        if (dash > 0) {
            var prefix = title.substring(0, dash).trim();
            if (!prefix.isEmpty()) {
                return prefix;
            }
        }
        return title;
    }

    /**
     * The video data attribute is rendered without Thymeleaf's {@code @{...}}
     * link expression, so site-relative uploads need the context path here.
     */
    private String contextRelative(String url, String contextPath) {
        if (url.startsWith("/") && !url.startsWith("//") && !contextPath.isEmpty()) {
            return contextPath + url;
        }
        return url;
    }

    private String effectiveBackground(String configured) {
        return NavUrlValidator.isSafeImageUrl(configured)
            ? configured.trim()
            : NavConstants.DEFAULT_BACKGROUND_ASSET;
    }

    private String firstDigits(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var matcher = DIGITS.matcher(value);
        return matcher.find() ? matcher.group() : "";
    }

    private String remoteAddress(ServerRequest request) {
        var address = request.exchange().getRequest().getRemoteAddress();
        if (address == null || address.getAddress() == null) {
            return "";
        }
        return address.getAddress().getHostAddress();
    }
}
