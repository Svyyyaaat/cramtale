package com.cramtale.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.cramtale.app.R
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.cramtale.app.story.ShownPassage
import com.cramtale.app.story.ShownTurn
import com.cramtale.app.ui.DueCounts
import com.cramtale.app.ui.LogsContent
import com.cramtale.app.ui.LogsData
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.background
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

    @Test fun a8b_claude_code() = shot {
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                                         options: androidx.core.app.ActivityOptionsCompat?) {}
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
            KeyStepContent("", {}, {}, {}, claudeCode = true,
                claudeCodeSetup = { com.cramtale.app.ui.ClaudeCodeSetup(com.cramtale.app.AppState(ctx), intro = false) })
        }
    }

    private fun summary(title: String, current: Boolean, turn: Int, style: String?) = com.cramtale.app.story.StoryController.StorySummary(
        java.io.File(title), title, "村の広場", 2, turn, System.currentTimeMillis() - 3 * 86_400_000L,
        "「これ、高いの？」\nミナは瓶を棚に戻した。", style, current)

    @Test fun f1_stories() = shot {
        com.cramtale.app.ui.StoriesContent(listOf(summary("The Well Keeper", true, 14, "Light novel"),
            summary("Demon in the Frost", false, 41, "Mostly dialogue"), summary("Night Bus", false, 3, null)), {}, {}, onSwitch = {})
    }

    @Test fun f1b_stories_first() = shot {
        com.cramtale.app.ui.StoriesContent(listOf(summary("The Well Keeper", true, 0, "Light novel")), {}, {}, onSwitch = {})
    }

    @Test fun f1c_stories_dark() = shot(dark = true) {
        com.cramtale.app.ui.StoriesContent(listOf(summary("The Well Keeper", true, 14, "Light novel"),
            summary("Demon in the Frost", false, 41, "Mostly dialogue")), {}, {}, problem = "A passage is still being written. Switch once it's done.", onSwitch = {})
    }

    @Test fun f2_style() = shot {
        val presets = com.cramtale.core.story.StylePresets.ALL.map { it to 0.0 }
        com.cramtale.app.ui.StyleContent("Light novel", presets[1].first.passage, presets, null, null, null, {}, {})
    }

    @Test fun a9b_story_setup_files() = shot {
        StoryStepContent("A demon at a mountain pass", {}, null, null, {}, {},
            files = listOf("world.md" to 412, "my_character.md" to 2380), onAddFiles = {},
            fileNote = "“scan.pdf” isn't a text file. Save it as .txt or .md (from a PDF or document: copy the text into a note) and add that.")
    }

    @Test fun a9c_story_sequel_dark() = shot(dark = true) {
        StoryStepContent("", {}, null, null, {}, {}, sequelOf = "The Well Keeper")
    }

    @Test fun a9_story_setup() = shot { StoryStepContent("", {}, null, null, {}, {}, onAddFiles = {},
        styleDefault = com.cramtale.core.story.Prompts(null).config("plot.md").orEmpty()) }
    @Test fun a9d_story_setup_named_dark() = shot(dark = true) { StoryStepContent("A ghost at a seaside café", {}, null, null, {}, {}, onAddFiles = {},
        name = "セラフ", style = "Quiet and eerie; small stakes that grow.", styleDefault = com.cramtale.core.story.Prompts(null).config("plot.md").orEmpty()) }

    @Test fun b1_styles() = shot {
        StyleStepContent(listOf(
            StoryEngine.StyleOption("Terse first person", "", "ドアを叩く音がした。三回。弱い音だ。\n開けると、二階の田中が立っていた。顔色が悪い。\n「家賃のことなんですが」\n「わかってる」") to 0.016,
            StoryEngine.StyleOption("Close third person, quiet", "", "夕方の光が、台所の窓から斜めに入っていた。佐藤さんは湯のみを両手で包んだまま、向かいに座る若い女の話を聞いていた。") to 0.053,
        ), null, null, {}, {}, {})
    }

    @Test fun c1_home() = shot {
        HomeContent(HomeInfo("西田製作所の事務所", 12, 1, "村井はかばんの口を閉めて、立ち上がらずに待っていた。", 1.42,
            "Real collection · com.ichi2.anki", true, true, 7127, 10000,
            due = DueCounts(142, 6, 20), perPassage = 6.4, readToday = 23, goodToday = 19, againToday = 4,
            title = "The Well Keeper", styleName = "Light novel", storyCount = 3), {}, {})
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

    private fun storyShot(dark: Boolean, error: String? = null, versions: Int = 1, ideas: com.cramtale.app.ui.IdeasUi? = null,
                          roll: com.cramtale.app.story.RollUi? = null) = shot(dark) {
        val p = ShownPassage(3, passage, spans(passage), emptyList(), setOf("早朝".hashCode().toLong()), mapOf("早朝".hashCode().toLong() to "early morning"), emptyList(), emptyList(),
            version = versions - 1, versions = versions, roll = roll)
        val prev = ShownPassage(2, "午前八時。事務所の暖房はまだ効いていない。", spans("午前八時。事務所の暖房はまだ効いていない。"), emptyList(), emptySet(), emptyMap(), emptyList(), emptyList())
        StoryContent("西田製作所", 0.18,
            history = listOf(ShownTurn(null, emptyList(), prev)),
            current = if (error != null) null else ShownTurn("すみません、もう少し待ってくれませんか。昨日は忙しいでした。",
                listOf(Correction("忙しいでした", "忙しかったです", "conjugation", "い-adjective past: 〜かったです, not 〜いでした", "k")), p),
            busy = null, error = error, onRetry = if (error != null) ({}) else null,
            reply = if (error != null) "すみません、もう少し待ってくれませんか。" else "", proposals = emptyList(), tapVersion = 0,
            isTapped = { it.surface == "布" }, statusOf = { if (it.surface == "返済") Status.UNKNOWN else Status.DECK },
            onBack = {}, onTap = { _, _ -> }, onGrammar = {}, onReroll = {}, onEdit = {}, onSend = {}, onDecide = { _, _ -> }, onListen = {},
            ideas = ideas ?: com.cramtale.app.ui.IdeasUi(emptyList(), false, null, {}, {}))
    }

    private val threeIdeas = listOf(
        com.cramtale.core.story.StoryEngine.Idea("「返済は来週まで待ってください」と頼む", "Ask her to wait until next week for the repayment"),
        com.cramtale.core.story.StoryEngine.Idea("ファイルを見て、「これは誰が書いたんですか」", "Look at the file: \"Who wrote this?\""),
        com.cramtale.core.story.StoryEngine.Idea("黙って部屋を出る", "Leave the room without a word"),
    )

    @Test fun d11_roll_cost() = storyShot(false, roll = com.cramtale.app.story.RollUi(listOf(4, 3), 0, com.cramtale.core.story.Dice.Outcome.COST, "talk Murai into waiting another week"))
    @Test fun d11b_roll_failed_dark() = storyShot(true, roll = com.cramtale.app.story.RollUi(listOf(2, 3), -1, com.cramtale.core.story.Dice.Outcome.FAILURE, "talk Murai into waiting another week"))
    @Test fun d11c_roll_success() = storyShot(false, roll = com.cramtale.app.story.RollUi(listOf(6, 5), 0, com.cramtale.core.story.Dice.Outcome.SUCCESS, "talk Murai into waiting another week"))

    @Test fun d10_ideas() = storyShot(false, ideas = com.cramtale.app.ui.IdeasUi(threeIdeas, false, null, {}, {}))
    @Test fun d10b_ideas_dark() = storyShot(true, ideas = com.cramtale.app.ui.IdeasUi(threeIdeas, false, null, {}, {}))
    @Test fun d10c_ideas_loading() = storyShot(false, ideas = com.cramtale.app.ui.IdeasUi(emptyList(), true, null, {}, {}))
    @Test fun d10d_ideas_failed() = storyShot(false, ideas = com.cramtale.app.ui.IdeasUi(emptyList(), false, "No internet: couldn't reach Claude.", {}, {}))

    @Test fun c2_home_dark() = shot(dark = true) {
        HomeContent(HomeInfo("西田製作所の事務所", 12, 1, "村井はかばんの口を閉めて、立ち上がらずに待っていた。", 1.42,
            "Real collection · com.ichi2.anki", true, true, 7127, 10000,
            due = DueCounts(142, 6, 20), perPassage = 6.4, readToday = 23, goodToday = 19, againToday = 4,
            title = "The Well Keeper", styleName = "Light novel", storyCount = 3), {}, {})
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
            intro = "狐火堂という小さな菓子屋の二代目。父の借金が残っていて、春祭りまでに返さなければならない。", onInfo = {}, teach = true)
    }

    @Test fun d6_story_info() = shot {
        StoryInfoBody(com.cramtale.app.story.StoryController.StoryInfo(
            "狐火堂という小さな菓子屋の二代目。父の借金が残っていて、春祭りまでに返さなければならない。",
            listOf("榊", "コガネ", "ミナ"),
            listOf(1 to "Sakaki from the guild brought the festival papers and a threat: sign, or lose the shop's permit. Kogane, the cat, watched."),
            listOf("供える" to true, "組合" to false, "許可" to true, "書類" to true, "保証人" to true, "手間" to false)))
    }

    private fun due(id: Long, word: String, meaning: String, isNew: Boolean = false) = com.cramtale.core.review.DueCard(
        com.cramtale.core.review.CardRef(id, 0),
        com.cramtale.core.match.DeckWord(id, "Core", "Japanese Core 5k", word, null, com.cramtale.core.match.CardState.STUDIED,
            listOf(com.cramtale.core.match.RawCard(0, "Recognition", 2, 2)), meaning = meaning),
        if (isNew) com.cramtale.core.review.CardInfo(0, 0, 1) else com.cramtale.core.review.CardInfo(2, 2, 10, 4, 12))

    @Test fun d7_story_streaming() = shot {
        StoryContent("狐火堂", 0.21, history = emptyList(), current = null, busy = "Writing…", error = null, proposals = emptyList(),
            tapVersion = 0, isTapped = { false }, statusOf = { Status.DECK }, onBack = {}, onTap = { _, _ -> }, onGrammar = {},
            onReroll = {}, onEdit = {}, onSend = {}, onDecide = { _, _ -> },
            draft = "writing" to "榊さんは書類を受け取らなかった。「サインは、お祭りのあとでもいいですよ」\nコガネが尻尾を",
            sending = "マナを隠すようにする。音もしない。")
    }

    @Test fun d8_story_waiting_flashcard() = shot {
        StoryContent("狐火堂", 0.21, history = emptyList(), current = null, busy = "Checking your Japanese…", error = null, proposals = emptyList(),
            tapVersion = 0, isTapped = { false }, statusOf = { Status.DECK }, onBack = {}, onTap = { _, _ -> }, onGrammar = {},
            onReroll = {}, onEdit = {}, onSend = {}, onDecide = { _, _ -> },
            study = listOf(due(1, "見逃す", "to miss; to overlook"), due(2, "手間", "time; labour")))
    }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    /** The launcher icon at large size: circle and rounded-square masks, and the themed (monochrome) version. */
    /** Like a launcher: the middle 72dp of the 108dp layers is what shows, under the launcher's mask. */
    @Composable
    private fun Launcher(size: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape, themed: Boolean) {
        androidx.compose.foundation.layout.Box(Modifier.size(size).clip(shape)
            .background(if (themed) androidx.compose.ui.graphics.Color(0xFFDCE1F7) else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            val layer = Modifier.requiredSize(size * 1.5f)
            if (themed) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_monochrome), null, layer,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.ui.graphics.Color(0xFF3A4255)))
            else {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_background), null, layer)
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), null, layer)
            }
        }
    }

    @Test fun z1_icon() = shot {
        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                Launcher(150.dp, androidx.compose.foundation.shape.CircleShape, false)
                Launcher(150.dp, androidx.compose.foundation.shape.CircleShape, true)
            }
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Launcher(150.dp, androidx.compose.foundation.shape.RoundedCornerShape(36.dp), false)
                Launcher(56.dp, androidx.compose.foundation.shape.CircleShape, false)
                Launcher(56.dp, androidx.compose.foundation.shape.CircleShape, true)
            }
            // in the app, both colour modes
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                com.cramtale.app.ui.AppMark(64.dp)
                androidx.compose.runtime.CompositionLocalProvider(com.cramtale.app.ui.LocalDynamicColor provides true) { com.cramtale.app.ui.AppMark(64.dp) }
                com.cramtale.app.ui.AppMark(36.dp)
            }
        }
    }

    @Test fun e2_logs() = shot {

        val rows = listOf(
            com.cramtale.core.review.DryRunReport.Row("書類", 3, com.cramtale.core.review.DryRunReport.Real.PASSED, "read without a tap"),
            com.cramtale.core.review.DryRunReport.Row("組合", 1, com.cramtale.core.review.DryRunReport.Real.AGAIN, "tapped"),
            com.cramtale.core.review.DryRunReport.Row("保証人", 3, com.cramtale.core.review.DryRunReport.Real.AGAIN, "read without a tap"),
            com.cramtale.core.review.DryRunReport.Row("手間", 1, com.cramtale.core.review.DryRunReport.Real.PASSED, "tapped (not due: Again right away)"),
            com.cramtale.core.review.DryRunReport.Row("許可", 3, com.cramtale.core.review.DryRunReport.Real.NOT_YET, "read without a tap"),
        )
        LogsContent(LogsData(com.cramtale.core.review.DryRunReport.Summary(rows), null, 3.41, 0.62,
            mapOf("writer" to 0.31, "rewrite" to 0.18, "judge" to 0.04, "mistakes" to 0.03, "scribe" to 0.02),
            listOf(com.cramtale.core.review.KnownNewCards.Entry(1, "見逃す", 0)), emptyList()), {})
    }

    @Test fun d3c_word_sheet_dark() = shot(dark = true) {
        WordSheetBody(WordInfo("書類", "しょるい", "the festival papers Sakaki brought", listOf("documents; official papers"), true,
            card = "documents, papers", newCard = true), {}, {}, onSay = {})
    }

    @Test fun d8b_flashcard_dark() = shot(dark = true) {
        StoryContent("狐火堂", 0.21, history = emptyList(), current = null, busy = "Planning this session…", error = null, proposals = emptyList(),
            tapVersion = 0, isTapped = { false }, statusOf = { Status.DECK }, onBack = {}, onTap = { _, _ -> }, onGrammar = {},
            onReroll = {}, onEdit = {}, onSend = {}, onDecide = { _, _ -> },
            study = listOf(due(3, "許可", "permission", isNew = true)))
    }

    @Test fun d9_grammar_sheet() = shot {
        val text = "榊さんは書類を置いた。「もし断るなら、こちらをかたにいただくことにします」"
        val i = text.indexOf("ことにします")
        com.cramtale.app.ui.GrammarSheetBody("ことにする", "decide to (do)", text, i, i + "ことにします".length) {}
    }

    @Test fun e1_settings() = shot {
        SettingsContent(SettingsValues(1.15f, com.cramtale.core.story.PassageLength.LONG, false, false, "sk-ant-…x9Qa", "Spare and close: short sentences, objects and what people do.",
            defaultStyle = "Cosy mysteries, low stakes, a town where everyone knows everyone.", ownDefaultStyle = true), {}, {}, {}, {}, {}, {})
    }

    @Test fun d3_word_sheet() = shot {
        WordSheetBody(WordInfo("返済", "へんさい", "repaying the loan", listOf("repayment; reimbursement; refund"), true, card = "repayment"), {}, {}, onSay = {})
    }

    @Test fun d3b_new_card_sheet() = shot {
        WordSheetBody(WordInfo("書類", "しょるい", "the festival papers Sakaki brought", listOf("documents; official papers"), true,
            card = "documents, papers", newCard = true), {}, {}, onSay = {})
    }

    @Test fun d4_name_sheet() = shot {
        WordSheetBody(WordInfo("榊", nameReading("榊"), "Sakaki, the visitor who brought the papers", emptyList(), false, isName = true), {}, {})
    }
}
