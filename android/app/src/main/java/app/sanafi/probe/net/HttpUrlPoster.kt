package app.sanafi.probe.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class HttpUrlPoster : HttpPoster {
    override suspend fun post(url: String, headers: Map<String, String>, json: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 25_000
                connection.doOutput = true
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                status to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
            } finally {
                connection.disconnect()
            }
        }
}
