package org.stypox.dicio.eval

import android.util.Log
import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.di.SkillContextInternal

/**
 * Selects the best matching skill for a user input, serializing the synchronous match-selection
 * critical section.
 *
 * Overlapping inputs are evaluated concurrently on `Dispatchers.Default` threads (see
 * `SkillEvaluator.processInputEvent`), and every concurrently-running evaluation reads and writes
 * the shared mutable [SkillContextInternal.standardMatchHelper] field, which caches the
 * tokenization of the input that each skill scored against. Without serialization, the evaluation
 * for input A can overwrite the helper with input B while A is still mid-`getBest`, so A scores
 * against B's tokens and spuriously falls back to the no-match reply. See plan
 * `1787080851702-german-joke-command-fix.md`.
 *
 * This section never suspends, so a plain `synchronized` sharing [matchLock] is sufficient.
 */
internal fun selectMatchingSkill(
    matchLock: Any,
    skillContext: SkillContextInternal,
    skillRanker: () -> SkillRanker,
    utterances: List<String>,
): Pair<String, SkillWithResult<*>> {
    return synchronized(matchLock) {
        try {
            utterances.firstNotNullOfOrNull { input: String ->
                skillContext.standardMatchHelper = MatchHelper(skillContext.parserFormatter, input)
                skillRanker().getBest(skillContext, input)?.let { skillWithResult ->
                    Log.d(
                        TAG,
                        "matched input='$input' skill=${skillWithResult.skill.correspondingSkillInfo.id} " +
                            "score=${skillWithResult.score.scoreIn01Range()}",
                    )
                    Pair(input, skillWithResult)
                }
            } ?: Pair(
                utterances[0],
                skillRanker().getFallbackSkill(skillContext, utterances[0]),
            ).also {
                Log.d(TAG, "matched=none falling back")
            }
        } finally {
            // standardMatchHelper only needs to be set while calling score() on skills, so once all
            // matching and scoring are done, free up the memory it uses (which may be significant
            // since the purpose of MatchHelper is to cache information about the input)
            skillContext.standardMatchHelper = null
        }
    }
}

private const val TAG = "SkillEvaluator"