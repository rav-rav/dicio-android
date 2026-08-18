package org.stypox.dicio.skills.joke

import io.kotest.core.spec.style.StringSpec
import org.dicio.skill.standard.construct.Construct
import org.dicio.skill.standard.util.MatchHelper
import org.dicio.skill.standard.util.initialMemToEnd
import org.stypox.dicio.sentences.Sentences

/**
 * JVM reference for the device per-sentence breakdown (Phase 6.4 of plan
 * 1787080851702-german-joke-command-fix.md). Mirrors the temporary device probe in
 * `SkillRanker.SkillBatch.dumpSentences`: scores each generated sentence in isolation against a
 * clean ASCII input and prints the raw StandardScore tuple. The device probe reports
 * `probeHardcoded=0.36363637` here; the JVM must show 1.0 for the whole graph to reproduce the bug.
 */
class JokeSentencesBreakdownDeTest : StringSpec({
    val wholeInput = "hast du einen witz"
    val jokeData = Sentences.Joke["de"]!!

    "whole graph equals passed score" {
        val (score, _) = jokeData.score(MatchHelper(null, wholeInput), wholeInput)
        println("@JVM-BREAKDOWN jokeWhole in01=${score.scoreIn01Range()} um=${score.userMatched} " +
            "uw=${score.userWeight} rm=${score.refMatched} rw=${score.refWeight}")
    }

    "per-sentence isolation matches device probe" {
        val field = jokeData.javaClass.getDeclaredField("sentencesWithId")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sentences = field.get(jokeData) as List<Pair<String, Construct>>
        println("@JVM-BREAKDOWN joke sentences=${sentences.size}")
        val h = MatchHelper(null, wholeInput)
        sentences.forEachIndexed { i, (id, construct) ->
            val mem = initialMemToEnd(h.cumulativeWeight)
            construct.matchToEnd(mem, h)
            val s = mem[0]
            println("@JVM-BREAKDOWN joke sentence[$i] id=$id um=${s.userMatched} uw=${s.userWeight} " +
                "rm=${s.refMatched} rw=${s.refWeight} in01=${s.scoreIn01Range()}")
        }
    }

    "currentTime whole graph" {
        val ctInput = "wie spät ist es"
        val ctData = Sentences.CurrentTime["de"]!!
        val (score, _) = ctData.score(MatchHelper(null, ctInput), ctInput)
        println("@JVM-BREAKDOWN ctWhole in01=${score.scoreIn01Range()} um=${score.userMatched} " +
            "uw=${score.userWeight} rm=${score.refMatched} rw=${score.refWeight}")
    }
})