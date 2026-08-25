package com.oAT.web.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PatchApplierTest {

    @Test
    void appliesSimpleAddition() {
        String original = "line1\nline2\nline3";
        String diff = "@@ -1,3 +1,4 @@\n line1\n line2\n+line2.5\n line3";
        assertEquals("line1\nline2\nline2.5\nline3", PatchApplier.apply(original, diff));
    }

    @Test
    void appliesSimpleRemoval() {
        String original = "a\nb\nc";
        String diff = "@@ -1,3 +1,2 @@\n a\n-b\n c";
        assertEquals("a\nc", PatchApplier.apply(original, diff));
    }

    @Test
    void appliesMultiHunkWithGap() {
        String original = "1\n2\n3\n4\n5";
        String diff = "@@ -1,2 +1,2 @@\n 1\n-2\n+two\n@@ -4,2 +4,2 @@\n 4\n-5\n+five";
        assertEquals("1\ntwo\n3\n4\nfive", PatchApplier.apply(original, diff));
    }

    @Test
    void handlesEmptyDiffAsNoop() {
        String original = "a\nb";
        assertEquals("a\nb", PatchApplier.apply(original, ""));
    }

    @Test
    void throwsOnMalformedPatchLine() {
        String original = "a\nb";
        String diff = "@@ -1,2 +1,2 @@\n a\n?weird\n b";
        assertThrows(IllegalArgumentException.class, () -> PatchApplier.apply(original, diff));
    }
}
