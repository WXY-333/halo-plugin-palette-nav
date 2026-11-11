package cn.litx.palettenav.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NavPageRouterTest {
    @Test
    void randomImagesUseFreshUrlsAndPreserveExistingQuery() {
        var first = NavPageRouter.randomImageUrl("https://example.com/random?kind=nature#image");
        var second = NavPageRouter.randomImageUrl("https://example.com/random?kind=nature#image");
        assertTrue(first.startsWith("https://example.com/random?kind=nature&_pn="));
        assertNotEquals(first, second);
        assertTrue(NavPageRouter.randomImageUrl("/random").startsWith("/random?_pn="));
        assertEquals("", NavPageRouter.randomImageUrl("javascript:alert(1)"));
        assertEquals("", NavPageRouter.randomImageUrl("//example.com/random"));
    }
}
