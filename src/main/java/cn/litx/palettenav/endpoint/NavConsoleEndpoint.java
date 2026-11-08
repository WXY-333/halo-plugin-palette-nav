package cn.litx.palettenav.endpoint;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import cn.litx.palettenav.constants.NavConstants;
import cn.litx.palettenav.dto.NavDashboardStats;
import cn.litx.palettenav.dto.NavLinkMeta;
import cn.litx.palettenav.dto.NavLinkMetaRequest;
import cn.litx.palettenav.dto.OkResult;
import cn.litx.palettenav.dto.ReorderRequest;
import cn.litx.palettenav.service.NavConsoleService;
import cn.litx.palettenav.service.NavIconStorageService;
import cn.litx.palettenav.service.NavLinkMetaService;
import cn.litx.palettenav.service.NavNotFoundException;
import cn.litx.palettenav.service.NavStatsService;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Console-only endpoints: reorder/cascade-delete and link metadata.
 *
 * <p>The routes are described with springdoc's {@link SpringdocRouteBuilder} so they
 * appear in the generated OpenAPI document and TypeScript API client. Request bodies are
 * read through {@link DataBufferUtils#join} with an explicit byte limit because relying on
 * {@code Content-Length} alone would let a chunked request bypass the size cap.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NavConsoleEndpoint implements CustomEndpoint {

    private static final String TAG = "NavConsoleV1alpha1";
    private static final int MAX_REORDER_BYTES = 1024 * 1024;
    private static final int MAX_LINK_META_BYTES = 16 * 1024;

    /**
     * Jackson 3's shared mapper is used instead of injecting a bean: plugin
     * application contexts do not expose the host's {@code JsonMapper} bean.
     */
    private static final JsonMapper JSON_MAPPER = JsonMapper.shared();

    private final NavConsoleService service;
    private final NavLinkMetaService metaService;
    private final NavIconStorageService iconStorageService;
    private final NavStatsService statsService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .POST("/reorder", this::reorder, builder -> builder
                .operationId("ReorderPaletteNavData")
                .tag(TAG)
                .description("批量重排指定模型实体的 sort 值。")
                .requestBody(requestBodyBuilder()
                    .required(true)
                    .implementation(ReorderRequest.class))
                .response(responseBuilder()
                    .responseCode("200")
                    .description("排序已保存")
                    .implementation(OkResult.class))
                .response(responseBuilder()
                    .responseCode("400")
                    .description("排序参数不合法"))
                .response(responseBuilder()
                    .responseCode("500")
                    .description("服务内部错误")))
            // POST rather than GET: the endpoint downloads the icon and creates a
            // Halo attachment, so it must not be treated as a safe, retryable read.
            .POST("/link-meta", this::linkMeta, builder -> builder
                .operationId("FetchPaletteNavLinkMeta")
                .tag(TAG)
                .description("读取目标网页的名称与图标，用于新增/编辑链接时自动填充。"
                    + "图标会转存到 Halo 附件并按设置返回附件地址；转存失败时返回原图标地址。"
                    + "目标网页不可达时仍返回 200，fetched=false，并按域名回退名称和图标。")
                .requestBody(requestBodyBuilder()
                    .required(true)
                    .implementation(NavLinkMetaRequest.class))
                .response(responseBuilder()
                    .responseCode("200")
                    .description("网页名称与图标")
                    .implementation(NavLinkMeta.class))
                .response(responseBuilder()
                    .responseCode("400")
                    .description("网址不合法或指向保留地址"))
                .response(responseBuilder()
                    .responseCode("413")
                    .description("请求内容过大"))
                .response(responseBuilder()
                    .responseCode("500")
                    .description("服务内部错误")))
            .GET("/stats", this::stats, builder -> builder
                .operationId("FetchPaletteNavStats")
                .tag(TAG)
                .description("读取导航页仪表盘数据：链接数量、今日浏览量、今日独立访客、"
                    + "累计浏览量以及最近 7 天的每日统计。")
                .response(responseBuilder()
                    .responseCode("200")
                    .description("仪表盘统计数据")
                    .implementation(NavDashboardStats.class))
                .response(responseBuilder()
                    .responseCode("500")
                    .description("服务内部错误")))
            .DELETE("/category/{name}", this::deleteCategory, builder -> builder
                .operationId("DeletePaletteNavCategory")
                .tag(TAG)
                .description("删除分类及其全部链接。")
                .parameter(parameterBuilder()
                    .name("name")
                    .in(ParameterIn.PATH)
                    .required(true)
                    .description("分类的 metadata.name"))
                .response(responseBuilder()
                    .responseCode("204")
                    .description("删除成功"))
                .response(responseBuilder()
                    .responseCode("404")
                    .description("分类不存在"))
                .response(responseBuilder()
                    .responseCode("500")
                    .description("服务内部错误")))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion(NavConstants.CONSOLE_API_GROUP, NavConstants.API_VERSION);
    }

    private Mono<ServerResponse> reorder(ServerRequest request) {
        return readJsonBody(request, ReorderRequest.class, MAX_REORDER_BYTES, "排序参数不能为空")
            .flatMap(service::reorder)
            .then(ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .bodyValue(new OkResult(true)))
            .onErrorResume(DataBufferLimitException.class,
                error -> error(HttpStatus.CONTENT_TOO_LARGE, "排序参数过大"))
            .onErrorResume(IllegalArgumentException.class,
                error -> error(HttpStatus.BAD_REQUEST, message(error)))
            .onErrorResume(error -> {
                log.error("Failed to reorder Palette Nav entities", error);
                return error(HttpStatus.INTERNAL_SERVER_ERROR, "排序失败，请稍后重试");
            });
    }

    private Mono<ServerResponse> linkMeta(ServerRequest request) {
        return readJsonBody(request, NavLinkMetaRequest.class, MAX_LINK_META_BYTES,
                "请求内容不能为空")
            .flatMap(body -> metaService.fetchMeta(body.url()))
            .flatMap(meta -> {
                if (!meta.fetched()) {
                    // The icon is only a host-guessed /favicon.ico fallback here.
                    // Downloading and storing it would spend another full outbound
                    // timeout on a URL that most likely 404s, so keep it as is.
                    return Mono.just(meta);
                }
                return iconStorageService.store(meta.icon())
                    .map(icon -> new NavLinkMeta(meta.url(), meta.name(), icon,
                        meta.fetched()))
                    // The icon is optional: if it cannot be stored, keep the remote
                    // URL so the console form still gets a usable value.
                    .defaultIfEmpty(meta);
            })
            .flatMap(meta -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .bodyValue(meta))
            .onErrorResume(DataBufferLimitException.class,
                error -> error(HttpStatus.CONTENT_TOO_LARGE, "请求内容过大"))
            .onErrorResume(IllegalArgumentException.class,
                error -> error(HttpStatus.BAD_REQUEST, message(error)))
            .onErrorResume(error -> {
                log.error("Failed to fetch nav link metadata", error);
                return error(HttpStatus.INTERNAL_SERVER_ERROR, "获取网页信息失败，请稍后重试");
            });
    }

    private Mono<ServerResponse> stats(ServerRequest request) {
        return statsService.dashboardStats()
            .flatMap(stats -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .bodyValue(stats))
            .onErrorResume(error -> {
                log.error("Failed to load Palette Nav dashboard stats", error);
                return error(HttpStatus.INTERNAL_SERVER_ERROR, "读取统计失败，请稍后重试");
            });
    }

    private Mono<ServerResponse> deleteCategory(ServerRequest request) {
        return service.deleteCategory(request.pathVariable("name"))
            .then(ServerResponse.noContent().build())
            .onErrorResume(NavNotFoundException.class,
                error -> error(HttpStatus.NOT_FOUND, message(error)))
            .onErrorResume(IllegalArgumentException.class,
                error -> error(HttpStatus.BAD_REQUEST, message(error)))
            .onErrorResume(error -> {
                log.error("Failed to delete Palette Nav category", error);
                return error(HttpStatus.INTERNAL_SERVER_ERROR, "删除分类失败，请稍后重试");
            });
    }

    /**
     * Reads and parses a JSON body with a hard byte limit, so chunked requests cannot
     * bypass the {@code Content-Length} precheck.
     */
    private <T> Mono<T> readJsonBody(ServerRequest request, Class<T> type, int maxBytes,
        String emptyMessage) {
        return DataBufferUtils.join(request.body(BodyExtractors.toDataBuffers()), maxBytes)
            .map(this::copyBytes)
            .flatMap(bytes -> {
                if (bytes.length == 0) {
                    return Mono.error(new IllegalArgumentException(emptyMessage));
                }
                try {
                    var value = JSON_MAPPER.readValue(bytes, type);
                    return value == null
                        ? Mono.error(new IllegalArgumentException(emptyMessage))
                        : Mono.just(value);
                } catch (JacksonException error) {
                    return Mono.error(new IllegalArgumentException("请求内容不是合法的 JSON",
                        error));
                }
            })
            .switchIfEmpty(Mono.error(new IllegalArgumentException(emptyMessage)));
    }

    private byte[] copyBytes(DataBuffer buffer) {
        try {
            var bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private Mono<ServerResponse> error(HttpStatus status, String message) {
        return ServerResponse.status(status)
            .contentType(APPLICATION_JSON)
            .cacheControl(CacheControl.noStore())
            .bodyValue(new NavErrorResponse(message, List.of()));
    }

    private String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Schema(name = "NavConsoleError", description = "Console API error response.")
    public record NavErrorResponse(String message, List<String> errors) {
    }
}