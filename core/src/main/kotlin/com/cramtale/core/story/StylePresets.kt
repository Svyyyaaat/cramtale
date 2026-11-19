package com.cramtale.core.story

import com.cramtale.core.writeAtomic
import java.io.File

/**
 * Built-in writing styles, ready without a model call. All of them lean on dialogue, the part a learner who
 * watches anime already reads well; they differ in what the narration between the lines is like. Each
 * sample is the same scene (the one the style picker uses), so they can be compared side by side.
 */
object StylePresets {
    val ALL = listOf(
        StoryEngine.StyleOption(
            "Mostly dialogue",
            "Mostly spoken lines in 「」 with quick back-and-forth; narration only as short plain sentences between the lines saying who does what; never more than two sentences of description in a row.",
            """
            「家賃、もう少し待ってもらえませんか」
            「またか。先月も同じことを言ったな」
            大家は帳面を閉じた。
            「来週には給料が入るんです。本当です」
            「本当かどうかは、来週わかる」
            「じゃあ……」
            「三日だ。それ以上は待てない」
            「三日……」
            「こっちにも払う相手がいるんだよ。銀行は待ってくれない」
            大家はそう言って、窓の外を見た。
            「三日後の夕方、ここに来い。来なかったら、鍵を替える」
            """.trimIndent(),
        ),
        StoryEngine.StyleOption(
            "Light novel",
            "Light-novel style: close third person with a chatty, wry narrator who comments in casual spoken Japanese; short paragraphs, lots of dialogue, everyday words, small jokes in the narration; no poetic imagery or mood-painting.",
            """
            田中は三回ドアをたたいた。三回目は、ほとんどお願いだった。
            「……開いてるよ」
            大家の声は、朝から機嫌が悪い人の声だった。いや、たぶん一日中こうなのだろう。
            「あの、家賃のことなんですけど」
            「知ってる。顔に書いてある」
            書いてあるらしい。田中は思わず自分の顔にさわった。
            「来週まで待ってもらえませんか。給料が出たら、すぐ払います」
            「来週ねえ」
            大家は長いため息をついた。ため息にも長さの順位があるなら、これは一位だ。
            「こっちもな、銀行に払う金があるんだよ。三日。それ以上は無理だ」
            三日。短い。でも、ゼロよりはずっといい。
            """.trimIndent(),
        ),
        StoryEngine.StyleOption(
            "Plain storybook",
            "Plain storybook prose, like a children's or young-adult novel: simple past-tense narration of what people do and what is there, concrete everyday words, short sentences, frequent dialogue; no metaphors, no mood-painting, no comments on what things mean.",
            """
            夕方、田中は大家の家の前に立っていた。今月の家賃は、まだ払えていなかった。
            ドアを開けたのは大家だった。台所から、魚を焼くにおいがした。
            「家賃のことか」
            「はい。来週まで待ってもらえませんか」
            大家はしばらく黙っていた。それから、げた箱の上の手紙を一枚取った。銀行からの手紙だった。
            「わたしにも、払わなければならないお金があるんだ」
            「わかっています」
            「三日だけ待つ。三日後の夕方に、また来なさい」
            田中は頭を下げた。ドアが閉まると、外はもう暗くなり始めていた。
            """.trimIndent(),
        ),
    )

    /** What each built-in style is like, in a few words for the picker (the full line is the writer's instruction). */
    val BLURBS = mapOf(
        "Mostly dialogue" to "Talk, talk, talk; one short line of action between.",
        "Light novel" to "Lots of dialogue and a chatty narrator with jokes.",
        "Plain storybook" to "Simple, concrete narration, like a young-adult book.",
    )

    /** Saves [o] as the story's style: the sample the writer imitates, its one-line description, and its name. */
    fun write(dir: File, o: StoryEngine.StyleOption) {
        File(dir, "config").mkdirs()
        File(dir, "config/style_sample.md").writeAtomic(o.passage.trim() + "\n")
        File(dir, "config/style_line.txt").writeAtomic(o.line.trim() + "\n")
        File(dir, "config/style_name.txt").writeAtomic(o.name.trim() + "\n")
    }

    fun name(dir: File): String? = File(dir, "config/style_name.txt").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
}
