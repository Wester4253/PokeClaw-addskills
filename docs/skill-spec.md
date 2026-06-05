# PokeClaw Custom Skill File Specification

PokeClaw supports Claude Code-style Markdown skills for both cloud and local models.
A skill is a folder containing a `SKILL.md` file with YAML frontmatter followed by
Markdown instructions.

## Locations

Built-in skills can be packaged at:

```text
app/src/main/assets/skills/<skill-id>/SKILL.md
```

User-created skills are loaded from:

```text
/sdcard/Android/data/io.agents.pokeclaw/files/skills/<skill-id>/SKILL.md
/data/data/io.agents.pokeclaw/files/skills/<skill-id>/SKILL.md
/sdcard/PokeClaw/skills/<skill-id>/SKILL.md
```

The app creates the app-specific skill folders on startup when possible. The
legacy `/sdcard/PokeClaw/skills` path is supported for ADB/manual installs on
devices where scoped storage still permits it.

For compatibility with older PokeClaw experiments, single files named
`<skill-id>.skill.md` are also accepted, but new skills should use the folder
layout above.

## SKILL.md Format

```markdown
---
name: Send Message
description: Use when the user asks to send a text message to a contact in a messaging app.
allowed-tools: send_message, finish
---

# Send Message

Send exactly one message to a contact.

## Steps

1. Identify the contact, app, and message from the user's request.
2. If any required detail is unclear, ask the user.
3. Use `send_message` with the identified contact, app, and message.
4. Use `finish` to confirm what was sent.
```

## Frontmatter

Required:

- `description`: A clear trigger description. The runtime uses this to decide
  whether to include the skill for the current request.

Recommended:

- `name`: Human-readable skill name. If omitted, PokeClaw uses the folder name.
- `allowed-tools`: Comma-separated or YAML-list tool names the skill expects.

Supported compatibility fields:

- `id`: Optional explicit ID. If omitted, PokeClaw derives the ID from `name` or
  the folder name.
- `tools`: Legacy alias for `allowed-tools`.

## Runtime Behavior

On each user request, PokeClaw:

1. Loads all skills from assets and user skill directories.
2. Builds a compact list of available skill descriptions.
3. Selects an explicit `/skill-id` invocation or the best matching skill by the
   current request text.
4. Injects the selected skill's Markdown body into the model prompt.

Cloud task models, local task models, cloud chat, and local chat all receive the
same skill context. If no skill clearly matches, only the compact skill index is
included and the model should continue normally.

## Tool Names

Use PokeClaw tool identifiers in `allowed-tools`, for example:

```text
open_app, get_screen_info, tap, tap_node, input_text, system_key, swipe,
scroll_to_find, find_and_tap, send_message, auto_reply, get_notifications,
clipboard, get_device_info, get_installed_apps, take_screenshot, wait, finish
```

Unknown tools produce a warning in logs but do not stop the skill from loading.
This makes it possible to share Claude Code skills that mention non-PokeClaw
tools while still using their Markdown instructions.

## Authoring Guidance

- Keep the body short and operational: roughly 250-500 tokens.
- Put routing intent in `description`, not only in the title.
- Start with parameter extraction and ask for missing required details.
- Prefer PokeClaw's direct tools when available.
- Do not include secrets or private data in skill files.
