package org.stypox.dicio.eval

import android.util.Log
import org.dicio.skill.skill.Skill
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.construct.Construct
import org.dicio.skill.standard.util.MatchHelper
import org.dicio.skill.standard.util.initialMemToEnd
import org.dicio.skill.util.CleanableUp
import org.stypox.dicio.sentences.Sentences
import java.util.Stack

class SkillRanker(
    defaultSkillBatch: List<Skill<*>>,
    private var fallbackSkill: Skill<*>
) : CleanableUp {

    private class SkillBatch(skills: List<Skill<*>>) {
        // all of the skills by specificity category (high, medium and low)
        private val highSkills: MutableList<Skill<*>> = ArrayList()
        private val mediumSkills: MutableList<Skill<*>> = ArrayList()
        private val lowSkills: MutableList<Skill<*>> = ArrayList()

        init {
            for (skill in skills) {
                when (skill.specificity) {
                    Specificity.HIGH -> highSkills.add(skill)
                    Specificity.MEDIUM -> mediumSkills.add(skill)
                    Specificity.LOW -> lowSkills.add(skill)
                }
            }
        }

        fun getBest(ctx: SkillContext, input: String): SkillWithResult<*>? {
            Log.d(
                TAG,
                "batch input='$input' " +
                    "high=${highSkills.map { it.correspondingSkillInfo.id }} " +
                    "medium=${mediumSkills.map { it.correspondingSkillInfo.id }} " +
                    "low=${lowSkills.map { it.correspondingSkillInfo.id }}",
            )

            // === Phase 5 discriminator probes (temporary, remove after diagnosis) ===
            try {
                val h = ctx.standardMatchHelper
                Log.d(TAG, "helper sameInstance=${h?.userInput === input} equals=${h?.userInput == input} " +
                    "userInputLen=${h?.userInput?.length} words=${h?.splitWords?.map { it.nfkdNormalizedText }}")
                Log.d(TAG, "inputHex=${input.take(32).map { it.code.toString(16).padStart(4, '0') }.joinToString("")} " +
                    "regexSanity witz=${Regex("witz").matches("witz")} " +
                    "wordPatternCount=${Regex("\\p{L}+").findAll(input).count()}")
                Sentences.Joke["de"]?.let { jokeData ->
                    val hardInput = "hast du einen witz"
                    val (s1, _) = jokeData.score(MatchHelper(null, hardInput), hardInput)
                    val (s2, _) = jokeData.score(MatchHelper(null, input), input)
                    Log.d(TAG, "probeHardcoded=${s1.scoreIn01Range()} probeRuntime=${s2.scoreIn01Range()}")

                    // Phase 6.2: graph dump
                    jokeData.toString().chunked(1500).forEachIndexed { i, c ->
                        Log.d(TAG, "jokeGraph[$i]: $c")
                    }

                    // Phase 6.3: per-sentence reflection breakdown
                    dumpSentences(jokeData, "joke", hardInput)
                }
                Sentences.CurrentTime["de"]?.let { ctData ->
                    val ctInput = "wie spät ist es"
                    val (ctScore, _) = ctData.score(MatchHelper(null, ctInput), ctInput)
                    Log.d(TAG, "ctWhole=${ctScore.scoreIn01Range()}")
                    dumpSentences(ctData, "currentTime", ctInput)
                }
            } catch (t: Throwable) {
                Log.d(TAG, "probe error: $t")
            }
            // === end Phase 5 probes ===

            // first round: considering only high-priority skills
            val bestHigh = getBestForSpecificity(ctx, highSkills, input)
            if (bestHigh != null && bestHigh.score.scoreIn01Range() > HIGH_THRESHOLD_1) {
                Log.d(TAG, "round1 winner=${bestHigh.skill.correspondingSkillInfo.id} " +
                    "score=${bestHigh.score.scoreIn01Range()} (threshold $HIGH_THRESHOLD_1)")
                return bestHigh
            }

            // second round: considering both medium- and high-priority skills
            val bestMedium = getBestForSpecificity(ctx, mediumSkills, input)
            if (bestMedium != null && bestMedium.score.scoreIn01Range() > MEDIUM_THRESHOLD_2) {
                Log.d(TAG, "round2 winner=${bestMedium.skill.correspondingSkillInfo.id} " +
                    "score=${bestMedium.score.scoreIn01Range()} (threshold $MEDIUM_THRESHOLD_2)")
                return bestMedium
            } else if (bestHigh != null && bestHigh.score.scoreIn01Range() > HIGH_THRESHOLD_2) {
                Log.d(TAG, "round2 winner=${bestHigh.skill.correspondingSkillInfo.id} " +
                    "score=${bestHigh.score.scoreIn01Range()} (threshold $HIGH_THRESHOLD_2)")
                return bestHigh
            }

            // third round: all skills are considered
            val bestLow = getBestForSpecificity(ctx, lowSkills, input)
            if (bestLow != null && bestLow.score.scoreIn01Range() > LOW_THRESHOLD_3) {
                Log.d(TAG, "round3 winner=${bestLow.skill.correspondingSkillInfo.id} " +
                    "score=${bestLow.score.scoreIn01Range()} (threshold $LOW_THRESHOLD_3)")
                return bestLow
            } else if (bestMedium != null && bestMedium.score.scoreIn01Range() > MEDIUM_THRESHOLD_3) {
                Log.d(TAG, "round3 winner=${bestMedium.skill.correspondingSkillInfo.id} " +
                    "score=${bestMedium.score.scoreIn01Range()} (threshold $MEDIUM_THRESHOLD_3)")
                return bestMedium
            } else if (bestHigh != null && bestHigh.score.scoreIn01Range() > HIGH_THRESHOLD_3) {
                Log.d(TAG, "round3 winner=${bestHigh.skill.correspondingSkillInfo.id} " +
                    "score=${bestHigh.score.scoreIn01Range()} (threshold $HIGH_THRESHOLD_3)")
                return bestHigh
            }

            Log.d(TAG, "no match: bestHigh=${bestHigh?.let { it.skill.correspondingSkillInfo.id to it.score.scoreIn01Range() }} " +
                "bestMedium=${bestMedium?.let { it.skill.correspondingSkillInfo.id to it.score.scoreIn01Range() }} " +
                "bestLow=${bestLow?.let { it.skill.correspondingSkillInfo.id to it.score.scoreIn01Range() }}")
            // nothing was matched
            return null
        }

        private fun dumpSentences(data: Any, label: String, input: String) {
            try {
                val field = data.javaClass.getDeclaredField("sentencesWithId")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val sentences = field.get(data) as List<Pair<String, Any>>
                Log.d(TAG, "$label sentences=${sentences.size}")
                val h = org.dicio.skill.standard.util.MatchHelper(null, input)
                sentences.forEachIndexed { i, (id, construct) ->
                    val mem = initialMemToEnd(h.cumulativeWeight)
                    (construct as Construct).matchToEnd(mem, h)
                    val s = mem[0]
                    Log.d(
                        TAG,
                        "$label sentence[$i] id=$id um=${s.userMatched} uw=${s.userWeight} " +
                            "rm=${s.refMatched} rw=${s.refWeight} in01=${s.scoreIn01Range()}",
                    )
                }
            } catch (t: Throwable) {
                Log.d(TAG, "$label breakdown error: $t")
            }
        }

        companion object {
            private val TAG = SkillBatch::class.simpleName

            private fun getBestForSpecificity(
                ctx: SkillContext,
                skills: List<Skill<*>>,
                input: String,
            ): SkillWithResult<*>? {
                // this ensures that if `skills` is empty and null skill is returned,
                // nothing bad happens since its score cannot be higher than any other float value.
                var bestSkillSoFar: SkillWithResult<*>? = null
                for (skill in skills) {
                    val res = skill.scoreAndWrapResult(ctx, input)
                    if (bestSkillSoFar == null || res.score.isBetterThan(bestSkillSoFar.score)) {
                        bestSkillSoFar = res
                    }
                }
                return bestSkillSoFar
            }
        }
    }

    private var defaultBatch: SkillBatch = SkillBatch(defaultSkillBatch)
    private val batches: Stack<SkillBatch> = Stack()

    fun addBatchToTop(skillBatch: List<Skill<*>>) {
        batches.push(SkillBatch(skillBatch))
    }

    fun hasAnyBatches(): Boolean {
        return batches.isNotEmpty()
    }

    fun removeTopBatch() {
        if (!batches.isEmpty()) {
            batches.pop()
        }
    }

    fun removeAllBatches() {
        batches.removeAllElements()
    }

    fun getBest(
        ctx: SkillContext,
        input: String,
    ): SkillWithResult<*>? {
        for (i in batches.indices.reversed()) {
            val skillFromBatch = batches[i].getBest(ctx, input)
            if (skillFromBatch != null) {
                // found a matching skill: remove all skills in batch above it
                for (j in i + 1 until batches.size) {
                    removeTopBatch()
                }
                return skillFromBatch
            }
        }

        val skillFromBatch = defaultBatch.getBest(ctx, input)
        if (skillFromBatch != null) {
            // found a matching skill in the default batch: remove all other skill batches
            removeAllBatches()
        }
        return skillFromBatch
    }

    fun getFallbackSkill(
        ctx: SkillContext,
        input: String,
    ): SkillWithResult<*> {
        return fallbackSkill.scoreAndWrapResult(ctx, input)
    }

    override fun cleanup() {
        batches.clear()
    }

    companion object {
        // various thresholds for different specificity categories (high, medium and low)
        // first round
        private const val HIGH_THRESHOLD_1 = 0.85f

        // second round
        private const val MEDIUM_THRESHOLD_2 = 0.90f
        private const val HIGH_THRESHOLD_2 = 0.80f

        // third round
        private const val LOW_THRESHOLD_3 = 0.90f
        private const val MEDIUM_THRESHOLD_3 = 0.80f
        private const val HIGH_THRESHOLD_3 = 0.70f
    }
}
