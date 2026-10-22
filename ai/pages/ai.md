# Build with AI

> Point Claude Code, Codex, Cursor, Copilot or any coding agent at Elci's AI resources, and let it write, check and test Elci contracts.

Source: https://elci-lang.dev/ai/

Elci is new, so AI models have seen little or none of it. Without context, an agent writes code that looks
like Elm but doesn't compile in Elci. Typical mistakes are `Float`, `Set`, `Debug.log`, `String.length`,
`Html` and helpers Elci doesn't have. The resources on this page give an agent the exact language, APIs and workflow in
one read. The `elci` CLI then lets it check its own work.

## The short version

Install Elci first ([Installation](https://elci-lang.dev/start/installation/)). Then start your agent with this prompt:

```text wrap
You are writing Cardano smart contracts in Elci, an Elm-syntax language that compiles to Plutus V3.
Before writing any code, read https://elci-lang.dev/ai/starter-pack.md and follow it strictly.
Use https://elci-lang.dev/llms.txt to find other pages when you need them.
After every change, run `elci check .` and `elci test .` and fix every ELCI error before continuing.
Only use functions and modules that the starter pack or https://elci-lang.dev/ai/catalog.json lists.
```

## Resources for agents

| Resource | What it is | Use it |
| --- | --- | --- |
| [Starter pack](https://elci-lang.dev/ai/starter-pack.md) | The language subset, encodings, handler signatures, APIs, error fixes and complete examples, written for agents | Read it first, every session |
| [Agent instructions](https://elci-lang.dev/ai/agent-instructions.md) | The must-follow rules in about a hundred lines | Save it as `AGENTS.md`, `CLAUDE.md` or a rules file |
| [llms.txt](https://elci-lang.dev/llms.txt) | An index of every page, following the [llms.txt](https://llmstxt.org) convention | To find the right page |
| [llms-full.txt](https://elci-lang.dev/llms-full.txt) | All documentation in one Markdown file | Attach it for long sessions |
| `/ai/pages/…` | Each page as plain Markdown, such as [transaction scenarios](https://elci-lang.dev/ai/pages/testing/scenarios.md) | To read one topic |
| [catalog.json](https://elci-lang.dev/ai/catalog.json) | Signatures of the bundled modules, compiler-provided modules and Java libraries | To check that a function exists |
| [diagnostics.json](https://elci-lang.dev/ai/diagnostics.json) | Every ELCI error code and its meaning, the error format and the `--report=json` fields | To explain a compiler error |
| [examples.json](https://elci-lang.dev/ai/examples.json) | The example projects, with every source file | To copy a working pattern |
| [manifest.json](https://elci-lang.dev/ai/manifest.json) | The Elci and Julc versions the docs describe, and checksums of all of the above | To match your installed version |

The pages and the catalog are generated from the same sources as this site. Every code sample in them was
compiled with the Elci version named in the manifest. Check that `elci --version` reports the same version.

## Set up your agent

Save the agent instructions where your tool reads them. They point the agent to the starter pack and the
rest of these resources.

**Claude Code**

Claude Code reads `CLAUDE.md` at the start of every session. An `@path` line in it imports another file, so
the whole starter pack is loaded too:

```sh
curl -o CLAUDE.md https://elci-lang.dev/ai/agent-instructions.md
curl --create-dirs -o docs/elci/starter-pack.md https://elci-lang.dev/ai/starter-pack.md
echo '@docs/elci/starter-pack.md' >> CLAUDE.md
```

**Codex and AGENTS.md**

Codex and other tools that follow the `AGENTS.md` convention read it from the project root:

```sh
curl -o AGENTS.md https://elci-lang.dev/ai/agent-instructions.md
```

**Cursor**

Cursor applies the rules in `.cursor/rules/`. This rule is active for Elci sources and `elci.toml`:

```sh
mkdir -p .cursor/rules
{ printf -- '---\ndescription: Elci smart contract rules\nglobs: ["**/*.elm", "elci.toml"]\nalwaysApply: true\n---\n\n'
  curl -s https://elci-lang.dev/ai/agent-instructions.md; } > .cursor/rules/elci.mdc
```

**GitHub Copilot**

Copilot reads repository instructions from `.github/copilot-instructions.md`:

```sh
mkdir -p .github
curl -o .github/copilot-instructions.md https://elci-lang.dev/ai/agent-instructions.md
```

**Chat on the web**

For ChatGPT, Claude or Gemini in the browser, paste [the prompt above](#the-short-version). For a longer
project, attach [llms-full.txt](https://elci-lang.dev/llms-full.txt) or the [starter pack](https://elci-lang.dev/ai/starter-pack.md) as a file.
A chat can't run the compiler, so paste `elci check` and `elci test` output back into the conversation.

## Let the agent check its own work

The CLI is the agent's feedback loop. Every command is non-interactive and exits with status 1 on failure.
Errors are printed as `path:line:column: ELCIxxxx: message`, followed by the source line and any hints
with the likely fix. A run reports every error it finds, one per top-level declaration, so the agent can fix
several at once. With `--report=json`, `check`, `build`, `eval` and `test` print each error as one JSON
object on its own line instead. `elci explain <code>` prints the long-form explanation of a code, with an
example and its fix.

| Command | What the agent uses it for |
| --- | --- |
| `elci check .` | Type-check and compile every validator, without writing files |
| `elci test .` | Run unit tests, transaction scenarios and property tests. `--match` selects tests, and `--seed` reproduces a property run |
| `elci eval .` | Evaluate a function project's value and print it with its cost; handy for checking an expression |
| `elci build .` | Write the deployable artifacts once everything passes |
| `elci explain <code>` | Explain an error code, such as `ELCI2002`, with an example and its fix |
| `elci --help` | Every command and option |

A loop that works well:

1. Write the types (datum, redeemer, parameters) and the handler signature, then run `elci check .`.
2. Write the logic, and run `elci check .` until it passes.
3. Add scenarios for every way the transaction should succeed or fail, plus a property, and run `elci test .`.
4. When a test fails, read the reason and the counterexample, then fix the code rather than the test.
5. Run `elci build .`.

The native `elci` binary starts in milliseconds, which suits agents that run the compiler many times.

## Prompts to try

```text wrap
Create an Elci validator in this project for a time-locked escrow: the datum holds the
beneficiary and a deadline; the beneficiary may claim at or after the deadline; the depositor
may reclaim before it. Add scenarios for every case and a property over the validity start.
Follow the starter pack, and run elci check and elci test until everything passes.
```

```text wrap
Explain this Elci error and fix the code, using https://elci-lang.dev/ai/diagnostics.json and
the starter pack: <paste the elci output>
```

```text wrap
Add property tests to tests/ for the validator in src/, covering every branch of the redeemer.
Run elci test --seed 1 and show me any counterexample you find before changing code.
```

## What to review yourself

Agents write plausible code quickly. On-chain mistakes are costly, so review what matters before
deploying:

- **Authorization:** every path that releases funds or mints checks the right signatures, inputs or
  validity range.
- **Types at the boundary:** the datum, redeemer and parameter types. `abi.json` records exactly how they are
  encoded.
- **What leaves the script:** outputs, amounts and minted tokens, not only the redeemer's claims.
- **Tests that prove something:** every `expectFailure` scenario has a matching success scenario, so a
  failure isn't caused by something unrelated.

Elci is a development preview. Tests are not a security audit.

There is no Elci MCP server yet. The CLI is the tool interface, and every agent that can run shell
commands can use it.

## Next steps

  - [AI starter pack](https://elci-lang.dev/ai/starter-pack/): What an agent needs to write correct Elci.
  - [Agent instructions](https://elci-lang.dev/ai/agent-instructions/): The compact rules file for your project.
  - [Your first validator](https://elci-lang.dev/start/first-validator/): The hands-on tutorial, for you or your agent.
  - [Transaction scenarios](https://elci-lang.dev/testing/scenarios/): How to test validators against full transactions.
