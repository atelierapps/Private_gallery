package com.atelierapps.vault.filter

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {

    private val item = listOf("Sunset at the beach.mp4", "beach", "holiday", "best angle at 0:40")

    @Test fun blankMatchesEverything() = assertEquals(Search.EXACT, Search.score("  ", item))

    @Test fun wholePhraseIsExact() = assertEquals(Search.EXACT, Search.score("at the beach", item))

    @Test fun wordsInDifferentFieldsAreExact() =
        assertEquals(Search.EXACT, Search.score("holiday sunset", item))

    @Test fun hashPrefixFindsTag() = assertEquals(Search.EXACT, Search.score("#holiday", item))

    @Test fun swappedLettersAreNear() = assertEquals(Search.NEAR, Search.score("beahc", item))

    @Test fun oneWrongLetterIsNear() = assertEquals(Search.NEAR, Search.score("sunsez", item))

    @Test fun slipInWordStillBeingTyped() = assertEquals(Search.NEAR, Search.score("holidya", item))

    @Test fun startOfLongerWordWithSlip() =
        assertEquals(Search.NEAR, Search.score("hloid", listOf("holiday")))

    @Test fun shortWordsMustBeExact() = assertEquals(Search.NONE, Search.score("cat", item))

    @Test fun oneUnmatchedWordFailsAll() = assertEquals(Search.NONE, Search.score("beach mountain", item))

    @Test fun transpositionCostsOne() = assertEquals(1, Search.distance("beahc", "beach", 2))

    @Test fun distanceCapsAtLimit() = assertEquals(2, Search.distance("abcdef", "uvwxyz", 1))
}
