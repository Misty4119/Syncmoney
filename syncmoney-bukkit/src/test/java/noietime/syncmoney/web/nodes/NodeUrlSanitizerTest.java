package noietime.syncmoney.web.nodes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeUrlSanitizerTest {

    @Test
    void removesUserInfoQueryAndFragmentWhileKeepingOriginAndPath() {
        assertEquals("https://node.example/base",
                NodeUrlSanitizer.sanitizeForDisplay(
                        "https://synthetic-user:synthetic-password@node.example/base?mode=health#status"));
    }

    @Test
    void removesQueryAndFragmentEvenWhenUrlHasNoUserInfo() {
        assertEquals("https://node.example:8443/base/path",
                NodeUrlSanitizer.sanitizeForDisplay(
                        "https://node.example:8443/base/path?token=synthetic-query#synthetic-fragment"));
    }

    @Test
    void labelsMalformedUrlsWithoutEchoingTheirContents() {
        assertEquals("[invalid URL]",
                NodeUrlSanitizer.sanitizeForDisplay("https://synthetic-user:synthetic-password@node.example/%zz"));
    }
}
