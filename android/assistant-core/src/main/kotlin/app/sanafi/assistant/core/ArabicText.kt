package app.sanafi.assistant.core

/** Makes Arabic and Latin names comparable: the same name typed two ways becomes the same text. */
object ArabicText {
    private val marks = Regex("[\\u064B-\\u0652\\u0670\\u0640]")
    private val spaces = Regex("\\s+")

    fun normalize(text: String): String {
        val folded = StringBuilder(text.length)
        for (ch in marks.replace(text, "")) {
            folded.append(
                when (ch) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ى' -> 'ي'
                    'ة' -> 'ه'
                    'ؤ' -> 'و'
                    'ئ' -> 'ي'
                    else -> ch.lowercaseChar()
                },
            )
        }
        return spaces.replace(folded.toString(), " ").trim()
    }
}
