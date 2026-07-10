package com.mink.guardian

import com.mink.guardian.llm.MiniCpmChatFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniCpmChatFormatTest {

    @Test
    fun buildPrompt_wrapsTurnsAndOpensAssistant() {
        val prompt = MiniCpmChatFormat.buildPrompt(
            systemPrompt = "You are Mink.",
            userMessage = "What leaks my identity?",
            enableThinking = true,
        )
        val expected =
            "<|im_start|>system\nYou are Mink.<|im_end|>\n" +
                "<|im_start|>user\nWhat leaks my identity?<|im_end|>\n" +
                "<|im_start|>assistant\n"
        assertEquals(expected, prompt)
    }

    @Test
    fun buildPrompt_omitsBlankSystem() {
        val prompt = MiniCpmChatFormat.buildPrompt("", "hello", enableThinking = true)
        assertFalse(prompt.contains("system"))
        assertTrue(prompt.startsWith("<|im_start|>user\n"))
    }

    @Test
    fun buildPrompt_noThinkAppendsSuffixToUserTurn() {
        val prompt = MiniCpmChatFormat.buildPrompt("sys", "hi", enableThinking = false)
        assertTrue(prompt.contains("hi ${MiniCpmChatFormat.NO_THINK}<|im_end|>"))
    }

    @Test
    fun buildPrompt_thinkingDoesNotAppendSuffix() {
        val prompt = MiniCpmChatFormat.buildPrompt("sys", "hi", enableThinking = true)
        assertFalse(prompt.contains(MiniCpmChatFormat.NO_THINK))
    }

    @Test
    fun buildPrompt_includesHistoryInOrder() {
        val history = listOf(
            MiniCpmChatFormat.Turn(MiniCpmChatFormat.ROLE_USER, "first"),
            MiniCpmChatFormat.Turn(MiniCpmChatFormat.ROLE_ASSISTANT, "answer"),
        )
        val prompt = MiniCpmChatFormat.buildPrompt("sys", history, "second", enableThinking = true)
        val firstIdx = prompt.indexOf("first")
        val answerIdx = prompt.indexOf("answer")
        val secondIdx = prompt.indexOf("second")
        assertTrue(firstIdx in 0 until answerIdx)
        assertTrue(answerIdx in 0 until secondIdx)
    }

    @Test
    fun parseReply_noThinkBlock() {
        val parsed = MiniCpmChatFormat.parseReply("Your location is exposed.")
        assertNull(parsed.thinking)
        assertEquals("Your location is exposed.", parsed.content)
    }

    @Test
    fun parseReply_closedThinkBlockIsSeparated() {
        val raw = "<think>\nThe user asked about location.\n</think>\n\nLocation is exposed."
        val parsed = MiniCpmChatFormat.parseReply(raw)
        assertEquals("The user asked about location.", parsed.thinking)
        assertEquals("Location is exposed.", parsed.content)
    }

    @Test
    fun parseReply_unterminatedThinkBlockHasEmptyContent() {
        val parsed = MiniCpmChatFormat.parseReply("<think>still reasoning")
        assertEquals("still reasoning", parsed.thinking)
        assertEquals("", parsed.content)
    }

    @Test
    fun parseReply_stripsTrailingImEnd() {
        val parsed = MiniCpmChatFormat.parseReply("Done.<|im_end|>")
        assertEquals("Done.", parsed.content)
    }

    @Test
    fun parseReply_emptyThinkBlockYieldsNullThinking() {
        val parsed = MiniCpmChatFormat.parseReply("<think></think>Answer.")
        assertNull(parsed.thinking)
        assertEquals("Answer.", parsed.content)
    }
}
