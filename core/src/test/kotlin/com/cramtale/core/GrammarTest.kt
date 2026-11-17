package com.cramtale.core

import com.cramtale.core.match.GrammarPatterns
import com.cramtale.core.text.Tokenizer
import kotlin.test.Test
import kotlin.test.assertTrue

/** Grammar patterns on real sentences (Tatoeba, CC BY 2.0 FR) and the near-misses found while measuring. */
class GrammarTest {
    private val g = GrammarPatterns.bundled()!!

    private fun ids(s: String) = g.find(Tokenizer.tokenize(s)).map { it.pattern.id }

    private fun has(s: String, id: String) = assertTrue(id in ids(s), "$id not found in $s: ${ids(s)}")
    private fun hasNot(s: String, id: String) = assertTrue(id !in ids(s), "$id wrongly found in $s: ${ids(s)}")

    @Test
    fun `patterns split across tokens and conjugated`() {
        has("家を買うことにした。", "ことにする")
        has("来月転勤することになりました。", "ことになる")
        has("天気予報によれば、明日は晴れる。", "によれば")
        has("行かなければならない。", "なければならない")
        has("私はその計画を中止せざるをえなかった。", "ざるを得ない")
        has("彼は来ないかも知れない。", "かもしれない")
        has("年をとるにつれて、忘れっぽくなる。", "につれて")
        has("人口の増加につれて、様々な問題が出てきた。", "につれて")
        has("登録にあたって料金のお支払方法をお選び頂きます。", "にあたって")
    }

    @Test
    fun `strings Kuromoji splits wrongly`() {
        has("この手紙を速達で送っていただけませんか。", "ていただく")
        has("ごめん帰らなきゃ。", "ないといけない")
        has("英語は言うまでもない。", "までもない")
        hasNot("彼女の助けがなかったら、彼は失敗していただろう。", "ていただく")
    }

    @Test
    fun `near-misses are not grammar`() {
        hasNot("彼女は彼をハンサムだといった。", "といった")
        has("この地域ではリンゴやモモといった果物の栽培が盛んだ。", "といった")
        hasNot("忘れずに雨具をもってきてください。", "をもって")
        has("勇気をもって私たちの地球を救ってください。", "をもって")
        hasNot("彼らは古いものより新しいものの方が良いと思っている。", "ものの")
        has("知っているものの、言えない。", "ものの")
        hasNot("とても現実味のあるプランとは言えなかったな。", "とはいえ")
        hasNot("彼女は宝石を盗もうとして捕まった。", "として")
        hasNot("この本は非常にためになる。", "ために")
        hasNot("彼女は火にあたって体を暖めた。", "にあたって")
        hasNot("彼女は首にスカーフをまいている。", "まい")
        hasNot("理論上は可能だけれど実際にはとても難しい。", "際に")
        hasNot("この花はとてもいい匂いがする。", "てもいい")
    }

    @Test
    fun `never-did and no-need-to are told apart`() {
        has("トムは一度だって泣いたことはない。", "ことがある")
        hasNot("トムは一度だって泣いたことはない。", "ことはない")
        has("急ぐことはない。", "ことはない")
    }

    @Test
    fun `the longest match wins`() {
        has("早く行かなくてはならない。", "なくてはならない")
        hasNot("早く行かなくてはならない。", "ては")
        has("年をとるのは悪いことではありません。", "ではありません")
    }
}
