package com.cramtale.core.story

import com.cramtale.core.writeAtomic
import java.io.File

/**
 * Built-in writing styles, ready without a model call. All of them lean on dialogue, the part a learner who
 * watches anime already reads well; they differ in what the narration between the lines is like. Each
 * sample is the same scene (the one the style picker uses), so they can be compared side by side. The scene is
 * deliberately no particular genre or era: the writer imitates the sample every turn, and a sample about rent and
 * debts pulled every story toward money trouble in the present day.
 */
object StylePresets {
    val ALL = listOf(
        StoryEngine.StyleOption(
            "Mostly dialogue",
            "Mostly spoken lines in 「」 with quick back-and-forth; narration only as short plain sentences between the lines saying who does what; never more than two sentences of description in a row.",
            """
            「すみません、部屋はありますか」
            「ないね。今夜は満室だ」
            宿の主人は帳面から顔を上げなかった。
            「この雪です。廊下でもいいんです」
            「廊下も客でいっぱいだよ」
            「でも、奥の部屋に明かりがついていますよ」
            主人の手が止まった。
            「あそこは……使ってない」
            「じゃあ、どうして明かりが？」
            「見間違いだ。下の村まで行けば、まだ間に合う」
            外で、風が戸を強くたたいた。
            「下の村まで、歩いて二時間ですよ」
            「なら、早く出たほうがいい」
            """.trimIndent(),
        ),
        StoryEngine.StyleOption(
            "Light novel",
            "Light-novel style: close third person with a chatty, wry narrator who comments in casual spoken Japanese; short paragraphs, lots of dialogue, everyday words, small jokes in the narration; no poetic imagery or mood-painting.",
            """
            雪の山道を三時間。旅人がやっと見つけた宿は、思ったより小さかった。
            「すみません、部屋はありますか」
            「ない」
            宿の主人の答えは、外の雪より冷たかった。たった二文字。
            「廊下でもいいんです。この雪ですし」
            「廊下も満員だ」
            廊下が満員の宿なんて、旅人は初めて見た。
            ところが、奥の部屋の戸のすきまから、明かりがもれている。
            「あの、あそこは？」
            「使ってない」
            使ってない部屋に、明かりはつかない。たぶん。
            主人は帳面を閉じて、旅人をじっと見た。これ以上聞くな、という顔だ。わかりやすい人である。
            """.trimIndent(),
        ),
        StoryEngine.StyleOption(
            "Plain storybook",
            "Plain storybook prose, like a children's or young-adult novel: simple past-tense narration of what people do and what is there, concrete everyday words, short sentences, frequent dialogue; no metaphors, no mood-painting, no comments on what things mean.",
            """
            その夜、山はひどい雪だった。旅人は小さな宿の戸をたたいた。
            中は暖かく、炉の火が赤く燃えていた。主人は帳面に何か書いていた。
            「部屋はありますか」
            「今夜は満室です」
            主人は顔を上げずに答えた。
            旅人は廊下の奥を見た。いちばん奥の部屋の戸の下から、細い明かりがもれていた。
            「あの部屋は？」
            「あそこは、誰も使っていません」
            主人はそう言って、ろうそくを一本消した。
            外では、風が戸をがたがたとゆらしていた。
            """.trimIndent(),
        ),
    )

    /** What each built-in style is like, in a few words for the picker (the full line is the writer's instruction). */
    val BLURBS = mapOf(
        "Mostly dialogue" to "Talk, talk, talk; one short line of action between.",
        "Light novel" to "Lots of dialogue and a chatty narrator with jokes.",
        "Plain storybook" to "Simple, concrete narration, like a young-adult book.",
    )

    /** Java hash codes of the samples before the inn scene (a landlord and late rent): untouched copies are upgraded. */
    private val OLD_SAMPLES = mapOf(-898301154 to "Mostly dialogue", 1440924998 to "Light novel", -362532161 to "Plain storybook")

    /**
     * A story whose style sample is one of the old built-in ones, unedited, gets the same style's current sample.
     * An edited or generated sample is left alone.
     */
    fun upgrade(dir: File) {
        val f = File(dir, "config/style_sample.md").takeIf { it.exists() } ?: return
        val name = OLD_SAMPLES[f.readText().trim().hashCode()] ?: return
        ALL.firstOrNull { it.name == name }?.let { f.writeAtomic(it.passage.trim() + "\n") }
    }

    /** Saves [o] as the story's style: the sample the writer imitates, its one-line description, and its name. */
    fun write(dir: File, o: StoryEngine.StyleOption) {
        File(dir, "config").mkdirs()
        File(dir, "config/style_sample.md").writeAtomic(o.passage.trim() + "\n")
        File(dir, "config/style_line.txt").writeAtomic(o.line.trim() + "\n")
        File(dir, "config/style_name.txt").writeAtomic(o.name.trim() + "\n")
    }

    fun name(dir: File): String? = File(dir, "config/style_name.txt").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
}
