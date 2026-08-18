package org.stypox.dicio.eval

import android.content.Context
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.dicio.numbers.ParserFormatter
import org.dicio.skill.context.SpeechOutputDevice
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.MockSkill
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.current_time.CurrentTimeInfo
import org.stypox.dicio.skills.current_time.CurrentTimeSkill
import org.stypox.dicio.skills.joke.JokeInfo
import org.stypox.dicio.skills.joke.JokeSkill
import org.stypox.dicio.mocked
import java.util.Locale

private const val JOKE_INPUT = "hast du einen witz"
private const val TIME_INPUT = "wie spät ist es"

/**
 * Reproduces the shared-`standardMatchHelper` race fixed by `selectMatchingSkill`
 * (see plan 1787080851702-german-joke-command-fix.md). Overlapping inputs are scored concurrently
 * on different threads while all of them read/write the single mutable `standardMatchHelper`
 * cached on the fake context; without the shared [lock], one evaluation can score against another
 * input's tokens and spuriously fall back. With the lock every evaluation must resolve to its own
 * skill.
 */
class SkillMatchSelectorTest : StringSpec({

    "overlapping evaluations always resolve to their own skill" {
        val context = FakeSkillContext()
        val lock = Any()
        val ranker = TestRanker()

        runBlocking {
            repeat(500) { i ->
                launch(Dispatchers.Default) {
                    val input = if (i % 2 == 0) JOKE_INPUT else TIME_INPUT
                    val expectedSkill = if (i % 2 == 0) ranker.jokeSkill else ranker.timeSkill

                    val (chosenInput, result) =
                        selectMatchingSkill(lock, context, { ranker.skillRanker }, listOf(input))

                    withClue("round $i input='$input' chosen='$chosenInput'") {
                        chosenInput shouldBe input
                        result.skill shouldBeSameInstanceAs expectedSkill
                    }
                }
            }
        }
    }
})

/**
 * A [SkillContextInternal] whose `standardMatchHelper` is `@Volatile` so that, when the lock is
 * removed, cross-thread writes are actually observed (mirroring the bug) instead of being hidden by
 * thread-local caching and making the race test pass for the wrong reason.
 */
private class FakeSkillContext : SkillContextInternal {
    override val android: Context get() = mocked()
    override val locale: Locale = Locale.GERMAN
    override val sentencesLanguage: String = "de"
    override val parserFormatter: ParserFormatter? get() = null
    override val speechOutputDevice: SpeechOutputDevice get() = mocked()
    override var previousOutput: SkillOutput? = null
    @Volatile
    override var standardMatchHelper: MatchHelper? = null
}

private class TestRanker {
    val jokeSkill: Skill<*> =
        JokeSkill(JokeInfo, Sentences.Joke["de"] ?: error("missing de joke data"), "de")
    val timeSkill: Skill<*> =
        CurrentTimeSkill(CurrentTimeInfo, Sentences.CurrentTime["de"] ?: error("missing de time data"))
    val skillRanker: SkillRanker =
        SkillRanker(
            listOf(jokeSkill, timeSkill),
            MockSkill(Specificity.LOW, 0f),
        )
}