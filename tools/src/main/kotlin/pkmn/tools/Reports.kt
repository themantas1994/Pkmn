package pkmn.tools

import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/** A single validation finding. [justification] explains why a finding is a warning rather than an error. */
data class Issue(val code: String, val subject: String, val message: String, val justification: String? = null) {
    fun toJson() = buildJsonObject {
        put("code", code); put("subject", subject); put("message", message)
        justification?.let { put("justification", it) }
    }
}

class Issues {
    val errors = ArrayList<Issue>()
    val warnings = ArrayList<Issue>()
    fun error(code: String, subject: String, message: String) { errors += Issue(code, subject, message) }
    fun warn(code: String, subject: String, message: String, justification: String) { warnings += Issue(code, subject, message, justification) }
    val ok get() = errors.isEmpty()

    fun toJson(extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("ok", ok)
        put("errorCount", errors.size)
        put("warningCount", warnings.size)
        put("errorsByCode", countBy(errors.map { it.code }))
        put("warningsByCode", countBy(warnings.map { it.code }))
        extra()
        put("errors", JsonArray(errors.map { it.toJson() }))
        put("warnings", JsonArray(warnings.map { it.toJson() }))
    }
}

val PrettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

fun writeJson(file: File, json: JsonElement) {
    file.parentFile?.mkdirs()
    file.writeText(PrettyJson.encodeToString(JsonElement.serializer(), json) + "\n")
}

fun countBy(keys: Iterable<String>): JsonObject =
    JsonObject(keys.groupingBy { it }.eachCount().toSortedMap().mapValues { JsonPrimitive(it.value) })

fun strArray(values: Iterable<String>) = JsonArray(values.map { JsonPrimitive(it) })

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Parses a `sha256sum`-style file into relative path -> hash. */
fun readSha256Sums(file: File): Map<String, String> =
    file.readLines().filter { it.isNotBlank() }.associate {
        val (h, p) = it.trim().split(Regex("\\s+"), limit = 2); p.removePrefix("./") to h
    }
