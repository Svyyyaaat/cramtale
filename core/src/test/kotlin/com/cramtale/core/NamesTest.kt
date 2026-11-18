package com.cramtale.core

import com.cramtale.core.match.Matcher
import com.cramtale.core.match.DeckIndex
import com.cramtale.core.match.Names
import com.cramtale.core.story.StoryState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NamesTest {
    @Test
    fun `a cast name written in kana is one name span, not its pieces`() {
        val state = StoryState(Files.createTempDirectory("names").toFile())
        state.writeCharacter("榊 ともえ", "## Goals\\nThe papers signed.")
        val names = state.castNameParts()
        assertTrue("さかき" in names && "さかきともえ" in names, names.toString())

        val text = "戸が開いて、さかきともえが入ってきた。"
        val m = Matcher(TestDict.fixture, DeckIndex(emptyList(), TestDict.fixture))
        val spans = Names.merge(text, m.analyze(text).spans, names)
        val name = spans.single { it.surface == "さかきともえ" }
        assertEquals("name", name.ignoredBecause)
        assertTrue(spans.none { it.surface == "かき" })
        // the rest of the sentence is untouched
        assertTrue(spans.any { it.surface == "戸" })
    }
}
