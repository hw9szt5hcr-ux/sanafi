package app.sanafi.assistant.tools

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import java.net.URLEncoder

/** The Android requests behind the commands. Each opens another app in its own task. */
object Intents {
    const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    const val GOOGLE_MAPS = "com.google.android.apps.maps"

    private fun encoded(text: String): String = URLEncoder.encode(text, "UTF-8")
    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Asks YouTube Music to search and start playing, the way a voice assistant does. */
    fun playMusic(query: String): Intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        .setPackage(YOUTUBE_MUSIC)
        .putExtra(SearchManager.QUERY, query)
        .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens YouTube Music on the search results when it does not offer play-from-search. */
    fun playMusicFallback(query: String): Intent = view("https://music.youtube.com/search?q=${encoded(query)}").setPackage(YOUTUBE_MUSIC)

    /** Starts turn-by-turn directions in Google Maps. */
    fun navigate(place: String): Intent = view("google.navigation:q=${encoded(place)}").setPackage(GOOGLE_MAPS)

    /** Shows the place in whatever map app the box has. */
    fun navigateFallback(place: String): Intent = view("geo:0,0?q=${encoded(place)}")

    fun call(number: String): Intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun dial(number: String): Intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
