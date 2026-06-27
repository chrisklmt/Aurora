package gr.hua.aurora.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenMessageIdCacheTest {
    @Test
    fun cacheAcceptsNewIdsAndDetectsDuplicates() {
        val cache = SeenMessageIdCache()

        assertTrue(cache.markSeen("message-1"))
        assertTrue(cache.contains("message-1"))
        assertFalse(cache.markSeen("message-1"))
        assertEquals(1, cache.size)
    }

    @Test
    fun cacheEvictsOldestIdAfterTenThousandEntries() {
        val cache = SeenMessageIdCache()

        repeat(SeenMessageIdCache.DEFAULT_MAX_SIZE) { index ->
            assertTrue(cache.markSeen("message-$index"))
        }
        assertFalse(cache.markSeen("message-0"))
        assertTrue(cache.markSeen("message-overflow"))

        assertEquals(SeenMessageIdCache.DEFAULT_MAX_SIZE, cache.size)
        assertFalse(cache.contains("message-0"))
        assertTrue(cache.contains("message-1"))
        assertTrue(cache.contains("message-overflow"))
    }

    @Test
    fun evictedIdCanBeAcceptedAgain() {
        val cache = SeenMessageIdCache(maxSize = 3)

        assertTrue(cache.markSeen("message-1"))
        assertTrue(cache.markSeen("message-2"))
        assertTrue(cache.markSeen("message-3"))
        assertTrue(cache.markSeen("message-4"))

        assertFalse(cache.contains("message-1"))
        assertTrue(cache.markSeen("message-1"))
        assertTrue(cache.contains("message-1"))
        assertEquals(3, cache.size)
    }
}
