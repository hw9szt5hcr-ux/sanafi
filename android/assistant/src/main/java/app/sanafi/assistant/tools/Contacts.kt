package app.sanafi.assistant.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone

/** The contacts saved on the box (its Google account), one entry per person. */
class Contacts(context: Context) {
    data class Entry(val name: String, val number: String)

    private val context = context.applicationContext

    fun hasPermission(): Boolean = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Each contact with its first phone number; empty without the permission. */
    fun entries(): List<Entry> {
        if (!hasPermission()) return emptyList()
        val found = LinkedHashMap<Long, Entry>()
        try {
            context.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER), null, null, Phone.DISPLAY_NAME)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1)?.trim().orEmpty()
                    val number = cursor.getString(2)?.trim().orEmpty()
                    if (name.isNotEmpty() && number.isNotEmpty() && id !in found) found[id] = Entry(name, number)
                }
            }
        } catch (_: Exception) {
            // A broken contacts provider reads as no contacts; the tool then says the name was not found.
        }
        return found.values.toList()
    }
}
