package io.github.prashanthsams.pytesthtmlreporter.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.streams.asSequence

/** Every message the code and plugin.xml ask for exists, and apostrophes render as written. */
class MessageBundleTest {

    private val bundle = Properties().apply {
        Files.newBufferedReader(Path.of("src/main/resources/messages/PytestHtmlReporterBundle.properties")).use(::load)
    }

    @Test
    fun `every referenced key exists`() {
        val code = Files.walk(Path.of("src/main/kotlin")).use { paths ->
            paths.asSequence().filter { it.toString().endsWith(".kt") }.map(Files::readString).toList()
        }
        val fromCode = code.flatMap { Regex("""message\("([A-Za-z0-9_.]+)"""").findAll(it).map { m -> m.groupValues[1] } }
        val descriptors = listOf("plugin.xml", "pytest-html-reporter-python.xml").map {
            Files.readString(Path.of("src/main/resources/META-INF", it))
        }
        val fromXml = descriptors.flatMap { Regex("""key="([A-Za-z0-9_.]+)"""").findAll(it).map { m -> m.groupValues[1] } }
        val actionIds = descriptors.flatMap { Regex("""<action id="([A-Za-z0-9_.]+)"""").findAll(it).map { m -> "action.${m.groupValues[1]}.text" } }

        val missing = (fromCode + fromXml + actionIds).distinct().filterNot(bundle::containsKey)
        assertTrue("missing message keys: $missing", missing.isEmpty())
        assertTrue(fromCode.size > 50)
    }

    @Test
    fun `apostrophes follow the MessageFormat rules`() {
        // With parameters a message goes through MessageFormat, where a lone ' starts a quote and '' is an apostrophe;
        // without parameters it is shown verbatim, so '' would appear doubled.
        val problems = bundle.stringPropertyNames().filter { key ->
            val value = bundle.getProperty(key)
            val parameterised = Regex("""\{\d+}""").containsMatchIn(value)
            if (parameterised) Regex("""(?<!')'(?!')""").containsMatchIn(value) else "''" in value
        }
        assertTrue("apostrophe problems in: $problems", problems.isEmpty())
    }
}
