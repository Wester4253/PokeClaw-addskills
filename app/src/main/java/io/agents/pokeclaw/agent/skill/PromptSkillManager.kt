// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.skill

import android.content.Context
import android.os.Environment
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.utils.XLog
import java.io.File
import java.util.Locale

/**
 * Claude Code-style prompt skills.
 *
 * These are different from deterministic SkillRegistry skills. A prompt skill is a
 * user-authored Markdown instruction file loaded conditionally into the model prompt:
 *
 *   skills/<skill-id>/SKILL.md
 *
 * with YAML frontmatter followed by Markdown instructions.
 */
object PromptSkillManager {

    private const val TAG = "PromptSkillManager"
    private const val ASSET_SKILL_DIR = "skills"
    private const val MAX_ACTIVE_SKILL_CHARS = 8_000

    data class PromptSkill(
        val id: String,
        val name: String,
        val description: String,
        val allowedTools: List<String>,
        val body: String,
        val source: String,
    )

    private val skills = linkedMapOf<String, PromptSkill>()

    fun loadAll(context: Context) {
        skills.clear()
        loadAssetSkills(context)
        loadUserSkills(context)
        XLog.i(TAG, "Loaded ${skills.size} prompt skills")
    }

    fun reload(context: Context) = loadAll(context)

    fun getAll(): List<PromptSkill> = skills.values.toList()

    fun findById(id: String): PromptSkill? = skills[normalizeId(id)]

    fun findBestMatch(userRequest: String): PromptSkill? {
        val request = userRequest.trim()
        if (request.isBlank()) return null

        val explicit = findExplicitInvocation(request)
        if (explicit != null) return explicit

        val requestTokens = tokenize(request)
        if (requestTokens.isEmpty()) return null

        var best: PromptSkill? = null
        var bestScore = 0
        for (skill in skills.values) {
            val haystack = "${skill.name} ${skill.description}"
            val skillTokens = tokenize(haystack)
            val overlap = requestTokens.count { it in skillTokens }
            val phraseBonus = phraseBonus(request, skill)
            val score = overlap + phraseBonus
            if (score > bestScore) {
                best = skill
                bestScore = score
            }
        }

        // A score below 2 tends to be accidental overlap like "send" or "open".
        return if (bestScore >= 2) best else null
    }

    fun buildPromptSection(userRequest: String): String {
        if (skills.isEmpty()) return ""

        val selected = findBestMatch(userRequest)
        val index = skills.values.joinToString("\n") { skill ->
            "- ${skill.id}: ${skill.description}"
        }

        return buildString {
            append("\n\n## Custom User Skills\n")
            append("These are Claude Code-style SKILL.md instructions installed by the user. ")
            append("Use a skill only when it clearly matches the current request; otherwise ignore it.\n")
            append("Available skills:\n")
            append(index)
            if (selected != null) {
                append("\n\n### Active Skill: ${selected.name}\n")
                append("- ID: ${selected.id}\n")
                append("- Source: ${selected.source}\n")
                if (selected.allowedTools.isNotEmpty()) {
                    append("- Allowed tools requested by skill: ${selected.allowedTools.joinToString(", ")}\n")
                    append("Prefer those tools while following this skill when equivalent PokeClaw tools exist.\n")
                }
                append("\nFollow this skill's Markdown instructions for the current request:\n\n")
                append(selected.body.take(MAX_ACTIVE_SKILL_CHARS))
                if (selected.body.length > MAX_ACTIVE_SKILL_CHARS) {
                    append("\n\n[Skill body truncated to ${MAX_ACTIVE_SKILL_CHARS} characters.]")
                }
            }
        }
    }

    fun parseSkillFile(idHint: String, content: String, source: String): PromptSkill? {
        if (!content.startsWith("---")) {
            XLog.w(TAG, "Skipping skill without frontmatter: $source")
            return null
        }
        val endIndex = content.indexOf("\n---", startIndex = 3)
        if (endIndex < 0) {
            XLog.w(TAG, "Skipping skill with unterminated frontmatter: $source")
            return null
        }

        val frontmatter = content.substring(3, endIndex).trim()
        val body = content.substring(endIndex + 4).trim()
        val fields = parseFlatYaml(frontmatter)
        val id = normalizeId(fields["id"] ?: fields["name"] ?: idHint)
        val name = (fields["name"] ?: idHint).trim().ifBlank { id }
        val description = fields["description"]?.trim().orEmpty()
        val allowedTools = parseToolList(fields["allowed-tools"] ?: fields["tools"].orEmpty())

        if (id.isBlank() || description.isBlank() || body.isBlank()) {
            XLog.w(TAG, "Skipping invalid skill $source: id, description, and body are required")
            return null
        }

        warnUnknownTools(id, allowedTools)
        return PromptSkill(
            id = id,
            name = name,
            description = description,
            allowedTools = allowedTools,
            body = body,
            source = source,
        )
    }

    private fun loadAssetSkills(context: Context) {
        try {
            val entries = context.assets.list(ASSET_SKILL_DIR) ?: return
            for (entry in entries) {
                val path = "$ASSET_SKILL_DIR/$entry"
                try {
                    val children = context.assets.list(path)
                    if (children?.contains("SKILL.md") == true) {
                        val content = context.assets.open("$path/SKILL.md").bufferedReader().use { it.readText() }
                        registerParsed(entry, content, "assets/$path/SKILL.md")
                    } else if (entry.endsWith(".skill.md") || entry == "SKILL.md") {
                        val content = context.assets.open(path).bufferedReader().use { it.readText() }
                        registerParsed(entry.removeSuffix(".skill.md").removeSuffix(".md"), content, "assets/$path")
                    }
                } catch (e: Exception) {
                    XLog.w(TAG, "Failed to load asset skill: $path", e)
                }
            }
        } catch (e: Exception) {
            XLog.d(TAG, "No asset prompt skills found")
        }
    }

    private fun loadUserSkills(context: Context) {
        userSkillDirs(context).forEach { root ->
            if (!root.exists()) {
                root.mkdirs()
                return@forEach
            }
            if (!root.isDirectory || !root.canRead()) return@forEach

            root.listFiles()?.forEach { entry ->
                try {
                    when {
                        entry.isDirectory -> {
                            val skillFile = File(entry, "SKILL.md")
                            if (skillFile.isFile && skillFile.canRead()) {
                                registerParsed(entry.name, skillFile.readText(), skillFile.absolutePath)
                            }
                        }
                        entry.isFile && (entry.name.endsWith(".skill.md") || entry.name == "SKILL.md") -> {
                            val idHint = entry.name.removeSuffix(".skill.md").removeSuffix(".md")
                            registerParsed(idHint, entry.readText(), entry.absolutePath)
                        }
                    }
                } catch (e: Exception) {
                    XLog.w(TAG, "Failed to load user skill: ${entry.absolutePath}", e)
                }
            }
        }
    }

    private fun registerParsed(idHint: String, content: String, source: String) {
        val skill = parseSkillFile(idHint, content, source) ?: return
        skills[skill.id] = skill
        XLog.d(TAG, "Loaded prompt skill: ${skill.id} from $source")
    }

    private fun userSkillDirs(context: Context): List<File> {
        val dirs = mutableListOf<File>()
        context.getExternalFilesDir(null)?.let { dirs.add(File(it, "skills")) }
        context.filesDir?.let { dirs.add(File(it, "skills")) }
        @Suppress("DEPRECATION")
        dirs.add(File(Environment.getExternalStorageDirectory(), "PokeClaw/skills"))
        return dirs.distinctBy { it.absolutePath }
    }

    private fun parseFlatYaml(frontmatter: String): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        var activeListKey: String? = null
        val activeList = mutableListOf<String>()

        fun flushList() {
            val key = activeListKey ?: return
            fields[key] = activeList.joinToString(", ")
            activeListKey = null
            activeList.clear()
        }

        for (line in frontmatter.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue

            if (activeListKey != null && trimmed.startsWith("- ")) {
                activeList.add(unquote(trimmed.substringAfter("- ").trim()))
                continue
            }
            flushList()

            val idx = trimmed.indexOf(':')
            if (idx <= 0) continue

            val key = trimmed.substring(0, idx).trim().lowercase(Locale.US)
            val value = trimmed.substring(idx + 1).trim()
            if (value.isBlank()) {
                activeListKey = key
            } else {
                fields[key] = unquote(value)
            }
        }
        flushList()
        return fields
    }

    private fun parseToolList(value: String): List<String> =
        value.split(',', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun warnUnknownTools(skillId: String, tools: List<String>) {
        if (tools.isEmpty()) return
        val knownTools = ToolRegistry.getAllTools().map { it.getName() }.toSet()
        if (knownTools.isEmpty()) return
        val unknown = tools.filterNot { it in knownTools }
        if (unknown.isNotEmpty()) {
            XLog.w(TAG, "Skill $skillId references unknown/non-PokeClaw tools: ${unknown.joinToString(", ")}")
        }
    }

    private fun findExplicitInvocation(request: String): PromptSkill? {
        val firstToken = request.trim().substringBefore(' ')
        if (!firstToken.startsWith("/")) return null
        val id = normalizeId(firstToken.removePrefix("/"))
        return skills[id]
    }

    private fun phraseBonus(request: String, skill: PromptSkill): Int {
        val lower = request.lowercase(Locale.US)
        var score = 0
        if (lower.contains(skill.id.replace('_', ' '))) score += 3
        if (lower.contains(skill.name.lowercase(Locale.US))) score += 3
        return score
    }

    private fun tokenize(value: String): Set<String> =
        value.lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()

    private fun normalizeId(value: String): String =
        value.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

    private fun unquote(value: String): String =
        value.removeSurrounding("\"").removeSurrounding("'")

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "when", "use", "uses", "user", "this",
        "that", "from", "into", "about", "what", "how", "why", "you", "your",
        "skill", "request", "should", "would", "could", "please", "example",
    )
}
