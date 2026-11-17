package com.cramtale.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.cramtale.app.story.ShownPassage
import com.cramtale.app.story.ShownTurn
import com.cramtale.app.ui.DueCounts
import com.cramtale.app.ui.StoryInfoBody
import com.cramtale.app.ui.SettingsContent
import com.cramtale.app.ui.SettingsValues
import com.cramtale.app.ui.nameReading
import com.cramtale.app.ui.AnkiStepContent
import com.cramtale.app.ui.CramtaleTheme
import com.cramtale.app.ui.DeckRowUi
import com.cramtale.app.ui.DeckStepContent
import com.cramtale.app.ui.DictionaryStepContent
import com.cramtale.app.ui.HomeContent
import com.cramtale.app.ui.HomeInfo
import com.cramtale.app.ui.InstallUi
import com.cramtale.app.ui.KeyStepContent
import com.cramtale.app.ui.StoryContent
import com.cramtale.app.ui.StoryStepContent
import com.cramtale.app.ui.StyleStepContent
import com.cramtale.app.ui.VocabIntroContent
import com.cramtale.app.ui.VocabQuestion
import com.cramtale.app.ui.VocabResults
import com.cramtale.app.ui.WelcomeContent
import com.cramtale.app.ui.WordInfo
import com.cramtale.app.ui.WordSheetBody
import com.cramtale.core.match.CardState
import com.cramtale.core.match.DeckWord
import com.cramtale.core.match.RawCard
import com.cramtale.core.match.Span
import com.cramtale.core.match.Status
import com.cramtale.core.match.VocabCheck
import com.cramtale.core.story.Correction
import com.cramtale.core.story.StoryEngine
import org.junit.Rule
import org.junit.Test

/** Screens rendered from sample data: ./gradlew :app:recordPaparazziDebug → app/src/test/snapshots/images */
class Shots {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.1)

    private fun shot(dark: Boolean = false, content: @Composable () -> Unit) {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_6.copy(nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT))
        paparazzi.snapshot {
            CramtaleTheme(dark = dark) { androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
        }
    }

    @Test fun a1_welcome() = shot { WelcomeContent {} }

    @Test fun a2_anki() = shot {
        AnkiStepContent(listOf(
            InstallUi("com.ichi2.anki", "AnkiDroid (your real collection)", "Version 2.21.2", true, true),
            InstallUi("com.ichi2.anki.a", "AnkiDroid parallel build (a)", "Version 2.21.2", false, false),
        ), "com.ichi2.anki", {}, {}, {}, {})
    }

    @Test fun a3_dictionary() = shot { DictionaryStepContent("Building dictionary: 120000 entries", true, false, {}, {}, {}, {}) }

    @Test fun a4_deck() = shot {
        DeckStepContent(listOf(
            DeckRowUi("日本語::Nayr's Core 5k", 4984, 4560, 424), DeckRowUi("日本語::Japanese V2K", 1982, 1982, 0),
            DeckRowUi("日本語::claude-generated", 145, 145, 0), DeckRowUi("日本語::僕の日本語", 16, 16, 0),
        ), null, false, null, {}, {}, {})
    }

    @Test fun a5_vocab_intro() = shot { VocabIntroContent(8000, {}, {}, {}) }

    @Test fun a6_vocab_question() = shot {
        VocabQuestion(VocabCheck.Item(1, "見比べる", 6400, 6000, "to compare with the eye"), 23, 80,
            VocabCheck.Item(2, "娯楽", 6900, 6000, "amusement; entertainment; recreation", "ごらく") to true, {}, {})
    }

    @Test fun a7_vocab_results() = shot {
        VocabResults(listOf(
            VocabCheck.BandResult(2000, 4000, 10, 10), VocabCheck.BandResult(4000, 6000, 10, 10), VocabCheck.BandResult(6000, 8000, 10, 9),
            VocabCheck.BandResult(8000, 10000, 10, 9), VocabCheck.BandResult(10000, 12000, 10, 7), VocabCheck.BandResult(12000, 15000, 10, 6),
            VocabCheck.BandResult(15000, 20000, 10, 4), VocabCheck.BandResult(20000, 30000, 10, 2),
        ), 10000, false, {}, {}, {})
    }

    @Test fun a8_key() = shot { KeyStepContent("sk-ant-api03-xxxx", {}, {}, {}) }

    @Test fun a9_story_setup() = shot { StoryStepContent("", {}, null, null, {}, {}) }

    @Test fun b1_styles() = shot {
        StyleStepContent(listOf(
            StoryEngine.StyleOption("Terse first person", "", "ドアを叩く音がした。三回。弱い音だ。\n開けると、二階の田中が立っていた。顔色が悪い。\n「家賃のことなんですが」\n「わかってる」") to 0.016,
            StoryEngine.StyleOption("Close third person, quiet", "", "夕方の光が、台所の窓から斜めに入っていた。佐藤さんは湯のみを両手で包んだまま、向かいに座る若い女の話を聞いていた。") to 0.053,
        ), null, null, {}, {}, {})
    }

    @Test fun c1_home() = shot {
        HomeContent(HomeInfo("西田製作所の事務所", 12, 1, "村井はかばんの口を閉めて、立ち上がらずに待っていた。", 1.42,
            "Real collection · com.ichi2.anki", true, true, 7127, 10000,
            due = DueCounts(142, 6, 20), perPassage = 6.4, readToday = 23, goodToday = 19, againToday = 4), {}, {})
    }

    private val passage = "信用金庫の村井は、早朝にもかかわらず化粧も髪も崩れていなかった。机に布のかばんを置き、薄いファイルを一冊出す。\n「先月お約束いただいた返済の計画書、今日もらえますか」"
    private fun spans(text: String): List<Span> {
        val words = listOf("信用金庫", "村井", "早朝", "化粧", "髪", "崩れて", "机", "布", "かばん", "置き", "薄い", "ファイル", "出す", "先月", "約束", "返済", "計画書", "今日", "もらえます")
        val deck = DeckWord(1, "d", "Japanese Core 5k", "布", null, CardState.STUDIED, listOf(RawCard(0, "Recognition", 2, 2)))
        val out = ArrayList<Span>()
        var from = 0
        for (w in words) {
            val i = text.indexOf(w, from)
            if (i < 0) continue
            out += Span(i, i + w.length, w, w, emptySet(), 1000, if (w == "布" || w == "早朝") listOf(deck.copy(noteId = w.hashCode().toLong(), word = w)) else emptyList(), null, 1)
            from = i + w.length
        }
        return out
    }

    private fun storyShot(dark: Boolean, error: String? = null, versions: Int = 1) = shot(dark) {
        val p = ShownPassage(3, passage, spans(passage), emptyList(), setOf("早朝".hashCode().toLong()), mapOf("早朝".hashCode().toLong() to "early morning"), emptyList(), emptyList(),
            version = versions - 1, versions = versions)
        val prev = ShownPassage(2, "午前八時。事務所の暖房はまだ効いていない。", spans("午前八時。事務所の暖房はまだ効いていない。"), emptyList(), emptySet(), emptyMap(), emptyList(), emptyList())
        StoryContent("西田製作所", 0.18,
            history = listOf(ShownTurn(null, emptyList(), prev)),
            current = if (error != null) null else ShownTurn("すみません、もう少し待ってくれませんか。昨日は忙しいでした。",
                listOf(Correction("忙しいでした", "忙しかったです", "conjugation", "い-adjective past: 〜かったです, not 〜いでした", "k")), p),
            busy = null, error = error, onRetry = if (error != null) ({}) else null,
            reply = if (error != null) "すみません、もう少し待ってくれませんか。" else "", proposals = emptyList(), tapVersion = 0,
            isTapped = { it.surface == "布" }, statusOf = { if (it.surface == "返済") Status.UNKNOWN else Status.DECK },
            onBack = {}, onTap = { _, _ -> }, onGrammar = {}, onReroll = {}, onEdit = {}, onSend = {}, onDecide = { _, _ -> }, onListen = {})
    }

    @Test fun c2_home_dark() = shot(dark = true) {
        HomeContent(HomeInfo("西田製作所の事務所", 12, 1, "村井はかばんの口を閉めて、立ち上がらずに待っていた。", 1.42,
            "Real collection · com.ichi2.anki", true, true, 7127, 10000,
            due = DueCounts(142, 6, 20), perPassage = 6.4, readToday = 23, goodToday = 19, againToday = 4), {}, {})
    }

    @Test fun a6b_vocab_question_dark() = shot(dark = true) {
        VocabQuestion(VocabCheck.Item(1, "見比べる", 6400, 6000, "to compare with the eye"), 23, 80,
            VocabCheck.Item(2, "娯楽", 6900, 6000, "amusement; entertainment; recreation", "ごらく") to false, {}, {})
    }

    @Test fun d1_story() = storyShot(false)
    @Test fun d2_story_dark() = storyShot(true)

    @Test fun d1b_story_versions() = storyShot(false, versions = 3)
    @Test fun d1c_story_error() = storyShot(false, error = com.cramtale.core.llm.ErrorText.explain(
        com.cramtale.core.llm.LlmException("network: Request failed", cause = java.net.UnknownHostException("Unable to resolve host \"api.anthropic.com\""))))

    @Test fun d5_story_start() = shot {
        val text = "朝六時。店の戸を半分開けたところで、榊さんが入ってきた。"
        StoryContent("狐火堂", 0.04, history = emptyList(),
            current = ShownTurn(null, emptyList(), ShownPassage(0, text, spans(text), emptyList(), emptySet(), emptyMap(), emptyList(), emptyList())),
            busy = null, error = null, proposals = emptyList(), tapVersion = 0, isTapped = { false }, statusOf = { Status.DECK },
            onBack = {}, onTap = { _, _ -> }, onGrammar = {}, onReroll = {}, onEdit = {}, onSend = {}, onDecide = { _, _ -> },
            intro = "狐火堂という小さな菓子屋の二代目。父の借金が残っていて、春祭りまでに返さなければならない。", onInfo = {})
    }

    @Test fun d6_story_info() = shot {
        StoryInfoBody(com.cramtale.app.story.StoryController.StoryInfo(
            "狐火堂という小さな菓子屋の二代目。父の借金が残っていて、春祭りまでに返さなければならない。",
            listOf("榊", "コガネ", "ミナ"),
            listOf(1 to "Sakaki from the guild brought the festival papers and a threat: sign, or lose the shop's permit. Kogane, the cat, watched.")))
    }

    @Test fun e1_settings() = shot {
        SettingsContent(SettingsValues(1.15f, com.cramtale.core.story.PassageLength.LONG, false, false, "sk-ant-…x9Qa", "Spare and close: short sentences, objects and what people do."), {}, {}, {}, {}, {}, {})
    }

    @Test fun d3_word_sheet() = shot {
        WordSheetBody(WordInfo("返済", "へんさい", "repaying the loan", listOf("repayment; reimbursement; refund"), true, card = "repayment"), {}, {}, onSay = {})
    }

    @Test fun d4_name_sheet() = shot {
        WordSheetBody(WordInfo("榊", nameReading("榊"), "Sakaki, the visitor who brought the papers", emptyList(), false, isName = true), {}, {})
    }
}
