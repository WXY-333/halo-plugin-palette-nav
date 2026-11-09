package cn.litx.palettenav.service;

import cn.litx.palettenav.dto.NavLinkMeta;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reads a public web page to derive a human readable name and icon URL.
 *
 * <p>The console form only needs the {@code <head>} of the document, so the body is
 * streamed and truncated instead of being buffered in full. The request is issued on a
 * plain socket built from the addresses that were validated first, instead of letting an
 * HTTP client resolve the host a second time: a DNS-rebinding host would otherwise pass
 * the loopback/link-local check and then be re-resolved to a reserved address when the
 * connection is opened. Redirects are followed manually so every hop goes through the
 * same validation. Loopback, link-local, multicast, RFC 1918 private networks and the
 * other non-routable / documentation ranges are rejected to keep the console endpoint
 * from acting as an SSRF proxy. Intranet pages can still be added manually; only the
 * automatic title/icon fetch refuses to reach them.</p>
 */
@Slf4j
@Service
public class NavLinkMetaService {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_NAME_CODE_POINTS = 60;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(12);
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0 Safari/537.36";
    private static final String HTML_ACCEPT =
        "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8";
    private static final String ICON_ACCEPT =
        "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5";

    /*
     * Fetching a page blocks on socket I/O, so it runs on Halo's shared
     * boundedElastic scheduler. A plugin-local scheduler would outlive the plugin
     * classloader across reloads because nothing disposes it; the shared scheduler is
     * already designed for bounded blocking work, and the socket deadlines below
     * bound each task.
     */

    private static final Pattern TITLE =
        Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title>");
    private static final Pattern META =
        Pattern.compile("(?is)<meta\\b[^>]*>");
    private static final Pattern LINK =
        Pattern.compile("(?is)<link\\b[^>]*>");
    private static final Pattern BASE =
        Pattern.compile("(?is)<base\\b[^>]*>");
    private static final Pattern TAG =
        Pattern.compile("(?is)<[^>]*>");
    private static final Pattern WHITESPACE =
        Pattern.compile("[\\s\\u00a0]+");
    private static final Pattern ATTRIBUTE = Pattern.compile(
        "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");
    private static final Pattern META_CHARSET =
        Pattern.compile("(?is)<meta[^>]+charset\\s*=\\s*[\"']?\\s*([a-zA-Z0-9._:-]+)");
    private static final Pattern HEADER_CHARSET =
        Pattern.compile("(?i)charset\\s*=\\s*[\"']?\\s*([a-zA-Z0-9._:-]+)");
    private static final Pattern ICON_SIZE =
        Pattern.compile("(?i)(\\d+)\\s*[x×]\\s*(\\d+)");

    /**
     * Fetches name/icon for an absolute http(s) URL.
     *
     * <p>Invalid or reserved targets are reported as {@link IllegalArgumentException}.
     * Network failures are not fatal: an unreachable host (including an unresolvable
     * name) falls back to the host name and {@code /favicon.ico} with
     * {@code fetched = false}. Redirects are followed, so the fallback is derived from
     * the final hop rather than from the URL that was typed.</p>
     */
    public Mono<NavLinkMeta> fetchMeta(String rawUrl) {
        var url = rawUrl == null ? "" : rawUrl.trim();
        if (!NavUrlValidator.isSafeHttpUrl(url)) {
            return Mono.error(new IllegalArgumentException("请输入合法的 http(s) 网址"));
        }
        var uri = URI.create(url);
        return resolveTarget(uri)
            .flatMap(addresses -> fetchPage(uri, addresses, MAX_REDIRECTS))
            .defaultIfEmpty(PageMeta.empty(uri))
            .onErrorResume(error -> {
                if (error instanceof IllegalArgumentException) {
                    return Mono.error(error);
                }
                log.debug("Failed to read page metadata for {}", uri, error);
                return Mono.just(PageMeta.empty(uri));
            })
            .flatMap(page -> {
                var finalUri = page.uri();
                var name = page.name().isBlank() ? hostName(finalUri) : page.name();
                var icon = page.icon().isBlank() ? fallbackIcon(finalUri) : page.icon();
                return checkedIcon(icon)
                    .map(allowedIcon -> new NavLinkMeta(url, name, allowedIcon,
                        page.fetched()));
            })
            // The icon check resolves one more host, so the budget must cover it too.
            .timeout(TOTAL_TIMEOUT)
            .onErrorResume(error -> {
                if (error instanceof IllegalArgumentException) {
                    return Mono.error(error);
                }
                log.debug("Failed to read page metadata for {}", uri, error);
                return Mono.just(new NavLinkMeta(url, hostName(uri), fallbackIcon(uri),
                    false));
            });
    }

    /**
     * Drops an icon URL that points into a network the server itself refuses to
     * fetch (loopback, link-local, CGNAT, private LAN and reserved ranges). The icon
     * is embedded in the public page as an {@code <img>} for every visitor, so a page
     * on a public host could otherwise make visitors' browsers probe reserved
     * addresses that an SSRF-guarded server request never reaches. A name that does
     * not resolve is kept: nothing here can prove it unsafe and the console may still
     * use it.
     */
    Mono<String> checkedIcon(String icon) {
        if (!NavUrlValidator.isSafeHttpUrl(icon)) {
            return Mono.just("");
        }
        return resolveTarget(URI.create(icon))
            .map(addresses -> icon)
            .onErrorResume(HostResolutionException.class, error -> Mono.just(icon))
            .onErrorResume(IllegalArgumentException.class, error -> Mono.just(""));
    }

    /**
     * Downloads an icon-like image from an absolute http(s) URL.
     *
     * <p>The same pinned-address and redirect validation as {@link #fetchMeta(String)}
     * applies, so the icon URL found in an arbitrary page cannot be used as an SSRF
     * primitive. The body is accepted only when its magic bytes identify a known image
     * format; a redirect landing page or an error page is never stored as an icon.
     * Failures resolve to {@link Mono#empty()}: callers keep the original icon URL.</p>
     */
    public Mono<FetchedAsset> fetchIconAsset(String rawUrl) {
        var url = rawUrl == null ? "" : rawUrl.trim();
        if (!NavUrlValidator.isSafeHttpUrl(url)) {
            return Mono.empty();
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException error) {
            return Mono.empty();
        }
        return resolveTarget(uri)
            .flatMap(addresses -> fetchIconAsset(uri, addresses, MAX_REDIRECTS))
            .timeout(TOTAL_TIMEOUT)
            .onErrorResume(error -> {
                log.debug("Failed to download the icon {}", uri, error);
                return Mono.empty();
            });
    }

    /* ----------------------------- HTTP ----------------------------- */

    private Mono<PageMeta> fetchPage(URI uri, List<InetAddress> addresses, int redirectsLeft) {
        return fetch(uri, addresses, redirectsLeft, HTML_ACCEPT, "Metadata",
            (finalUri, response) -> {
                var bytes = decode(response);
                if (!looksLikeHtml(response.contentType(), bytes)) {
                    return null;
                }
                var html = new String(bytes, detectCharset(bytes, response.contentType()));
                return new PageMeta(extractName(html), extractIcon(html, finalUri), true,
                    finalUri);
            });
    }

    private Mono<FetchedAsset> fetchIconAsset(URI uri, List<InetAddress> addresses,
        int redirectsLeft) {
        return fetch(uri, addresses, redirectsLeft, ICON_ACCEPT, "Icon",
            (ignored, response) -> {
            var bytes = decode(response);
            // readCapped() silently truncates at MAX_BYTES; a truncated image would
            // still carry a valid signature, so reject a body that reached the cap.
            if (bytes.length == 0 || bytes.length >= MAX_BYTES) {
                return null;
            }
            return describeAsset(bytes);
        });
    }

    /**
     * Shared request/redirect/error pipeline for both the page and the icon path, so a
     * change to the redirect budget, SSRF validation or body limit cannot apply to only
     * one of them. A {@code null} or empty result means "nothing usable was fetched".
     */
    private <T> Mono<T> fetch(URI uri, List<InetAddress> addresses, int redirectsLeft,
        String accept, String label, ResponseDecoder<T> decoder) {
        return Mono.fromCallable(() -> request(uri, addresses, accept))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(response -> {
                if (isRedirect(response.status())) {
                    var next = redirectTarget(uri, response.location(), redirectsLeft);
                    if (next == null) {
                        return Mono.empty();
                    }
                    // Every hop is resolved and validated again: an attacker controlled
                    // page could otherwise redirect from a public host to a loopback
                    // address.
                    return resolveTarget(next)
                        .flatMap(nextAddresses -> fetch(next, nextAddresses,
                            redirectsLeft - 1, accept, label, decoder))
                        .onErrorResume(IllegalArgumentException.class,
                            error -> Mono.empty());
                }
                if (response.status() < 200 || response.status() >= 300) {
                    return Mono.empty();
                }
                try {
                    return Mono.justOrEmpty(decoder.decode(uri, response));
                } catch (IOException | RuntimeException error) {
                    log.debug("Failed to decode the {} body of {}", label, uri, error);
                    return Mono.empty();
                }
            })
            // A blocked or broken hop is treated as "unreadable" rather than a client
            // error: the URL the admin typed was valid.
            .onErrorResume(error -> {
                log.debug("{} request failed for {}", label, uri, error);
                return Mono.empty();
            });
    }

    /**
     * Decodes the body of the hop that finally answered. {@code uri} is that hop, not
     * the URL the caller passed in: relative references and the fallback icon must be
     * resolved against the page the browser would end up on after the redirects.
     */
    @FunctionalInterface
    private interface ResponseDecoder<T> {
        T decode(URI uri, RawResponse response) throws IOException;
    }

    /**
     * Resolves and validates a redirect location. Returns {@code null} when the hop is
     * missing, exhausted or leads to an unsafe target.
     */
    private static URI redirectTarget(URI current, String location, int redirectsLeft) {
        if (location == null || location.isBlank() || redirectsLeft <= 0) {
            return null;
        }
        try {
            var next = current.resolve(location.trim());
            return NavUrlValidator.isSafeHttpUrl(next.toString()) ? next : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private RawResponse request(URI uri, List<InetAddress> addresses, String accept)
        throws IOException {
        var deadline = System.nanoTime() + REQUEST_TIMEOUT.toNanos();
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        var https = "https".equals(scheme);
        var host = unbracket(uri.getHost() == null ? "" : uri.getHost());
        var port = uri.getPort() == -1 ? (https ? 443 : 80) : uri.getPort();
        var socket = connect(addresses, port, deadline);
        try {
            socket.setSoTimeout(timeoutMillis(deadline));
            Socket channel = socket;
            if (https) {
                channel = startTls(socket, host, port, deadline);
            }
            var out = new BufferedOutputStream(channel.getOutputStream());
            out.write(requestHead(host, port, https, requestTarget(uri), accept));
            out.flush();
            var in = new BufferedInputStream(channel.getInputStream());
            var head = readHead(in, deadline);
            var body = isRedirect(head.status()) ? new byte[0] : readBody(in, head, deadline);
            return new RawResponse(
                head.status(),
                head.header("location"),
                head.header("content-type"),
                head.header("content-encoding"),
                body
            );
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * Connects to one of the already validated addresses, so the name is never resolved
     * a second time (DNS rebinding).
     */
    private static Socket connect(List<InetAddress> addresses, int port, long deadline)
        throws IOException {
        IOException failure = null;
        for (var address : addresses) {
            var socket = new Socket();
            try {
                var timeout = Math.min(timeoutMillis(deadline), CONNECT_TIMEOUT_MS);
                socket.connect(new InetSocketAddress(address, port), timeout);
                return socket;
            } catch (IOException error) {
                closeQuietly(socket);
                failure = error;
            }
        }
        throw failure != null ? failure : new IOException("没有可用的目标地址");
    }

    /**
     * Layers TLS over the pinned socket. The host name is used for SNI and certificate
     * verification even though the connection itself targets an IP address.
     */
    private static Socket startTls(Socket plain, String host, int port, long deadline)
        throws IOException {
        var factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        var ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
        var parameters = ssl.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(parameters);
        ssl.setSoTimeout(timeoutMillis(deadline));
        ssl.startHandshake();
        return ssl;
    }

    private static byte[] requestHead(String host, int port, boolean https, String target,
        String accept) {
        var hostHeader = host.contains(":") ? "[" + host + "]" : host;
        if (port != (https ? 443 : 80)) {
            hostHeader = hostHeader + ":" + port;
        }
        var head = "GET " + target + " HTTP/1.1\r\n"
            + "Host: " + hostHeader + "\r\n"
            + "User-Agent: " + USER_AGENT + "\r\n"
            + "Accept: " + accept + "\r\n"
            + "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8\r\n"
            + "Connection: close\r\n\r\n";
        return head.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String requestTarget(URI uri) {
        var path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        var query = uri.getRawQuery();
        return encodeTarget(query == null ? path : path + "?" + query);
    }

    /** Percent-encodes anything that cannot appear verbatim on an HTTP request line. */
    private static String encodeTarget(String value) {
        var builder = new StringBuilder(value.length());
        for (var raw : value.getBytes(StandardCharsets.UTF_8)) {
            var byteValue = raw & 0xff;
            if (byteValue > 0x20 && byteValue < 0x7f) {
                builder.append((char) byteValue);
            } else {
                builder.append('%').append(String.format("%02X", byteValue));
            }
        }
        return builder.toString();
    }

    private static ResponseHead readHead(InputStream in, long deadline) throws IOException {
        var buffer = new ByteArrayOutputStream(1024);
        var state = 0;
        while (buffer.size() < MAX_HEADER_BYTES) {
            checkDeadline(deadline);
            var value = in.read();
            if (value == -1) {
                throw new EOFException("响应头不完整");
            }
            buffer.write(value);
            if (state == 3) {
                if (value == '\n') {
                    return parseHead(buffer.toByteArray());
                }
                state = 0;
            }
            state = switch (state) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : (value == '\r' ? 1 : 0);
                case 2 -> value == '\r' ? 3 : 0;
                default -> 0;
            };
        }
        throw new IOException("响应头过长");
    }

    private static ResponseHead parseHead(byte[] bytes) throws IOException {
        var text = new String(bytes, StandardCharsets.ISO_8859_1);
        var lines = text.split("\r\n");
        if (lines.length == 0) {
            throw new IOException("响应为空");
        }
        var parts = lines[0].trim().split("\\s+");
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (RuntimeException error) {
            throw new IOException("无法解析响应状态", error);
        }
        var headers = new HashMap<String, String>();
        for (var index = 1; index < lines.length; index++) {
            var line = lines[index];
            var colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            var name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            var value = line.substring(colon + 1).trim();
            headers.merge(name, value, (first, second) -> first + ", " + second);
        }
        return new ResponseHead(status, headers);
    }

    private static byte[] readBody(InputStream in, ResponseHead head, long deadline)
        throws IOException {
        if (head.status() < 200 || head.status() == 204 || head.status() == 304) {
            return new byte[0];
        }
        if (head.header("transfer-encoding").toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunked(in, deadline);
        }
        var lengthValue = head.header("content-length");
        if (!lengthValue.isBlank()) {
            try {
                var length = Long.parseLong(lengthValue.trim());
                if (length >= 0) {
                    return readCapped(in, (int) Math.min(length, MAX_BYTES), deadline);
                }
            } catch (NumberFormatException ignored) {
                // A malformed length falls through to reading until the connection ends.
            }
        }
        return readCapped(in, MAX_BYTES, deadline);
    }

    private static byte[] readChunked(InputStream in, long deadline) throws IOException {
        var output = new ByteArrayOutputStream(8192);
        while (output.size() < MAX_BYTES) {
            var line = readLine(in, deadline);
            var extension = line.indexOf(';');
            var hex = (extension >= 0 ? line.substring(0, extension) : line).trim();
            long size;
            try {
                size = Long.parseLong(hex, 16);
            } catch (NumberFormatException error) {
                throw new IOException("无法解析分块长度", error);
            }
            if (size <= 0) {
                break;
            }
            var remaining = (int) Math.min(size, MAX_BYTES - output.size());
            var chunk = new byte[remaining];
            readFully(in, chunk, deadline);
            output.write(chunk, 0, remaining);
            if (size > remaining) {
                break;
            }
            readLine(in, deadline);
        }
        return output.toByteArray();
    }

    private static byte[] readCapped(InputStream stream, int maxBytes, long deadline)
        throws IOException {
        var output = new ByteArrayOutputStream(Math.min(8192, Math.max(maxBytes, 1)));
        var buffer = new byte[8192];
        var total = 0;
        while (total < maxBytes) {
            checkDeadline(deadline);
            var read = stream.read(buffer, 0, Math.min(buffer.length, maxBytes - total));
            if (read == -1) {
                break;
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static void readFully(InputStream in, byte[] target, long deadline)
        throws IOException {
        var offset = 0;
        while (offset < target.length) {
            checkDeadline(deadline);
            var read = in.read(target, offset, target.length - offset);
            if (read == -1) {
                throw new EOFException("响应正文不完整");
            }
            offset += read;
        }
    }

    private static String readLine(InputStream in, long deadline) throws IOException {
        var builder = new ByteArrayOutputStream(64);
        while (builder.size() < 512) {
            checkDeadline(deadline);
            var value = in.read();
            if (value == -1) {
                throw new EOFException("响应不完整");
            }
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                builder.write(value);
            }
        }
        return builder.toString(StandardCharsets.ISO_8859_1);
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (System.nanoTime() > deadline) {
            throw new SocketTimeoutException("请求超时");
        }
    }

    private static int timeoutMillis(long deadline) {
        var remaining = (deadline - System.nanoTime()) / 1_000_000L;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, remaining));
    }

    private static byte[] decode(RawResponse response) throws IOException {
        var encoding = response.contentEncoding().toLowerCase(Locale.ROOT);
        var body = response.body();
        if (body.length == 0 || encoding.isBlank() || encoding.contains("identity")) {
            return body;
        }
        if (encoding.contains("gzip")) {
            return readCapped(new GZIPInputStream(new ByteArrayInputStream(body)),
                MAX_BYTES, Long.MAX_VALUE);
        }
        if (encoding.contains("deflate")) {
            return readCapped(new InflaterInputStream(new ByteArrayInputStream(body)),
                MAX_BYTES, Long.MAX_VALUE);
        }
        return body;
    }

    /* --------------------------- Validation -------------------------- */

    /**
     * Resolves the host once and rejects addresses that can reach the server itself,
     * link-local metadata services or a private network. The returned addresses are
     * also the ones the caller must connect to, so the name is never re-resolved.
     * RFC 1918, CGNAT and the documentation / benchmarking ranges are all blocked by
     * default; there is no internal-host allow-list.
     */
    private Mono<List<InetAddress>> resolveTarget(URI uri) {
        return Mono.fromCallable(() -> resolve(uri))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private static List<InetAddress> resolve(URI uri) {
        var host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("网址缺少主机名");
        }
        var lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost")
            || lower.endsWith(".local")) {
            throw new IllegalArgumentException("不允许访问本机、内网或保留地址");
        }
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(unbracket(host));
        } catch (UnknownHostException error) {
            // A name that does not resolve is a network failure, not an invalid
            // target: the console still wants the host-based name/icon fallback.
            throw new HostResolutionException("无法解析该网址的域名");
        }
        if (resolved.length == 0) {
            throw new HostResolutionException("无法解析该网址的域名");
        }
        var allowed = new ArrayList<InetAddress>(resolved.length);
        for (var address : resolved) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException("不允许访问本机、内网或保留地址");
            }
            allowed.add(address);
        }
        return List.copyOf(allowed);
    }

    private static String unbracket(String host) {
        return host.startsWith("[") && host.endsWith("]") && host.length() > 2
            ? host.substring(1, host.length() - 1)
            : host;
    }

    /**
     * Signals a host name that could not be resolved. It is deliberately not an
     * {@link IllegalArgumentException}: the URL is well-formed, so the caller falls
     * back to the host guess instead of rejecting the request with 400.
     */
    static final class HostResolutionException extends RuntimeException {
        HostResolutionException(String message) {
            super(message);
        }
    }

    static boolean isBlockedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        var bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isBlockedV4(bytes, 0);
        }
        if (address instanceof Inet6Address) {
            return isBlockedV6(bytes);
        }
        return false;
    }

    /**
     * Reserved / non-routable IPv4 ranges, read from {@code offset} so the same rules
     * can validate an IPv4 address embedded in a transition IPv6 prefix.
     *
     * <p>Private LAN ranges are blocked on purpose: the console endpoint must not be
     * usable as an SSRF primitive, so an intranet shortcut never reaches this outbound
     * reader even when the configured role is only {@code palette-nav-role-manage}.</p>
     */
    private static boolean isBlockedV4(byte[] bytes, int offset) {
        var first = bytes[offset] & 0xff;
        var second = bytes[offset + 1] & 0xff;
        var third = bytes[offset + 2] & 0xff;
        return first == 0 // 0.0.0.0/8
            || first == 10 // 10.0.0.0/8
            || first == 127 // loopback
            || (first == 169 && second == 254) // 169.254.0.0/16 link-local
            || (first == 100 && (second & 0xc0) == 64) // 100.64.0.0/10 CGNAT
            || (first == 172 && second >= 16 && second <= 31) // 172.16.0.0/12
            || (first == 192 && second == 0 && (third == 0 || third == 2)) // 192.0.0.0/24, 192.0.2.0/24
            || (first == 192 && second == 88 && third == 99) // 192.88.99.0/24 6to4 relay
            || (first == 192 && second == 168) // 192.168.0.0/16
            || (first == 198 && (second == 18 || second == 19)) // 198.18.0.0/15
            || (first == 198 && second == 51 && third == 100) // 198.51.100.0/24 TEST-NET-2
            || (first == 203 && second == 0 && third == 113) // 203.0.113.0/24 TEST-NET-3
            || first >= 240; // 240.0.0.0/4 reserved / broadcast
    }

    /**
     * Only {@code 2000::/3} is globally routable unicast IPv6. Everything outside it
     * is rejected: {@code ::/96} (IPv4-compatible), {@code ::ffff:0:0/96}
     * (IPv4-mapped), {@code 64:ff9b::/96} (NAT64), {@code fc00::/7} (unique-local),
     * {@code fe80::/10} (link-local) and multicast all reach the host itself or an
     * IPv4 target through a translation prefix. The two transition prefixes inside
     * {@code 2000::/3} that carry an IPv4 address (6to4 and Teredo) are decoded and
     * checked against the IPv4 rules. ORCHID, benchmarking and the newer documentation
     * prefix inside {@code 2000::/3} are rejected explicitly.
     */
    private static boolean isBlockedV6(byte[] bytes) {
        if ((bytes[0] & 0xe0) != 0x20) {
            return true;
        }
        // 2001:db8::/32 is documentation-only and never routed.
        if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d
            && (bytes[3] & 0xff) == 0xb8) {
            return true;
        }
        // 2001:10::/28 (ORCHID) and 2001:20::/28 (ORCHIDv2) are not globally routable;
        // 2001:2::/48 is the benchmarking range.
        if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x00) {
            var fourth = bytes[3] & 0xff;
            if ((fourth & 0xf0) == 0x10 || (fourth & 0xf0) == 0x20) {
                return true;
            }
            if (fourth == 0x02 && bytes[4] == 0 && bytes[5] == 0) {
                return true;
            }
        }
        // 3fff::/20 is reserved for documentation.
        if (bytes[0] == 0x3f && (bytes[1] & 0xff) == 0xff && (bytes[2] & 0xf0) == 0x00) {
            return true;
        }
        // 6to4 (2002::/16) embeds the peer IPv4 address in bytes 2..5.
        if (bytes[0] == 0x20 && bytes[1] == 0x02) {
            return isBlockedV4(bytes, 2);
        }
        // Teredo (2001:0::/32) carries the client IPv4 in the last 32 bits, inverted.
        if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0
            && bytes[3] == 0) {
            var client = new byte[] {
                (byte) ~bytes[12], (byte) ~bytes[13],
                (byte) ~bytes[14], (byte) ~bytes[15]
            };
            return isBlockedV4(client, 0);
        }
        return false;
    }

    /* ----------------------------- Parsing ---------------------------- */

    static String extractName(String html) {
        var siteName = metaContent(html, "property", "og:site_name");
        if (siteName.isBlank()) {
            siteName = metaContent(html, "name", "application-name");
        }
        if (!siteName.isBlank()) {
            return cleanText(siteName);
        }
        var matcher = TITLE.matcher(html);
        return matcher.find() ? cleanText(matcher.group(1)) : "";
    }

    /**
     * Picks the highest quality icon declaration in the document and resolves it to an
     * absolute URL. Returns an empty string when nothing usable is declared.
     */
    static String extractIcon(String html, URI documentUri) {
        var base = documentBase(html, documentUri);
        var matcher = LINK.matcher(html);
        var bestHref = "";
        var bestPriority = 0;
        var bestSize = -1;
        while (matcher.find()) {
            var attributes = attributes(matcher.group());
            var rel = attributes.getOrDefault("rel", "").toLowerCase(Locale.ROOT);
            var href = attributes.getOrDefault("href", "").trim();
            if (href.isEmpty()) {
                continue;
            }
            var priority = iconPriority(rel);
            if (priority == 0) {
                continue;
            }
            var size = iconSize(attributes.getOrDefault("sizes", ""));
            if (priority > bestPriority || (priority == bestPriority && size > bestSize)) {
                bestPriority = priority;
                bestSize = size;
                bestHref = href;
            }
        }
        if (bestHref.isEmpty()) {
            return "";
        }
        return resolveUrl(base, bestHref);
    }

    private static int iconPriority(String rel) {
        if (rel.contains("apple-touch-icon")) {
            return 3;
        }
        if (rel.contains("mask-icon")) {
            return 0;
        }
        return rel.contains("icon") ? 2 : 0;
    }

    private static int iconSize(String sizes) {
        var value = sizes.trim().toLowerCase(Locale.ROOT);
        if (value.equals("any")) {
            return 1024; // vector sources are preferable at any rendered size
        }
        var matcher = ICON_SIZE.matcher(value);
        var largest = 0;
        while (matcher.find()) {
            largest = Math.max(largest,
                Math.max(iconDimension(matcher.group(1)), iconDimension(matcher.group(2))));
        }
        return largest;
    }

    /** Page authors can put any number in {@code sizes}; overflow must not fail the fetch. */
    private static int iconDimension(String value) {
        try {
            return (int) Math.min(Integer.MAX_VALUE, Long.parseLong(value));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static URI documentBase(String html, URI documentUri) {
        var matcher = BASE.matcher(html);
        if (!matcher.find()) {
            return documentUri;
        }
        var href = attributes(matcher.group()).getOrDefault("href", "");
        var resolved = resolveUrl(documentUri, href);
        return resolved.isEmpty() ? documentUri : URI.create(resolved);
    }

    private static String metaContent(String html, String attribute, String expected) {
        var matcher = META.matcher(html);
        while (matcher.find()) {
            var attributes = attributes(matcher.group());
            if (expected.equalsIgnoreCase(attributes.getOrDefault(attribute, "").trim())) {
                return attributes.getOrDefault("content", "");
            }
        }
        return "";
    }

    private static Map<String, String> attributes(String tag) {
        var result = new HashMap<String, String>();
        var matcher = ATTRIBUTE.matcher(tag);
        while (matcher.find()) {
            var value = matcher.group(2) != null ? matcher.group(2)
                : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            result.putIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), value);
        }
        return result;
    }

    private static String cleanText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        var text = TAG.matcher(raw).replaceAll(" ");
        text = HtmlUtils.htmlUnescape(text);
        text = WHITESPACE.matcher(text).replaceAll(" ").trim();
        var codePoints = text.codePointCount(0, text.length());
        if (codePoints > MAX_NAME_CODE_POINTS) {
            text = text.substring(0, text.offsetByCodePoints(0, MAX_NAME_CODE_POINTS)) + "…";
        }
        return text;
    }

    /**
     * Resolves a possibly relative reference and only keeps absolute http(s) results,
     * so {@code javascript:}, {@code data:} and malformed icon URLs are dropped. The
     * reference is HTML-unescaped first, because attribute values keep {@code &amp;}.
     * URLs carrying userinfo are dropped so embedded credentials are never stored as an
     * icon and rendered into the public page.
     */
    private static String resolveUrl(URI base, String reference) {
        var value = reference == null ? "" : HtmlUtils.htmlUnescape(reference).trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("//")) {
            value = base.getScheme() + ":" + value;
        }
        try {
            var resolved = base.resolve(value);
            if (resolved.getUserInfo() != null) {
                return "";
            }
            var result = resolved.toString();
            return NavUrlValidator.isSafeHttpUrl(result) ? result : "";
        } catch (IllegalArgumentException error) {
            return "";
        }
    }

    private static String hostName(URI uri) {
        var host = uri.getHost() == null ? "" : uri.getHost();
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        return host.isBlank() ? uri.toString() : host;
    }

    /**
     * Builds the fallback icon from the host only. Using the raw authority would copy
     * any {@code user:pass@} userinfo into the stored icon, which the public page then
     * renders for every visitor.
     */
    static String fallbackIcon(URI uri) {
        var host = uri.getHost();
        if (uri.getScheme() == null || host == null || host.isBlank()) {
            return "";
        }
        var port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
        var candidate = uri.getScheme() + "://" + host + port + "/favicon.ico";
        return NavUrlValidator.isSafeHttpUrl(candidate) ? candidate : "";
    }

    /* ----------------------------- Charset ---------------------------- */

    static Charset detectCharset(byte[] bytes, String contentType) {
        var fromHeader = charsetFrom(HEADER_CHARSET.matcher(
            contentType == null ? "" : contentType));
        if (fromHeader != null) {
            return fromHeader;
        }
        var head = new String(bytes, 0, Math.min(bytes.length, 4096),
            StandardCharsets.ISO_8859_1);
        var fromMeta = charsetFrom(META_CHARSET.matcher(head));
        return fromMeta == null ? StandardCharsets.UTF_8 : fromMeta;
    }

    private static Charset charsetFrom(java.util.regex.Matcher matcher) {
        if (!matcher.find()) {
            return null;
        }
        try {
            return Charset.forName(matcher.group(1));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static boolean looksLikeHtml(String contentType, byte[] bytes) {
        var type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!type.isBlank()) {
            return type.contains("html") || type.contains("xml");
        }
        for (var value : bytes) {
            if (value == ' ' || value == '\n' || value == '\r' || value == '\t') {
                continue;
            }
            return value == '<';
        }
        return false;
    }

    /**
     * Builds the upload descriptor from the downloaded bytes. The {@code Content-Type}
     * header is deliberately ignored: many icon endpoints answer with
     * {@code application/octet-stream}, while an error or redirect landing page may
     * claim to be an image. Only a recognised file signature is accepted.
     *
     * <p>SVG is rejected on purpose. Halo serves attachments inline, so a crafted SVG
     * stored from an arbitrary site would run its scripts on this origin when its
     * permalink is opened directly. The original remote URL is kept instead, and both
     * the public page and the console preview load it through {@code <img>}, where
     * scripts never execute.</p>
     */
    static FetchedAsset describeAsset(byte[] bytes) {
        var extension = detectImageExtension(bytes);
        if (extension == null) {
            return null;
        }
        var mediaType = switch (extension) {
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "ico" -> "image/vnd.microsoft.icon";
            case "bmp" -> "image/bmp";
            case "avif" -> "image/avif";
            default -> "";
        };
        return new FetchedAsset(bytes, extension, mediaType);
    }

    private static String detectImageExtension(byte[] bytes) {
        if (bytes.length >= 8
            && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
            && bytes[3] == 'G' && bytes[4] == '\r' && bytes[5] == '\n'
            && bytes[6] == 0x1a && bytes[7] == '\n') {
            return "png";
        }
        if (bytes.length >= 3
            && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
            && (bytes[2] & 0xff) == 0xff) {
            return "jpg";
        }
        if (bytes.length >= 6
            && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
            && bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9')
            && bytes[5] == 'a') {
            return "gif";
        }
        if (bytes.length >= 12
            && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
            && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "webp";
        }
        if (bytes.length >= 4
            && bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 1 && bytes[3] == 0) {
            return "ico";
        }
        if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') {
            return "bmp";
        }
        if (bytes.length >= 12
            && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p'
            && bytes[8] == 'a' && bytes[9] == 'v' && bytes[10] == 'i'
            && (bytes[11] == 'f' || bytes[11] == 's')) {
            return "avif";
        }
        return null;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // The connection is being discarded anyway.
        }
    }

    private record ResponseHead(int status, Map<String, String> headers) {
        String header(String name) {
            return headers.getOrDefault(name, "");
        }
    }

    private record RawResponse(
        int status,
        String location,
        String contentType,
        String contentEncoding,
        byte[] body
    ) {
    }

    /**
     * @param uri the hop that produced this metadata (the final redirect target) when
     *     {@code fetched} is true, otherwise the URL the caller asked for
     */
    private record PageMeta(String name, String icon, boolean fetched, URI uri) {
        static PageMeta empty(URI uri) {
            return new PageMeta("", "", false, uri);
        }
    }

    /**
     * A downloaded icon with its detected extension and MIME type.
     *
     * <p>The extension is used in the attachment filename because Halo validates that
     * the filename suffix matches the supplied media type.</p>
     */
    public record FetchedAsset(byte[] bytes, String extension, String mediaType) {
    }
}
