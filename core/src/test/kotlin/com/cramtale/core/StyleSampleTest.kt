package com.cramtale.core

import com.cramtale.core.story.StylePresets
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The built-in samples no longer show a rent-and-debt scene; stories that had the old one, unedited, get the new one. */
class StyleSampleTest {
    private val oldLightNovel = "田中は三回ドアをたたいた。三回目は、ほとんどお願いだった。\n「……開いてるよ」\n大家の声は、朝から機嫌が悪い人の声だった。いや、たぶん一日中こうなのだろう。\n「あの、家賃のことなんですけど」\n「知ってる。顔に書いてある」\n書いてあるらしい。田中は思わず自分の顔にさわった。\n「来週まで待ってもらえませんか。給料が出たら、すぐ払います」\n「来週ねえ」\n大家は長いため息をついた。ため息にも長さの順位があるなら、これは一位だ。\n「こっちもな、銀行に払う金があるんだよ。三日。それ以上は無理だ」\n三日。短い。でも、ゼロよりはずっといい。"

    @Test
    fun `an untouched old sample becomes the same style's new one, an edited one stays`() {
        val dir = Files.createTempDirectory("style").toFile()
        File(dir, "config").mkdirs()
        File(dir, "config/style_sample.md").writeText(oldLightNovel + "\n")
        StylePresets.upgrade(dir)
        assertEquals(StylePresets.ALL.single { it.name == "Light novel" }.passage.trim(), File(dir, "config/style_sample.md").readText().trim())

        File(dir, "config/style_sample.md").writeText(oldLightNovel + "\n（自分で書き足した）\n")
        StylePresets.upgrade(dir)
        assertTrue("書き足した" in File(dir, "config/style_sample.md").readText(), "edited: left alone")
    }

    @Test
    fun `no built-in sample is about rent or money`() {
        for (o in StylePresets.ALL) assertFalse(listOf("家賃", "大家", "給料", "銀行").any { it in o.passage }, o.name)
    }
}
