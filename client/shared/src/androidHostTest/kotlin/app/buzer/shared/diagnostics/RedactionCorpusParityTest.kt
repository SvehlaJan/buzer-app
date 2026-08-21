package app.buzer.shared.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RedactionCorpusParityTest {
    private val redactor = DiagnosticRedactor()

    private fun findCorpusFile(): File {
        val candidates = listOf(
            File("../../test-fixtures/observability/redaction-corpus.json"),
            File("../test-fixtures/observability/redaction-corpus.json"),
            File("test-fixtures/observability/redaction-corpus.json")
        )
        for (candidate in candidates) {
            if (candidate.exists()) {
                return candidate
            }
        }
        var currentDir: File? = File(".").canonicalFile
        while (currentDir != null) {
            val probe = File(currentDir, "test-fixtures/observability/redaction-corpus.json")
            if (probe.exists()) return probe
            currentDir = currentDir.parentFile
        }
        error("Could not find test-fixtures/observability/redaction-corpus.json from ${File(".").canonicalPath}")
    }

    @Test
    fun runsAllCorpusTestCases() {
        val corpusFile = findCorpusFile()
        val jsonText = corpusFile.readText()
        val corpus = Json.parseToJsonElement(jsonText).jsonObject

        val sentinels = corpus["sentinels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertFalse(sentinels.isEmpty(), "Corpus must define sentinels")

        // 1. Test string cases
        val stringCases = corpus["stringCases"]?.jsonArray ?: error("Missing stringCases")
        for (caseElement in stringCases) {
            val caseObj = caseElement.jsonObject
            val name = caseObj["name"]?.jsonPrimitive?.content ?: "unnamed"
            val input = caseObj["input"]?.jsonPrimitive?.content ?: error("Missing input in $name")
            val expected = caseObj["expected"]?.jsonPrimitive?.content ?: error("Missing expected in $name")

            val actual = redactor.redactString(input)
            assertEquals(expected, actual, "Failed string case: $name")

            for (sentinel in sentinels) {
                assertFalse(actual.contains(sentinel), "Result of $name still contains sentinel $sentinel")
            }
        }

        // 2. Test json cases
        val jsonCases = corpus["jsonCases"]?.jsonArray ?: error("Missing jsonCases")
        for (caseElement in jsonCases) {
            val caseObj = caseElement.jsonObject
            val name = caseObj["name"]?.jsonPrimitive?.content ?: "unnamed"
            val input = caseObj["input"] ?: error("Missing input in $name")
            val expected = caseObj["expected"] ?: error("Missing expected in $name")

            val actual = redactor.redactJson(input)
            assertEquals(expected, actual, "Failed json case: $name")

            val serializedActual = Json.encodeToString(JsonElement.serializer(), actual)
            for (sentinel in sentinels) {
                assertFalse(serializedActual.contains(sentinel), "Result of $name still contains sentinel $sentinel")
            }
        }
    }
}
