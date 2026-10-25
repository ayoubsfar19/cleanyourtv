package com.gathin.ambienttv;

import org.junit.Test;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class MediaNavigationTest {
    private final List<File> files = Arrays.asList(new File("a.jpg"), new File("b.jpg"), new File("c.jpg"));

    @Test public void wrapsBothEndsAndKeepsSinglePhotoSelected() {
        assertEquals(0, Media.step(2, 3, 1));
        assertEquals(2, Media.step(0, 3, -1));
        assertEquals(0, Media.step(0, 1, 1));
        assertEquals(0, Media.step(0, 1, -1));
    }

    @Test public void rapidRequestsAdvanceFromRequestedPosition() {
        int requested = 0;
        requested = Media.step(requested, 3, 1);
        requested = Media.step(requested, 3, 1);
        requested = Media.step(requested, 3, -1);
        assertEquals(1, requested);
    }

    @Test public void missingRememberedPhotoStartsAtDirectionalEndpoint() {
        int removed = Media.indexOf(files, "removed.jpg");
        assertEquals(-1, removed);
        assertEquals(0, Media.step(removed, files.size(), 1));
        assertEquals(2, Media.step(removed, files.size(), -1));
        assertEquals("a.jpg", Media.next(files, "removed.jpg").getName());
        assertEquals("a.jpg", Media.next(files, "c.jpg").getName());
    }

    @Test public void allExifOrientationsUseDisplayedDimensions() {
        for (int orientation = 1; orientation <= 8; orientation++) {
            int[] expected = orientation >= 5 ? new int[]{3000, 4000} : new int[]{4000, 3000};
            assertArrayEquals("EXIF " + orientation, expected,
                    Media.orientationDimensions(4000, 3000, orientation));
        }
        assertArrayEquals(new int[]{3000, 4000}, Media.orientationDimensions(3000, 4000, 1));
        assertArrayEquals(new int[]{4000, 3000}, Media.orientationDimensions(4000, 3000, 0));
    }

    @Test public void rotatedPhotoSamplingUsesPortraitDisplayBounds() {
        int[] oriented = Media.orientationDimensions(4000, 3000, 6);
        assertEquals(1, Media.sampleSize(oriented[0], oriented[1], 1920, 1080));
        assertArrayEquals(new int[]{3000, 1920},
                Media.coverScale(oriented[0], oriented[1], 1920, 1080));
    }

    @Test public void emptyFolderHasNoSelection() {
        assertEquals(-1, Media.step(-1, 0, 1));
        assertEquals(-1, Media.step(0, 0, -1));
        assertNull(Media.next(Collections.emptyList(), "a.jpg"));
    }
}
