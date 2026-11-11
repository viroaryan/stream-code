package org.schabi.newpipe.feedengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenizerTest {

    @Test
    fun dropsStopwordsAndKeepsTopics() {
        val words = Tokenizer.tokens("Official Video: The BEST Cricket Highlights of the Year | #IPL 2026")
        assertEquals(listOf("cricket", "highlights", "ipl"), words)
    }

    @Test
    fun keepsHindiAndHinglish() {
        val words = Tokenizer.tokens("Kya hai ye? मुंबई की बारिश vlog")
        assertTrue("मुंबई" in words)
        assertTrue("बारिश" in words)
        assertTrue("kya" !in words)
        assertTrue("vlog" !in words)
    }

    @Test
    fun deduplicatesAndHandlesEmpty() {
        assertEquals(listOf("minecraft"), Tokenizer.tokens("Minecraft minecraft MINECRAFT"))
        assertEquals(emptyList<String>(), Tokenizer.tokens(null))
        assertEquals(emptyList<String>(), Tokenizer.tokens("   "))
    }
}
