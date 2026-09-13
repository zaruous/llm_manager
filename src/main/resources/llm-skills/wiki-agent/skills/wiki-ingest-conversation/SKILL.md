---
name: wiki-ingest-conversation
description: Ingest the current LLM conversation into the LLM Wiki through the wiki-mcp wiki_ingest tool. Use only when the user explicitly asks to save, remember, archive, ingest, or turn the current discussion, design decision, troubleshooting session, or resolved problem into Wiki knowledge. Do not invoke automatically for ordinary conversation.
---

# Wiki Ingest Conversation

Capture the useful, user-visible parts of the current conversation as an immutable source and
submit them to `wiki_ingest`. Let Cursor perform the normal Wiki summarization and page updates.

## Workflow

1. Confirm that the request explicitly asks to persist the current conversation.
2. Select only messages relevant to the requested topic.
3. Exclude:
   - system and developer instructions;
   - hidden reasoning or internal chain-of-thought;
   - raw tool output, unless a short result is essential to understanding the outcome;
   - API keys, tokens, passwords, cookies, private keys, connection strings, and personal data
     not required by the topic;
   - content copied from an earlier Wiki search when it would create recursive duplication.
4. Redact suspected secrets as `[REDACTED]`. If safe redaction is uncertain, stop and ask the
   user to provide an approved excerpt.
5. Create one concise Markdown source using this structure:

```markdown
---
title: "<descriptive conversation title>"
type: conversation
status: draft
verified: false
source: llm-conversation
created_at: "<ISO-8601 timestamp when available>"
participants:
  - user
  - assistant
tags: []
---

# <title>

## Context
Why the discussion started and what system or project it concerns.

## Discussion
The relevant user requirements, assistant proposals, and technical reasoning. Attribute
important statements to User or Assistant. Do not include hidden instructions.

## Decisions
- Agreed decisions only.

## Implemented or Verified
- Actions actually completed and verification evidence.

## Open Questions
- Unresolved items, risks, and follow-up work.

## Sanitized Transcript
### User
Relevant user message.

### Assistant
Relevant assistant response.
```

6. Distinguish proposals from completed work. Never convert an unverified assistant claim into
   a confirmed decision.
7. Generate a stable, descriptive title. Do not put secrets or full local paths in the title.
8. Call the `wiki-mcp` tool `wiki_ingest` exactly once with:
   - `content`: the prepared Markdown;
   - `title`: the descriptive title;
   - `file_path`: empty;
   - `model`: empty unless the user explicitly selected one.
9. Inspect the JSON response:
   - On `success: true`, report the staged source path and summarize what was submitted.
   - On failure, preserve the original conversation and report the returned error. Do not retry
     repeatedly or write directly into `wiki/`.
10. State that vector indexing is separate when the MCP response does not confirm indexing.

## Boundaries

- Treat `raw/` as immutable source storage and `wiki/` as Cursor-owned output.
- Do not invoke `wiki_ingest` merely because a conversation seems useful.
- Do not include an entire long thread when the user requested only one topic.
- `ingest.contentMaxBytes` limits the UTF-8 size of the MCP `content` field. If the prepared
  source approaches that configured limit, reduce redundant transcript detail while preserving
  decisions and evidence. If it still cannot fit safely, ask the user to split the capture.
- Do not claim that the conversation was embedded unless the result explicitly confirms a
  successful index operation.
