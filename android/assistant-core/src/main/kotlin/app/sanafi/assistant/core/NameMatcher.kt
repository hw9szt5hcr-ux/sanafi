package app.sanafi.assistant.core

sealed interface Match {
    data class One(val index: Int) : Match
    data class Several(val indices: List<Int>) : Match
    data object None : Match
}

/**
 * Finds which contact or app a spoken name means. Rules are tried from strict to loose and the
 * first rule that matches anything decides: the same name; every spoken word starting a word of
 * the name; the name containing what was said.
 */
object NameMatcher {
    private const val MAX_SEVERAL = 5

    fun match(query: String, candidates: List<String>): Match {
        val wanted = ArabicText.normalize(query)
        if (wanted.isEmpty()) return Match.None
        val names = candidates.map(ArabicText::normalize)
        val wantedWords = wanted.split(' ')
        val rules: List<(String) -> Boolean> = listOf(
            { name -> name == wanted },
            { name -> name.split(' ').let { words -> wantedWords.all { w -> words.any { it.startsWith(w) } } } },
            { name -> name.contains(wanted) },
        )
        for (rule in rules) {
            val hits = names.indices.filter { rule(names[it]) }
            if (hits.size == 1) return Match.One(hits.single())
            if (hits.size > 1) return Match.Several(hits.take(MAX_SEVERAL))
        }
        return Match.None
    }
}
