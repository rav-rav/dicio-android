package org.stypox.dicio.skills.joke

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.sentences.Sentences

/**
 * Empirical reproduction of the German joke fallback bug (see plan:
 * 1787080851702-german-joke-command-fix.md). Scores German joke sentences against the generated
 * matcher data directly, using the MatchHelper overload so no Android/SkillContext mocks are needed.
 *
 * If this test passes, the matcher itself is fine and the defect is app-side wiring/runtime (Phase
 * 2a). If it fails, there is a real matcher/compiler defect (Phase 2b).
 */
class JokeSentencesDeTest : StringSpec({
    val data = Sentences.Joke["de"]
    data shouldNotBe null

    "German joke commands should score above the HIGH threshold and resolve to Command" {
        val inputs = listOf(
            "hast du einen witz",
            "erzähle mir einen witz",
            "erzähl mir einen witz",
            "kennst du einen witz",
            "sag etwas lustiges",
            "bring mich zum lachen",
        )

        for (input in inputs) {
            val (score, inputData) = data!!.score(MatchHelper(null, input), input)
            score.scoreIn01Range() shouldBeGreaterThan 0.85f
            inputData shouldBe Sentences.Joke.Command
        }
    }
})