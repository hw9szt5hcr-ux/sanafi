package app.sanafi.assistant.tools

import android.content.Context
import android.content.Intent

/** The apps on the box's home screen: their names go to the agent, and one can be opened by name. */
class AppCatalog(context: Context) {
    data class App(val label: String, val launch: Intent)

    private val context = context.applicationContext

    fun apps(): List<App> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = context.packageManager
        return try {
            packages.queryIntentActivities(launcher, 0)
        } catch (_: Exception) {
            emptyList()
        }
            .filter { it.activityInfo != null && it.activityInfo.packageName != context.packageName }
            .map { info ->
                val label = info.loadLabel(packages).toString().trim().take(MAX_LABEL)
                App(
                    label,
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            .filter { it.label.isNotEmpty() }
            .sortedBy { it.label }
            .take(MAX_APPS)
    }

    private companion object {
        const val MAX_APPS = 80
        const val MAX_LABEL = 40
    }
}
