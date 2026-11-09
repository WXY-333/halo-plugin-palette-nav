package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NavLinkMetaServiceTest {

    private static final URI PAGE = URI.create("https://www.example.com/blog/post?id=1");

    @Test
    void extractsAndDecodesTitle() {
        var html = """
            <html><head>
              <title>  Example &amp; Co&nbsp;站点 </title>
            </head></html>
            """;
        assertEquals("Example & Co 站点", NavLinkMetaService.extractName(html));
    }

    @Test
    void prefersOpenGraphSiteName() {
        var html = """
            <head>
              <meta property="og:site_name" content="Example 官网">
              <title>Example - 首页</title>
            </head>
            """;
        assertEquals("Example 官网", NavLinkMetaService.extractName(html));
    }

    @Test
    void resolvesRelativeIconAgainstDocumentBase() {
        var html = """
            <head>
              <link rel="shortcut icon" href="/static/icon.png">
            </head>
            """;
        assertEquals("https://www.example.com/static/icon.png",
            NavLinkMetaService.extractIcon(html, PAGE));
    }

    @Test
    void honorsBaseHref() {
        var html = """
            <head>
              <base href="https://cdn.example.org/assets/">
              <link rel="icon" href="favicon.svg">
            </head>
            """;
        assertEquals("https://cdn.example.org/assets/favicon.svg",
            NavLinkMetaService.extractIcon(html, PAGE));
    }

    @Test
    void prefersAppleTouchIconOverSmallFavicon() {
        var html = """
            <head>
              <link rel="icon" sizes="32x32" href="/favicon-32.png">
              <link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
            </head>
            """;
        assertEquals("https://www.example.com/apple-touch-icon.png",
            NavLinkMetaService.extractIcon(html, PAGE));
    }

    @Test
    void prefersVectorIconAmongIcons() {
        var html = """
            <head>
              <link rel="icon" sizes="32x32" href="/favicon-32.png">
              <link rel="icon" sizes="any" href="/icon.svg">
            </head>
            """;
        assertEquals("https://www.example.com/icon.svg",
            NavLinkMetaService.extractIcon(html, PAGE));
    }

    @Test
    void dropsUnsafeIconUrls() {
        var html = """
            <head>
              <link rel="icon" href="javascript:alert(1)">
            </head>
            """;
        assertEquals("", NavLinkMetaService.extractIcon(html, PAGE));
    }

    @Test
    void unescapesEntitiesInIconReferences() {
        var html = """
            <head>
              <link rel="icon" href="/favicon.ico?v=1&amp;v=2">
            </head>
            """;
        assertEquals("https://www.example.com/favicon.ico?v=1&v=2",
            NavLinkMetaService.extractIcon(html, PAGE));
    }

    @Test
    void ignoresOverflowingIconSizes() {
        var html = """
            <head>
              <link rel="icon" sizes="9999999999999x1" href="/big.ico">
            </head>
            """;
        assertEquals("https://www.example.com/big.ico",
            NavLinkMetaService.extractIcon(html, PAGE));
    }

    @Test
    void dropsIconUrlsCarryingUserInfo() {
        var html = """
            <head>
              <link rel="icon" href="https://user:pass@cdn.example.org/icon.png">
            </head>
            """;
        assertEquals("", NavLinkMetaService.extractIcon(html, PAGE));
    }

    @Test
    void fallbackIconOmitsCredentials() {
        assertEquals("https://example.com:8443/favicon.ico",
            NavLinkMetaService.fallbackIcon(
                URI.create("https://user:pass@example.com:8443/page")));
    }

    @Test
    void skipsMaskIcon() {
        var html = """
            <head>
              <link rel="mask-icon" href="/safari-pinned-tab.svg">
            </head>
            """;
        assertEquals("", NavLinkMetaService.extractIcon(html, PAGE));
    }

    @Test
    void dropsFetchedIconsPointingAtReservedAddresses() {
        var service = new NavLinkMetaService();
        // The public page embeds the icon as <img>, so a page on a public host must
        // not be able to hand the console an icon that probes loopback / link-local /
        // private networks.
        assertEquals("", service.checkedIcon("http://127.0.0.1/favicon.ico").block());
        assertEquals("", service.checkedIcon("http://169.254.169.254/latest/icon.png")
            .block());
        assertEquals("", service.checkedIcon("http://192.168.1.1/favicon.ico").block());
        assertEquals("", service.checkedIcon("http://10.0.0.5/favicon.ico").block());
        // Globally routable targets and already-invalid values keep their meaning.
        assertEquals("http://8.8.8.8/favicon.ico",
            service.checkedIcon("http://8.8.8.8/favicon.ico").block());
        assertEquals("", service.checkedIcon("").block());
    }

    @Test
    void detectsCharsetFromMetaWhenHeaderIsSilent() {
        var bytes = "<meta charset=\"gbk\"><title>中文</title>"
            .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(Charset.forName("GBK"),
            NavLinkMetaService.detectCharset(bytes, "text/html"));
    }

    @Test
    void prefersCharsetFromHeader() {
        var bytes = "<meta charset=\"gbk\">".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(StandardCharsets.UTF_8,
            NavLinkMetaService.detectCharset(bytes, "text/html; charset=utf-8"));
    }

    @Test
    void blocksReservedAddresses() throws Exception {
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("169.254.169.254")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("0.0.0.0")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("100.100.100.200")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("224.0.0.1")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("::1")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("fe80::1")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("fc00::1")));
        // Transition prefixes that embed an IPv4 target must not bypass the check.
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("64:ff9b::7f00:1")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("::127.0.0.1")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("2002:7f00:1::")));
        // The documentation range is never routed.
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("2001:db8::1")));
    }

    @Test
    void blocksPrivateLanAndDocumentationRanges() throws Exception {
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("10.0.0.5")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("10.255.255.255")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("172.16.0.1")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("172.31.255.255")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("192.168.1.10")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("192.0.2.1")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("198.51.100.1")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("203.0.113.1")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("192.88.99.1")));
        // Non-global IPv6 prefixes added with the private-network block.
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("2001:10::1")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("2001:20::1")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("2001:2::1")));
        assertTrue(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("3fff::1")));
    }

    @Test
    void allowsGlobalUnicastAddresses() throws Exception {
        assertFalse(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("8.8.8.8")));
        assertFalse(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("11.0.0.1")));
        // Just outside 172.16.0.0/12.
        assertFalse(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("172.15.255.255")));
        assertFalse(NavLinkMetaService.isBlockedAddress(InetAddress.getByName("172.32.0.1")));
        assertFalse(NavLinkMetaService.isBlockedAddress(
            InetAddress.getByName("2001:4860:4860::8888")));
    }

    @Test
    void rejectsPrivateAndReservedTargetsBeforeConnecting() {
        var service = new NavLinkMetaService();
        for (var url : java.util.List.of(
            "http://127.0.0.1/",
            "http://10.0.0.5/",
            "http://172.16.0.1/",
            "http://192.168.1.1/",
            "http://169.254.169.254/",
            "http://[::1]/",
            "http://[fc00::1]/"
        )) {
            assertThrows(IllegalArgumentException.class,
                () -> service.fetchMeta(url).block(), url);
        }
    }

    @Test
    void describesCommonIconFormatsFromMagicBytes() {
        var png = new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0};
        var asset = NavLinkMetaService.describeAsset(png);
        assertEquals("png", asset.extension());
        assertEquals("image/png", asset.mediaType());

        var ico = new byte[] {0, 0, 1, 0, 1, 0};
        assertEquals("ico", NavLinkMetaService.describeAsset(ico).extension());
    }

    @Test
    void rejectsNonImageBodies() {
        var html = "<!doctype html><html><body>not an icon</body></html>"
            .getBytes(StandardCharsets.UTF_8);
        assertEquals(null, NavLinkMetaService.describeAsset(html));
        assertEquals(null, NavLinkMetaService.describeAsset(new byte[0]));
    }

    @Test
    void rejectsSvgIconsSoTheyAreNeverStoredAsExecutableAttachments() {
        var plainSvg = """
            <?xml version="1.0"?>
            <svg xmlns="http://www.w3.org/2000/svg"></svg>
            """.getBytes(StandardCharsets.UTF_8);
        assertEquals(null, NavLinkMetaService.describeAsset(plainSvg));

        var hostileSvg = """
            <?xml version="1.0"?>
            <svg xmlns="http://www.w3.org/2000/svg">
              <script>alert(document.domain)</script>
            </svg>
            """.getBytes(StandardCharsets.UTF_8);
        assertEquals(null, NavLinkMetaService.describeAsset(hostileSvg));
    }

    @Test
    void trimsAndTruncatesLongTitles() {
        var title = "标题".repeat(80);
        var html = "<title>" + title + "</title>";
        var name = NavLinkMetaService.extractName(html);
        assertTrue(name.endsWith("…"));
        assertEquals(61, name.codePointCount(0, name.length()));
    }
}
