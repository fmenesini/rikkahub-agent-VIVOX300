package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchToolsTest {
    @Test
    fun `exa search tool exposes optional evidence parameters`() {
        val settings = Settings(
            searchServices = listOf(SearchServiceOptions.ExaOptions()),
            searchServiceSelected = 0,
        )
        val searchTool = createSearchTools(settings).single { it.name == "search_web" }
        val schema = searchTool.parameters() as InputSchema.Obj

        assertEquals(listOf("query"), schema.required)
        assertTrue(schema.properties.containsKey("startPublishedDate"))
        assertTrue(schema.properties.containsKey("endPublishedDate"))
        assertTrue(schema.properties.containsKey("includeDomains"))
        assertTrue(schema.properties.containsKey("excludeDomains"))
        assertTrue(schema.properties.containsKey("maxAgeHours"))
    }

    @Test
    fun `exa scrape tool keeps url required and freshness optional`() {
        val settings = Settings(
            searchServices = listOf(SearchServiceOptions.ExaOptions()),
            searchServiceSelected = 0,
        )
        val scrapeTool = createSearchTools(settings).single { it.name == "scrape_web" }
        val schema = scrapeTool.parameters() as InputSchema.Obj

        assertEquals(listOf("url"), schema.required)
        assertTrue(schema.properties.containsKey("maxAgeHours"))
    }

    @Test
    fun `scrape_web is approval-gated at runtime, search_web is not`() {
        // Regression: scrape_web was in ALWAYS_ASK but createSearchTools bypassed the
        // mapping that turns the set into needsApproval, so it still ran unprompted.
        val settings = Settings(
            searchServices = listOf(SearchServiceOptions.ExaOptions()),
            searchServiceSelected = 0,
        )
        val tools = createSearchTools(settings).associateBy { it.name }
        val args = kotlinx.serialization.json.JsonObject(emptyMap())
        assertTrue(tools.getValue("scrape_web").needsApproval(args))
        assertEquals(false, tools.getValue("search_web").needsApproval(args))
    }
}
