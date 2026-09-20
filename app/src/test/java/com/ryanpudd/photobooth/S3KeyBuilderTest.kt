package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Test

class S3KeyBuilderTest {

    @Test
    fun noPrefix_usesBareFilename() {
        assertEquals("IMG_20260920_193045_001.jpg", S3KeyBuilder.buildKey("", "IMG_20260920_193045_001.jpg"))
    }

    @Test
    fun prefixIsJoinedWithASingleSlash() {
        assertEquals("wedding/IMG_1.jpg", S3KeyBuilder.buildKey("wedding", "IMG_1.jpg"))
        assertEquals("wedding/IMG_1.jpg", S3KeyBuilder.buildKey("wedding/", "IMG_1.jpg"))
        assertEquals("wedding/IMG_1.jpg", S3KeyBuilder.buildKey("/wedding/", "IMG_1.jpg"))
        assertEquals("wedding/IMG_1.jpg", S3KeyBuilder.buildKey("  wedding  ", "IMG_1.jpg"))
    }

    @Test
    fun demoFilesGetTheirOwnFolderSoTheyAreEasyToDelete() {
        assertEquals("wedding/demo/DEMO_IMG_1.jpg", S3KeyBuilder.buildKey("wedding", "DEMO_IMG_1.jpg"))
    }

    @Test
    fun demoFilesWithNoPrefixStillGetTheDemoFolder() {
        assertEquals("demo/DEMO_IMG_1.jpg", S3KeyBuilder.buildKey("", "DEMO_IMG_1.jpg"))
    }

    @Test
    fun retriedDemoFilesAreStillRecognised() {
        assertEquals("demo/DEMO_IMG_1__u2.jpg", S3KeyBuilder.buildKey("", "DEMO_IMG_1__u2.jpg"))
    }
}
