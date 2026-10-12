---
status: accepted
---

# Agent output is summarised by a model on the hub

Agent output is markdown, diffs and code, and read aloud it is useless. The
hub therefore summarises an agent's state into one or two sentences before it
reaches the phone ("stsloop agent is blocked: asking whether to rebase or
merge"). This reverses Phase 0's "no LLM in v1" for one narrow job.

- **Backends:** `claude -p` by default, which uses the user's existing Claude
  Code login, with pi and Ollama as options.
- **Redaction:** obvious secrets are removed before the text reaches the model
  and again before it is spoken (rung 2 of the ladder).
- **Fallback:** when the summariser fails or times out, the phone gets the
  Provider's raw state and the last line.
- **Principle 3 holds:** the model chooses what to say. Focus, commands and
  send stay in the phone's deterministic grammar.

## Considered options

- **No model, structured state only.** Kept as the fallback, but "blocked"
  without what it is asking does not let you answer.
- **A model on the phone.** Larger APK and memory on top of a 757 MB
  recogniser, and no better on a phone than on the machine already running the
  agents.

## Consequences

- Summaries need the hub, so they degrade with it: offline, an agent Bucket
  answers "hub offline".
- Agent content leaves the machine when the backend is a hosted model. The
  default backend is the one the agent itself already uses.
