package app.sanafi.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NameMatcherTest {
    @Test
    fun `spelling variants of the same name are equal after normalising`() {
        assertEquals(ArabicText.normalize("أَحْمَد"), ArabicText.normalize("احمد"))
        assertEquals(ArabicText.normalize("فاطمة"), ArabicText.normalize("فاطمه"))
        assertEquals(ArabicText.normalize("مصطفى"), ArabicText.normalize("مصطفي"))
        assertEquals(ArabicText.normalize("مؤمن"), ArabicText.normalize("مومن"))
        assertEquals(ArabicText.normalize("رائد"), ArabicText.normalize("رايد"))
        assertEquals(ArabicText.normalize("آمنة"), ArabicText.normalize("امنه"))
        assertEquals(ArabicText.normalize("عبـــدالله"), ArabicText.normalize("عبدالله"))
        assertEquals("youtube music", ArabicText.normalize("  YouTube   MUSIC "))
    }

    @Test
    fun `an exact name wins over longer names that contain it`() {
        assertEquals(Match.One(1), NameMatcher.match("احمد", listOf("أحمد العتيبي", "أحمد", "محمد أحمد")))
    }

    @Test
    fun `two entries with exactly the same name are both returned`() {
        assertEquals(Match.Several(listOf(0, 2)), NameMatcher.match("سعد", listOf("سعد", "سعد القحطاني", "سعد")))
    }

    @Test
    fun `a first name shared by two contacts returns both, in order`() {
        assertEquals(Match.Several(listOf(0, 2)), NameMatcher.match("خالد", listOf("خالد المري", "سعد", "خالد الدوسري")))
    }

    @Test
    fun `word starts match so a short app name finds its full label`() {
        assertEquals(Match.One(0), NameMatcher.match("youtube", listOf("YouTube Music", "Maps")))
        assertEquals(Match.One(1), NameMatcher.match("خرائط", listOf("YouTube", "خرائط Google")))
    }

    @Test
    fun `every word of the query must start a word of the name`() {
        assertEquals(Match.One(1), NameMatcher.match("خالد الدوسري", listOf("خالد المري", "خالد بن فهد الدوسري")))
    }

    @Test
    fun `a word start beats a name that merely contains the query`() {
        assertEquals(Match.One(1), NameMatcher.match("تس", listOf("واتساب", "تسجيلات")))
    }

    @Test
    fun `containment is used when no word starts with the query`() {
        assertEquals(Match.One(0), NameMatcher.match("ساب", listOf("واتساب", "خرائط")))
    }

    @Test
    fun `nothing similar and a blank query are no match`() {
        assertEquals(Match.None, NameMatcher.match("تيك توك", listOf("YouTube", "Maps")))
        assertEquals(Match.None, NameMatcher.match("  ", listOf("YouTube")))
        assertEquals(Match.None, NameMatcher.match("x", emptyList()))
    }

    @Test
    fun `more than five hits are cut to the first five`() {
        assertEquals(Match.Several(listOf(0, 1, 2, 3, 4)), NameMatcher.match("محمد", List(8) { "محمد $it" }))
    }
}
