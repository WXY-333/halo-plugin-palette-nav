package cn.litx.palettenav.service;

import cn.litx.palettenav.config.NavSettingsService;
import cn.litx.palettenav.service.NavLinkMetaService.FetchedAsset;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Group;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.MetadataOperator;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * Stores icons fetched from link targets in Halo's attachment storage.
 *
 * <p>The console form used to save the remote icon URL directly, so the public page
 * hot-linked the target site and broke whenever that site changed or blocked
 * hot-linking. Icons are now downloaded through the same pinned-address HTTP path as
 * the page metadata (see {@link NavLinkMetaService#fetchIconAsset(String)}) and
 * uploaded with Halo's {@link AttachmentService}, which delegates to the configured
 * storage policy. The form receives the attachment permalink instead of the remote
 * URL.</p>
 *
 * <p>The policy is taken from settings when configured; otherwise the highest
 * priority policy visible in Halo's own upload UI is used. The priority and hidden
 * flags are the same labels the console reads
 * ({@code storage.halo.run/policy-priority-in-upload-ui} and
 * {@code storage.halo.run/policy-hidden-in-upload-ui}). An empty group means the
 * attachment is stored without a group.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NavIconStorageService {

    static final String POLICY_PRIORITY_LABEL =
        "storage.halo.run/policy-priority-in-upload-ui";
    static final String POLICY_HIDDEN_LABEL =
        "storage.halo.run/policy-hidden-in-upload-ui";

    /**
     * Halo creates the {@link Attachment} and fills {@code status.permalink} from a
     * reconciler, so the permalink is usually available shortly after the upload
     * returns. Poll briefly instead of handing an unusable attachment to the form.
     */
    private static final Duration PERMALINK_POLL_INTERVAL = Duration.ofMillis(150);
    private static final int PERMALINK_POLL_ATTEMPTS = 12;
    private static final int MAX_LABEL_RETRIES = 3;
    /**
     * Uploads go through the storage policy (local, S3, OSS...). A hung backend would
     * otherwise keep the console "获取" request open until the browser gives up; the
     * download path already has its own 12s budget.
     */
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Marks attachments created from a remote icon URL so repeated 获取 clicks reuse the
     * existing attachment instead of uploading another copy. The label value is a
     * truncated SHA-256 of the source URL, which keeps it inside Halo's 63-character
     * label-value limit.
     */
    static final String SOURCE_LABEL = "palette-nav.halo.run/icon-source";

    private final NavLinkMetaService metaService;
    private final NavSettingsService settingsService;
    private final ReactiveExtensionClient client;
    private final AttachmentService attachmentService;

    /**
     * Downloads and stores the icon, returning the attachment permalink.
     *
     * <p>Empty means "could not be stored" (unsafe URL, unreachable host, unsupported
     * image format, no readable policy, upload failure). Callers keep the original
     * remote icon URL in that case so the form still works.</p>
     */
    public Mono<String> store(String iconUrl) {
        if (!StringUtils.hasText(iconUrl)) {
            return Mono.empty();
        }
        var url = iconUrl.trim();
        return findStoredIcon(url)
            .switchIfEmpty(Mono.defer(() -> downloadAndUpload(url)));
    }

    private Mono<String> downloadAndUpload(String url) {
        return Mono.zip(
                metaService.fetchIconAsset(url),
                settingsService.get()
            )
            .flatMap(tuple -> {
                var asset = tuple.getT1();
                var icons = tuple.getT2().getIcons();
                return resolvePolicyName(icons.getPolicyName())
                    .flatMap(policyName -> resolveGroupName(icons.getGroupName())
                        .flatMap(groupName -> upload(url, asset, policyName, groupName)));
            })
            .onErrorResume(error -> {
                log.warn("Failed to store the icon {} as an attachment: {}", url,
                    error.toString());
                return Mono.empty();
            });
    }

    /**
     * Returns the permalink of an attachment previously created from the same icon URL.
     * Lookup failures fall through to a fresh upload rather than breaking 获取.
     */
    private Mono<String> findStoredIcon(String iconUrl) {
        var options = ListOptions.builder()
            .labelSelector()
            .eq(SOURCE_LABEL, sourceKey(iconUrl))
            .end()
            .build();
        return client.listAll(Attachment.class, options, Sort.unsorted())
            .filter(attachment -> attachment.getMetadata() != null
                && attachment.getMetadata().getDeletionTimestamp() == null)
            .flatMap(attachment -> {
                var permalink = permalink(attachment);
                return permalink == null ? Mono.empty() : Mono.just(permalink);
            })
            .next()
            .onErrorResume(error -> {
                log.warn("Failed to look up an existing icon attachment for {}: {}",
                    iconUrl, error.toString());
                return Mono.empty();
            });
    }

    private static String permalink(Attachment attachment) {
        var status = attachment.getStatus();
        var value = status == null ? null : status.getPermalink();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /* --------------------------- Policy / group --------------------------- */

    private Mono<String> resolvePolicyName(String configured) {
        if (!StringUtils.hasText(configured)) {
            return defaultPolicyName();
        }
        var name = configured.trim();
        return client.fetch(Policy.class, name)
            .filter(policy -> policy.getMetadata() != null
                && policy.getMetadata().getDeletionTimestamp() == null)
            .map(policy -> name)
            // A deleted or renamed policy falls back to the automatic choice instead
            // of breaking the 获取 button for every link.
            .switchIfEmpty(Mono.defer(() -> {
                log.warn("Configured icon storage policy {} no longer exists, "
                    + "falling back to the highest priority policy", name);
                return defaultPolicyName();
            }))
            .onErrorResume(error -> defaultPolicyName());
    }

    /**
     * Mirrors Halo's upload UI: hidden policies are not offered there, and the
     * remaining ones are sorted by the priority label (higher first). Ties keep
     * Halo's default list order, i.e. the newest policy first, and the name breaks
     * any remaining tie deterministically.
     */
    private Mono<String> defaultPolicyName() {
        return client.list(Policy.class, null, null)
            .filter(policy -> !isHidden(policy.getMetadata()))
            .sort((first, second) -> {
                var firstMeta = first.getMetadata();
                var secondMeta = second.getMetadata();
                var byPriority = Integer.compare(priority(secondMeta),
                    priority(firstMeta));
                if (byPriority != 0) {
                    return byPriority;
                }
                var byCreation = compareInstants(secondMeta.getCreationTimestamp(),
                    firstMeta.getCreationTimestamp());
                if (byCreation != 0) {
                    return byCreation;
                }
                return String.valueOf(firstMeta.getName())
                    .compareTo(String.valueOf(secondMeta.getName()));
            })
            .next()
            .map(policy -> policy.getMetadata().getName())
            .switchIfEmpty(Mono.defer(() -> {
                log.warn("No visible attachment storage policy is configured; icons "
                    + "will keep referencing their remote URL");
                return Mono.empty();
            }))
            .onErrorResume(error -> {
                log.warn("Failed to list attachment storage policies: {}", error.toString());
                return Mono.empty();
            });
    }

    private static boolean isHidden(MetadataOperator metadata) {
        if (metadata == null || metadata.getDeletionTimestamp() != null) {
            return true;
        }
        var labels = metadata.getLabels();
        return labels != null
            && "true".equalsIgnoreCase(labels.get(POLICY_HIDDEN_LABEL));
    }

    private static int priority(MetadataOperator metadata) {
        var labels = metadata == null ? null : metadata.getLabels();
        var raw = labels == null ? null : labels.get(POLICY_PRIORITY_LABEL);
        if (!StringUtils.hasText(raw)) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static int compareInstants(Instant first, Instant second) {
        if (first == null && second == null) {
            return 0;
        }
        if (first == null) {
            return 1;
        }
        if (second == null) {
            return -1;
        }
        return first.compareTo(second);
    }

    private Mono<String> resolveGroupName(String configured) {
        if (!StringUtils.hasText(configured)) {
            return Mono.just("");
        }
        var name = configured.trim();
        return client.fetch(Group.class, name)
            // Match the policy check: a group that is being deleted cannot accept
            // new attachments, so fall back to "no group" like a missing one.
            .filter(group -> group.getMetadata() != null
                && group.getMetadata().getDeletionTimestamp() == null)
            .map(group -> name)
            .switchIfEmpty(Mono.defer(() -> {
                log.warn("Configured icon attachment group {} no longer exists, "
                    + "storing the icon without a group", name);
                return Mono.just("");
            }))
            .onErrorResume(error -> {
                log.warn("Failed to read the attachment group {}: {}", name,
                    error.toString());
                return Mono.just("");
            });
    }

    /* ------------------------------ Upload ------------------------------ */

    private Mono<String> upload(String iconUrl, FetchedAsset asset, String policyName,
        String groupName) {
        if (!StringUtils.hasText(policyName)) {
            return Mono.empty();
        }
        var filename = filename(iconUrl, asset.extension());
        var mediaType = MediaType.parseMediaType(asset.mediaType());
        var group = StringUtils.hasText(groupName) ? groupName : null;
        return Mono.defer(() -> attachmentService.upload(
                policyName,
                group,
                filename,
                Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(asset.bytes())),
                mediaType
            ))
            .timeout(UPLOAD_TIMEOUT)
            .flatMap(attachment -> {
                var name = attachment.getMetadata().getName();
                return tagSource(name, iconUrl)
                    .then(awaitPermalink(name, PERMALINK_POLL_ATTEMPTS));
            })
            .onErrorResume(error -> {
                log.warn("Failed to upload the icon {} to attachment storage: {}",
                    iconUrl, error.toString());
                return Mono.empty();
            });
    }

    /**
     * Labels the freshly uploaded attachment with its source URL hash. Retrying re-fetches
     * the attachment because Halo's reconciler may bump the version while it fills in the
     * permalink; replaying a stale entity would conflict forever.
     */
    private Mono<Void> tagSource(String name, String iconUrl) {
        var key = sourceKey(iconUrl);
        return Mono.defer(() -> client.fetch(Attachment.class, name)
                .flatMap(attachment -> {
                    var metadata = attachment.getMetadata();
                    if (metadata == null) {
                        return Mono.just(attachment);
                    }
                    var labels = metadata.getLabels() == null
                        ? new HashMap<String, String>()
                        : new HashMap<>(metadata.getLabels());
                    if (labels.containsKey(SOURCE_LABEL)) {
                        return Mono.just(attachment);
                    }
                    labels.put(SOURCE_LABEL, key);
                    metadata.setLabels(labels);
                    return client.update(attachment);
                })
                .switchIfEmpty(Mono.empty())
                .then())
            .retryWhen(Retry.backoff(MAX_LABEL_RETRIES, Duration.ofMillis(50))
                .filter(error -> error instanceof OptimisticLockingFailureException))
            .onErrorResume(error -> {
                log.warn("Failed to label the icon attachment {} with its source: {}",
                    name, error.toString());
                return Mono.empty();
            });
    }

    /**
     * Short, label-safe digest of the source URL. Deduplication therefore keys on the URL
     * rather than on the file name, so two different sites never share an attachment.
     */
    static String sourceKey(String iconUrl) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                .formatHex(digest.digest(iconUrl.getBytes(StandardCharsets.UTF_8)))
                .substring(0, 32);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    private Mono<String> awaitPermalink(String name, int attemptsLeft) {
        return client.fetch(Attachment.class, name)
            .flatMap(attachment -> {
                var status = attachment.getStatus();
                var permalink = status == null ? "" : status.getPermalink();
                if (StringUtils.hasText(permalink)) {
                    return Mono.just(permalink.trim());
                }
                return retryPermalink(name, attemptsLeft);
            })
            .switchIfEmpty(Mono.defer(() -> retryPermalink(name, attemptsLeft)));
    }

    private Mono<String> retryPermalink(String name, int attemptsLeft) {
        if (attemptsLeft <= 0) {
            log.warn("Attachment {} was created but never received a permalink", name);
            return Mono.empty();
        }
        return Mono.delay(PERMALINK_POLL_INTERVAL)
            .then(awaitPermalink(name, attemptsLeft - 1));
    }

    /**
     * Builds a readable filename that keeps Halo's extension/MIME validation happy:
     * the suffix must match {@link FetchedAsset#mediaType()}. A short digest of the
     * source URL is included because one host usually has several icon files
     * ({@code favicon.ico}, {@code apple-touch-icon.png}, ...): with only the host and
     * extension they would collide, and a storage policy that uses the supplied
     * filename as its object key (S3/OSS) would overwrite the first icon.
     */
    static String filename(String iconUrl, String extension) {
        var host = "";
        try {
            host = String.valueOf(URI.create(iconUrl).getHost()).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            // Fall through to the generic prefix below.
        }
        host = host.replaceAll("[^a-z0-9.-]", "-").replaceAll("^-+|-+$", "");
        if (host.length() > 80) {
            host = host.substring(0, 80);
        }
        if (host.isBlank() || "null".equals(host)) {
            host = "site";
        }
        return host + "-icon-" + sourceKey(iconUrl).substring(0, 8) + "." + extension;
    }
}