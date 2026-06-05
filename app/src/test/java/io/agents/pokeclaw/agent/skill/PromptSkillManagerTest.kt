package io.agents.pokeclaw.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptSkillManagerTest {

    @Test
    fun `parse claude style skill md`() {
        val skill = PromptSkillManager.parseSkillFile(
            idHint = "summarize-changes",
            source = "test",
            content = """
                ---
                name: Summarize Changes
                description: Use when the user asks to summarize repository changes or prepare release notes.
                allowed-tools: get_screen_info, finish
                ---

                # Summarize Changes

                Read the provided context and produce a concise summary.
            """.trimIndent(),
        )

        assertNotNull(skill)
        assertEquals("summarize_changes", skill!!.id)
        assertEquals("Summarize Changes", skill.name)
        assertEquals(listOf("get_screen_info", "finish"), skill.allowedTools)
        assertTrue(skill.body.contains("# Summarize Changes"))
    }

    @Test
    fun `parse yaml list allowed tools`() {
        val skill = PromptSkillManager.parseSkillFile(
            idHint = "open-app",
            source = "test",
            content = """
                ---
                description: Open an app by name when the user asks to launch an installed app.
                allowed-tools:
                  - open_app
                  - finish
                ---

                # Open App

                Launch the app and confirm completion.
            """.trimIndent(),
        )

        assertNotNull(skill)
        assertEquals("open_app", skill!!.id)
        assertEquals(listOf("open_app", "finish"), skill.allowedTools)
    }

    @Test
    fun `reject skill without required description`() {
        val skill = PromptSkillManager.parseSkillFile(
            idHint = "bad-skill",
            source = "test",
            content = """
                ---
                name: Bad Skill
                ---

                # Bad Skill
            """.trimIndent(),
        )

        assertNull(skill)
    }
}
