<p align="center"><img src="docs/bella-logo.png" width="96" alt="Bella"></p>

# Bella – AI assistant for ABAP in Eclipse

Bella brings Claude into the SAP ABAP Development Tools (ADT). You can use it with an API key, **with your Claude subscription**, **with your GitHub Copilot subscription**, or with another model through an OpenAI-compatible endpoint.

- Explain code: right-click → *What happens here?*
- Chat with the code in your editor as context
- Generate code, rework a selection or implement a method, written **into the editor only**
- Inline AI completion as ghost text

Bella reaches the SAP system through your existing ADT logon. ARC-1 or any other MCP server can be added. The user interface is available in 14 languages.

<p align="center"><img src="docs/screenshots/diff-preview.png" width="760" alt="Bella shows a diff preview before it writes generated code into the ABAP editor"></p>

## Features

| | Feature | Where |
|---|---|---|
| <img src="bundles/de.kiliantaubmann.bella.ui/icons/explain.png"> | **What happens here?** Explains the selection or, without a selection, the method at the cursor | Right-click in the editor → Bella |
| <img src="bundles/de.kiliantaubmann.bella.ui/icons/generate.png"> | **Generate code here…** Writes code at the cursor position, using the real definitions of the tables, classes and function modules involved | Right-click → Bella |
| <img src="bundles/de.kiliantaubmann.bella.ui/icons/rewrite.png"> | **Rework selection…** Fix errors, modern syntax, ABAP Cloud, performance, comments | Right-click → Bella |
| <img src="bundles/de.kiliantaubmann.bella.ui/icons/method.png"> | **Implement method** Writes the body of the METHOD/FORM/FUNCTION at the cursor | Right-click → Bella |
| <img src="bundles/de.kiliantaubmann.bella.ui/icons/insert.png"> | **AI completion** as grey ghost text; Tab accepts, Esc dismisses | `Ctrl+↑` (macOS: `Cmd+Option+Enter`), or automatically while typing (not with the Claude subscription or GitHub Copilot) |
| <img src="bundles/de.kiliantaubmann.bella.ui/icons/atc.png"> | **Check with ATC and fix…** Runs ATC on the object, lists the findings; Bella fixes the ticked ones in the editor (diff preview, not saved) | Right-click → Bella, toolbar |
| <img src="bundles/de.kiliantaubmann.bella.ui/icons/transport.png"> | **Review transport request…** Pick one of your requests (or enter a number); Bella reviews it in the chat: what it does, findings per object, security, syntax/ATC/ABAP Unit, completeness, verdict. Read only | Menu *Bella*, right-click → Bella |
| <img src="bundles/de.kiliantaubmann.bella.ui/icons/atc.png"> | **Review code** Checks the selection or the method at the cursor with abap_lint, syntax check and ATC and reports blocking points, performance and Clean ABAP issues, most important first (read only) | Right-click → Bella (chat) |
| <img src="bundles/de.kiliantaubmann.bella.ui/icons/refactor.png"> | **Suggest refactoring / unit test** | Right-click → Bella (chat) |
| <img src="bundles/de.kiliantaubmann.bella.ui/icons/bella.png"> | **Chat** with editor context, streaming and tool calls; insert code blocks, replace the selection or take them over as a method body | `Ctrl+Alt+B`, pink B in the toolbar, menu *Bella* |
| <img src="bundles/de.kiliantaubmann.bella.ui/icons/tool.png"> | **SAP tools**: search, read (also DDIC tables, structures, data elements, domains, table types, function modules, message classes), table contents, context of used objects, where-used, syntax check (also for unsaved code), ABAP Unit, ATC, style check, write, create, activate | automatically in the chat |
| <img src="bundles/de.kiliantaubmann.bella.ui/icons/bella.png"> | **Chat modes** from *Diff mode only* to *Automode*, and **Planning Mode** with accept, change or cancel (see below) | Drop-down and button below the chat input |

<p align="center"><img src="docs/screenshots/context-menu.png" width="560" alt="Right-click in the ABAP editor: submenu Bella with its actions"></p>

**Changing shortcuts:** *Preferences → General → Keys*, filter for "Bella". `Ctrl+↑` only applies in editors Bella is attached to and replaces "Scroll Line Up" there. Bella does not use `Ctrl+Alt+Space`, because the Claude desktop app takes it for its quick entry on Windows.

<table>
<tr>
<td width="50%"><img src="docs/screenshots/generate-dialog.png" alt="Dialog 'Generate code at the cursor' with suggestions"><br><sub><b>Generate code at the cursor.</b> Describe what you need or pick a suggestion.</sub></td>
<td width="50%"><img src="docs/screenshots/explain-findings.png" alt="Bella explains a report and lists side effects and suspicious points"><br><sub><b>What happens here?</b> The explanation also lists side effects and suspicious points, here a missing authority check.</sub></td>
</tr>
<tr>
<td colspan="2" align="center"><img src="docs/screenshots/atc-findings.png" width="560" alt="Dialog 'ATC check' with the findings of a report, sorted by priority, each with a tick box"><br><sub><b>Check with ATC and fix.</b> The findings, most important first; Bella fixes the ticked ones in the editor after the diff preview.</sub></td>
</tr>
</table>

### Chat modes

A drop-down below the chat input sets how freely Bella may act, from careful to free. It applies from the next tool call on, and every chat starts with *Ask before changes*.

| Mode | Reading | Table contents | Writing into an open editor | Saving, creating, activating in SAP |
|---|---|---|---|---|
| **Diff mode only (suggest)** | runs | asks | diff preview | refused |
| **Ask before changes** (default) | runs | asks | diff preview | asks |
| **Read data without asking** | runs | runs | diff preview | asks |
| **Write and activate** | runs | asks | diff preview | runs |
| **Automode** | runs | runs | diff preview | runs, plus ABAP Unit after each activation (`adt_activate` with `run_unit_tests`); Bella carries the task out and fixes errors, ATC priority 1 and 2 findings and failing tests |

- Choosing one of the two modes that write without asking asks once for confirmation.
- **Planning Mode** (button next to *Send*): Bella reads and analyses what you typed and answers with a numbered plan; nothing is changed, also not in the editor. Below the plan you then choose:
  - **Accept plan**: Bella carries the plan out in the mode chosen in the drop-down (e.g. asking before each change, or everything at once in *Automode*).
  - **Change plan**: you type what should be different; Bella answers with a revised plan and asks again.
  - **Cancel**: the plan is dropped and nothing happens.
- In every mode the allowed packages, your `DENY` rules and the ban on releasing transports stay, and objects open in an editor are only changed in the editor (you save and activate those).
- The modes work the same with an API key, the Claude subscription and GitHub Copilot.
- Use the modes that write without asking in development systems only.

### How Bella gets SAP context

A model that does not know your system guesses field names and signatures. Bella therefore reads them from the system through your ADT logon:

- **Editor actions** (*Generate code here…*, *Rework selection…*, *Implement method*) first collect the objects the code and your instruction mention: tables in `SELECT`, types after `TYPE`, classes before `=>` or after `NEW`, function modules in `CALL FUNCTION`, and names such as `MARA` in "select mara and show". Bella loads their definitions (at most 12 objects, 8 seconds), adds them to the request and lists them in the diff preview under *SAP definitions used*. You can switch this off under *Preferences → Bella → Editor*. It needs ADT and an ABAP editor of a logged-on system; the completion (`Ctrl+↑`) stays without it so that it stays fast.
- **In the chat** the model calls `adt_context` or `adt_read_source` itself. To save tokens, `adt_read_source` can return a single method (`method`) or only the matching lines (`grep`), and `adt_write_source` can replace a single method. Sources are cached and revalidated with SAP (ETag), and a note tells the model when an object has saved changes that are not activated yet. `adt_context` takes an object name, a piece of code or a list of names and returns in one call: the public section of classes, interfaces, table and structure fields, CDS views, function module signatures, data elements and table types.
- **Style check**: `abap_lint` checks code without SAP access for obsolete statements (`MOVE`, `CALL METHOD`, `CREATE OBJECT`, header lines, `FORM` …), `SELECT *`, `SELECT` in loops, `SELECT … ENDSELECT`, unchecked `SELECT SINGLE`, `CATCH cx_root`, empty `CATCH` blocks, break-points, aborting messages, texts the user sees as literals instead of text symbols, selection texts set in code (`%_p_name_%_app_%-text`), unused variables, unreachable code, repeated `ELSEIF` conditions, mismatched `BEGIN OF`/`END OF` names, deep nesting, long or complex methods, a missing `@` in strict Open SQL, long lines, trailing spaces, runs of blank lines and mixed keyword case (rules after ARC-1's abaplint presets). The diff preview shows its findings for generated code. It is a small rule set of Bella's own, not abaplint and no replacement for ATC.
- **Checks after writing, as with ARC-1**: `adt_write_source` and `adt_create_object` add the style check of the code written and a syntax check of the saved version to their result, so the model fixes them before activating. `adt_activate` with `run_atc` runs ATC on the activated objects in the same call, because ATC checks the active version; findings are listed by priority. *Write and activate* and *Automode* run ATC once when a change is complete (not on every activation), fix priority 1 and 2 and explain what they leave.
- **Quick fixes and pretty printer**: `adt_quickfix` lists SAP's own quick fixes (Ctrl+1) for the line of a syntax or ATC finding and previews one as a diff, ready for `adt_write_source`; `adt_format` formats code with the system's pretty printer settings; `adt_settings_write` changes those settings for everybody and always asks first.
- **Text elements**: `adt_text_elements` reads and `adt_write_text_elements` writes the text pool of a program, class or function group through ADT's textelements service, as ARC-1's `SAPWrite edit_text_symbols` does: selection texts of `PARAMETERS` and `SELECT-OPTIONS`, text symbols (`TEXT-001`) and list headings. They are active at once.
- **Creating objects**: `adt_create_object` creates classes, interfaces, programs, includes, function groups and function modules (with processing type normal, RFC or update), message classes with their messages, data elements, domains (with fixed values), table types, tables, structures, CDS views, access controls, metadata extensions, behavior definitions, service definitions and service bindings, as ARC-1's `SAPWrite create` does. `adt_write_metadata` changes data elements, domains, table types and the messages of a message class; it reads the object first and changes only the fields given.

Tables and structures are read as source on newer ABAP releases (7.52 and later). On older releases, and for data elements, domains, table types and message classes, Bella summarizes the object's ADT description.

- **Table contents**: `adt_table_contents` reads rows of a table or CDS view through ADT's data preview, like SE16 or the SQL console: `table` with optional `columns`, `where` and `max_rows` (default 100, at most 1000), or a complete ABAP SQL `SELECT` in `sql`. Only a single `SELECT` is sent, and SAP checks your authorization for the data. Because the rows go to the model provider, Bella asks before each read unless the chat mode is *Read data without asking* or *Automode*.
- **System knowledge**: `adt_list_systems` shows each system's SAP_BASIS release or whether it is an ABAP Cloud system, and the editor actions tell the model the release. `adt_transport_info` asks SAP which transport request a change needs, `adt_short_dumps` lists and reads runtime errors (ST22).
- **Transport review**: `adt_list_transports` lists requests, `adt_transport_review` collects a review dossier for one: per source the diff between the version in the request and the version before it (version choice as in ARC-1's transport diff), syntax check, ATC and ABAP Unit in one run each, objects not activated, and customer objects the changed code uses that are missing, inactive or held in another open request. The review answer starts with the verdict and at most five blocking and five should-fix points, then an overview table per object, then the details.

**Compared with ARC-1:** Bella's tools now cover what ARC-1's `SAPRead` (incl. single methods, grep and active/inactive versions), `SAPContext`, `SAPQuery` (table contents and SQL), read-only `SAPTransport` (incl. the transport diff) and the dumps of `SAPDiagnose` offer, the text elements of `SAPWrite`, plus a package allowlist for writes. With *Automode*, writing, creating, activating and testing run without confirmation as with ARC-1. ARC-1 is still worth adding for the real abaplint rule set (`SAPLint`), a central audit log and rate limits, Git (gCTS/abapGit), or when the tools should run on a server instead of in Eclipse.

### Ground rule: open objects are only changed in the editor

- **Code for an open method or class goes only into the Eclipse editor**, never directly into the SAP system. The editor holds the lock on the object.
  - A diff preview first shows old and new side by side (can be switched off).
  - Afterwards the editor is dirty: nothing is saved or activated. `Ctrl+Z` undoes the change.
  - You save and activate as usual with `Ctrl+S` and `Ctrl+F3`.
- If Claude tries to change an open object through a tool in the chat, Bella's **router** redirects the write into the editor.
- **Objects that are not open** may be written, created and activated in the SAP system, after you confirm.

## Architecture: who calls whom

<p align="center"><img src="docs/architecture.svg" alt="Architecture: developer, Eclipse with ADT editor and Bella, Claude, optional ARC-1, SAP system"></p>

1. and 2. Bella reads the source from the ADT editor and writes suggestions only into its buffer.
3. **Only Bella calls the model.** Prompt, code context and tool list go out; text and `tool_use` requests come back. With the Claude subscription or GitHub Copilot, the local Claude Code CLI or Copilot CLI makes this call for Bella.
4. Optionally Bella runs tool requests through ARC-1 or another MCP server.
5. Tools reach the SAP system in one of two ways:
   - **5a (default):** Bella's own `adt_*` tools use the ADT communication layer with the logon of your ABAP projects, including SSO. There is no second logon and no extra process.
   - **5b (optional):** ARC-1 talks to the SAP system and adds audit, rate limits and a package allowlist.
6. You save and activate open objects yourself in ADT.

**The model never talks to the SAP system directly.** Your system does not need to be reachable from the internet.

<p align="center"><img src="docs/sequence-tool-call.svg" alt="Sequence of a tool call"></p>

### Tool policy

| Tool | Default |
|---|---|
| Read and check (`adt_search_objects`, `adt_read_source`, `adt_context`, `adt_where_used`, `adt_syntax_check`, `adt_run_unit_tests`, `adt_atc_check`, `adt_text_elements`, `adt_transport_info`, `adt_short_dumps`, `adt_list_transports`, `adt_transport_review`, `abap_lint`; ARC-1: SAPRead, SAPSearch, …) | runs automatically |
| Table contents (`adt_table_contents`; ARC-1: SAPQuery) | asks first |
| Write, create, activate (`adt_write_source`, `adt_create_object`, `adt_write_metadata`, `adt_write_text_elements`, `adt_activate`; ARC-1: SAPWrite, SAPActivate, …) | asks first |
| Delete objects, create or change transport requests, Git pull/push, deploy UI5 apps, delete packages, start traces, change system settings (rules with `ASK`, also for ARC-1's matching actions) | asks in every chat mode, also in Automode |
| Release transports | always refused |

The chat mode can tighten or loosen this (see *Chat modes*); refusals stay refusals in every mode.

- Add your own rules under *Preferences → Bella → SAP-Tools & ARC-1*, one per line as `pattern=AUTO|CONFIRM|ASK|DENY`; `ASK` asks even in Automode, and `tool:action=…` matches only one action of a multi-purpose tool (e.g. `mcp_arc1_SAPTransport:create=ASK`).
- **Allowed packages** (same page, default `$TMP, Z*, Y*`): Bella's tools write, create and activate only there; other writes are refused before you are asked. Empty allows all packages. Writing into an open editor is not affected.
- With ARC-1, its server-side safety flags apply in addition.

<p align="center"><img src="docs/screenshots/preferences-sap-tools.png" width="760" alt="Preferences page SAP-Tools and ARC-1 with MCP servers and tool policy"></p>

## Installation

Requirements:
- Eclipse 2026-09 or newer with Java 21
- SAP ABAP Development Tools
- on Linux also WebKitGTK (`libwebkit2gtk-4.1`) for the chat

**From the update site (recommended):**

1. In Eclipse: *Help → Install New Software… → Add…*, name *Bella*, location:
   ```
   https://bella.kilian-taubmann.de/
   ```
2. Install both features:
   - **Bella**: chat, editor actions, completion, MCP/ARC-1.
   - **Bella ADT integration**: Bella's own SAP tools through the ADT logon. Requires ADT.
3. Restart Eclipse. New versions then arrive through *Help → Check for Updates*.

**From a ZIP (offline):** download `bella-update-site-vX.Y.Z.zip` from [Releases](https://github.com/ktaubmann/Bella/releases), do not unzip it, and choose *Add… → Archive…* instead of entering the address. *Check for Updates* does not find new versions of a local ZIP; install the next ZIP the same way.

Bella does not ship or download ADT. The update site contains only Bella's own bundles, and they accept any installed ADT version, so an existing ADT installation is left as it is. To be on the safe side, you can untick *Contact all update sites during install to find required software* in the install dialog; Eclipse then only looks at Bella's update site.

## Setup

*Window → Preferences → Bella*

<p align="center"><img src="docs/screenshots/preferences-provider.png" width="760" alt="Bella preferences with the provider drop-down (Claude API key, Claude subscription, GitHub Copilot, OpenAI-compatible) and the Claude subscription settings"></p>

- **Provider** (drop-down at the top): *Claude (API key)*, *Claude subscription (Claude Code CLI)*, *GitHub Copilot (Copilot CLI)* or *OpenAI-compatible*. The page only shows the fields of the selected provider, plus a note on code completion.
- **Claude (API key)**:
  - Enter an API key from [console.anthropic.com](https://console.anthropic.com/settings/keys). It is stored encrypted in Eclipse secure storage.
  - Default models: `claude-opus-5` for chat and code, `claude-haiku-4-5` for fast completion.
  - Effort and the server-side fallback model for declined requests can be set.
- **Claude subscription** and **GitHub Copilot**: see the next sections.
- **OpenAI-compatible** (optional): base URL, key and model, e.g. `http://localhost:11434/v1` for Ollama. The source code then stays in-house.
- **Languages**:
  - User interface: like Eclipse or fixed. Menus switch right away, no restart needed.
  - Answers: like the user interface, like the question, or fixed.
  - ABAP comments in generated code: separate setting, English by default.
- **Editor**: diff preview, automatic completion while typing and its delay.

### Claude subscription instead of an API key

With a Claude subscription (Pro, Max, Team or Enterprise) you do not need an API key. Bella then uses the locally installed [Claude Code CLI](https://claude.com/claude-code), which handles logon and billing through your subscription.

1. Install Claude Code and log in once in a terminal:
   ```bash
   claude auth login        # log in with your Claude account in the browser
   ```
   If Eclipse does not see this logon (e.g. a different user), `claude setup-token` creates a long-lived subscription token. Enter it in Bella as *Subscription token*; it is kept in secure storage.
2. Choose *Preferences → Bella → Provider: Claude subscription (Claude Code CLI)* and click **Check**. The status line shows e.g. "logged in (max)".
   - You only need to set the path to `claude` if Bella does not find it. Bella searches `PATH`, `~/.local/bin`, `/opt/homebrew/bin`, `/usr/local/bin` and on Windows `%USERPROFILE%\.local\bin` and `%APPDATA%\npm`.
     To find the path, run `(Get-Command claude).Source` in PowerShell or `which claude` on macOS/Linux, and paste the result (or use **Browse…**). If the command is not found, the CLI is not installed yet, or the terminal was opened before the installation: open a new one.
   - Models: alias or full ID, default `opus` for the chat and `haiku` for completion. Which models are available depends on your plan.

What happens:

- Bella starts `claude` headless (`-p`, `stream-json`) in its own working directory in the Eclipse workspace metadata. One CLI process runs per chat; *New chat* ends it.
- The CLI's built-in tools are switched off (`--tools ""`), so Claude can neither read files nor run commands.
- The CLI gets Bella's SAP tools through a **local MCP server** inside Bella. It listens on `127.0.0.1` only and requires a random token. Other MCP configurations of the user are ignored (`--strict-mcp-config`).
- Every tool call passes the same rules as with an API key: tool policy, confirmation dialog and the router for open objects (code only goes into the editor).
- Bella removes an `ANTHROPIC_API_KEY` from the CLI's environment so that the subscription is really used.

Limits:

- **Completion:** every suggestion starts the CLI and takes 2–5 seconds. With the subscription there are therefore no automatic suggestions while typing, only on `Ctrl+↑`.
- Your subscription's usage limits apply. When the limit is reached, Bella says so in the chat.
- *Stop* interrupts the running answer. If the CLI does not react within 3 seconds, Bella ends the process; the next message then starts without the earlier conversation, and Bella tells you.

### GitHub Copilot subscription

With a GitHub Copilot subscription, Bella uses the locally installed [GitHub Copilot CLI](https://github.com/features/copilot/cli). It handles logon and billing through your Copilot plan, and you can pick any model your plan offers (Claude, GPT and others).

1. Install the Copilot CLI and log in once in a terminal:
   ```powershell
   winget install GitHub.Copilot      # Windows; or: npm install -g @github/copilot, brew install copilot-cli
   copilot login                      # opens the browser
   ```
   Instead of `copilot login` you can create a fine-grained personal access token with the **Copilot Requests** permission and enter it in Bella as *Token*. Classic `ghp_` tokens are not supported.
2. Choose *Preferences → Bella → Provider: GitHub Copilot (Copilot CLI)* and click **Check**. The status line shows "logged in", the CLI version and the models your plan offers.
   - You only need to set the path to `copilot` if Bella does not find it. Bella searches `PATH` and on Windows also `%LOCALAPPDATA%\Microsoft\WinGet\Links` and `%APPDATA%\npm`.
     To find the path, run `(Get-Command copilot).Source` in PowerShell or `which copilot` on macOS/Linux, and paste the result (or use **Browse…**). Typical results: `C:\Users\<you>\AppData\Local\Microsoft\WinGet\Links\copilot.exe` (winget) or `C:\Users\<you>\AppData\Roaming\npm\copilot.cmd` (npm). If the command is not found, open a new terminal after the installation.
   - Models: leave empty for the Copilot default, or enter a model ID from the list shown by **Check**.

What happens:

- Bella starts `copilot --acp` (Agent Client Protocol) in its own empty working directory in the Eclipse workspace metadata. One CLI process runs per chat; *New chat* ends it.
- The CLI's own abilities are switched off: no shell commands (`--deny-tool shell`), no file changes (`--deny-tool write`), no web access (`--deny-tool url`), no GitHub MCP server and none of your own Copilot MCP servers.
- The CLI gets Bella's SAP tools through the same **local MCP server** as the Claude subscription (loopback only, random token). Tool policy, confirmation dialog and the router for open objects apply as usual. Bella rejects every other action the CLI asks permission for and says so in the chat.

Limits:

- **Premium requests:** every chat message, explanation or completion counts against your Copilot plan's premium requests, depending on the model.
- **Completion:** every suggestion starts the CLI and takes a few seconds, so there are no automatic suggestions while typing, only on `Ctrl+↑`.
- *Stop* cancels the running answer; if the CLI does not react within 3 seconds, Bella ends the process and the next message starts a new conversation.

### Project conventions

Under *Preferences → Bella → Project conventions* you describe your project for Bella, for all systems and per ABAP project (a system's settings are added to those for all systems):

- **Project information** (Markdown): package structure, architecture, dos and don'ts. Bella gives it to the model in the chat and in the editor actions.
- **Naming rules**, one per line as `kind = pattern, pattern` (`*` any characters, `?` one character), e.g.
  ```
  class = ZCL_SD_*
  local_data = lv_*, lt_*, ls_*, lo_*, lr_*
  importing = iv_*, it_*, is_*, io_*
  field_symbol = <ls_*>, <lv_*>
  test_class = ltc_*
  ```
  *Insert standard rules* fills in common SAP conventions. The model follows the rules, and the style check (`abap_lint`, diff preview) reports names that break them: data by where it is declared (local, global, attribute), constants, field symbols, types, methods and their parameters, local and test classes, and the names of programs, global classes and interfaces.
- **Derive from package…** reads the objects and a few sources of a package and lets the model propose both parts; review them before you apply.
- **ATC check variant** for *Check with ATC and fix…*, the transport review, *Review code* and `adt_atc_check`. A system's own variant wins over the one for all systems; left empty, Bella uses the system's default variant. Useful when the default variant runs remote checks through a central check system that cannot be reached (RFC destination errors).

The parameters of ATC check variants (e.g. a naming check) cannot be read through ADT, so they cannot be imported; findings of such a check are fixed with *Check with ATC and fix…*.

### Connecting ARC-1 (optional)

Under *Preferences → Bella → SAP-Tools & ARC-1 → Add…*:

- **HTTP**: URL of the ARC-1 server, e.g. locally `http://localhost:3000/mcp` or a BTP instance, optionally with a bearer token.
- **stdio**: Bella starts ARC-1 itself, e.g. with `npx -y arc-1@latest`. Configure the SAP connection as described in the [ARC-1 documentation](https://github.com/arc-mcp/arc-1).

**Test** lists the tools a server offers. When two tools offer the same capability (e.g. reading source code), Bella's ADT tools win by default. You can prefer ARC-1 instead, e.g. when governance should run centrally through ARC-1.

## Troubleshooting: log file

When something does not work, switch on the log under *Preferences → Bella → Log file* (it is off by default) and repeat what went wrong.

- **Normal** records what Bella does, with status, duration and errors:
  - model requests: provider, model, tokens, stop reason,
  - tool calls with the policy decision,
  - every ADT request with path and HTTP status, plus the SAP error text,
  - Claude Code and Copilot CLI processes with command line, exit code and stderr,
  - MCP connections.
- **Details** (a second checkbox) adds the content: prompts, answers, tool input and output, source code and every protocol line of the CLIs. Only switch it on while you reproduce a problem.
- API keys, tokens, bearer headers and passwords are removed from every entry. Environment variables are logged by name only.
- **Masking** (same page, on by default): confidential data in the log is replaced with placeholders, so you can attach the file to a bug report. The model still gets the real data.
  - Customer objects (Z*, Y*): `ZCL_ACME_ORDER` → `ZCL_MASK1`, `zcl_acme_order` → `zcl_mask1`.
  - System data: SID, ABAP project and logon user → `MASKSYS2`, `MASKUSER3`.
  - E-mail addresses and IBANs → `maskmail4@example.invalid`, `MASKIBAN5`.
  - Own terms (company, customer, project, namespace such as `/ACME/`), one per line → `MASKTERM6`, `/MASK7/`.
  - A value keeps its placeholder for the Eclipse session, so you can follow an object through the log. Names of people in free text, comments or string literals are only masked if you add them as terms.
- The file is `bella.log` in `<workspace>/.metadata/.plugins/de.kiliantaubmann.bella.ui/`. The preference page opens it, opens its folder or clears it; the chat view menu (▾) has *Open log*. At 5 MB it moves to `bella.log.1`.
- Please attach the file to a bug report. Errors also still go to Eclipse's *Error Log* view.

## Security and privacy

- The selected model provider receives your question, source excerpts from the editor and tool results. With Ollama everything stays local.
- API keys and tokens are kept in Eclipse secure storage, not in plain-text preferences.
- Bella never opens an SAP logon itself. Tools only use projects that are already logged on.
- Writing tools ask first, releasing transports is blocked, and open objects are only changed in the editor.
- The log file is off by default and never contains keys or tokens; with details on it contains source code.
- With a personal subscription (Claude Pro/Max, GitHub Copilot for individuals) the provider's data settings apply. Check whether your chats may be used for model training, and switch that off if your company's code must not be used for it. Anthropic and GitHub do not train on data from API keys or business plans by default.

## Development

```bash
mvn verify                    # core, UI, unit tests (without the SAP SDK)
xvfb-run -a mvn verify        # plus the workbench smoke test on Linux
mvn -Padt verify              # plus the ADT bundle and the update site
                              #   (downloads ADT from tools.hana.ondemand.com)
python3 releng/i18n/generate.py   # generate translations from releng/i18n/*.py
```

`-Padt` downloads ADT from SAP's update site; SAP's license terms for these downloads (SAP Developer License Agreement) apply to whoever runs the build. The ADT bundles are only used to compile against and are not part of Bella's update site.

**Release:** raise the version in all `pom.xml`, `MANIFEST.MF` and `feature.xml` files (e.g. with `mvn org.eclipse.tycho:tycho-versions-plugin:set-version -DnewVersion=0.3.0-SNAPSHOT`) and commit. Then push a tag `v0.3.0`, or enter the version `0.3.0` under *Actions → Release → Run workflow*. The `release.yml` workflow builds everything including the ADT integration, runs the tests and attaches `bella-update-site-v0.3.0.zip` to a GitHub release. When the repository variable `PAGES_ENABLED` is `true`, it then publishes the same update site on GitHub Pages (`pages.yml`, address from the variable `UPDATE_SITE_URL`); *Actions → Update site → Run workflow* publishes an existing release again.

| Module | Contents |
|---|---|
| `bundles/de.kiliantaubmann.bella.core` | no UI and no ADT: Anthropic and OpenAI-compatible providers (streaming, tool use, prompt caching), Claude Code integration (CLI, `stream-json`, local MCP server), GitHub Copilot integration (Copilot CLI, Agent Client Protocol), tool registry and policy, MCP client (HTTP and stdio), chat tool loop, ABAP scanner, reference finder and style check, prompts, ADT REST client, `adt_*` tools and the context builder |
| `bundles/de.kiliantaubmann.bella.ui` | chat view, context menu, diff preview, router, ghost text, preferences, icons, translations |
| `bundles/de.kiliantaubmann.bella.adt` | the only dependency on the ADT SDK: projects, logon and REST transport as an OSGi service |
| `tests/…core.tests` | unit tests for providers, MCP client and server, Claude Code and Copilot sessions (with simulated CLIs), tool loop, ABAP scanner, reference finder, style check, ADT client and context (with a simulated ADT backend), logging and redaction, translations |
| `tests/…ui.tests` | workbench smoke test: commands, chat, preferences (provider drop-down, subscription and Copilot hints), `Ctrl+↑` without key conflict, menus in Bella's language, writing into the editor without saving, style check in the diff preview, log file on/off, router, language switch |

The Claude integration deliberately uses `java.net.http` instead of the Anthropic Java SDK. This keeps the OSGi bundle free of OkHttp, Kotlin and Jackson.

### Status and known limits

- The **GitHub Copilot** integration is tested with a simulated CLI speaking the Agent Client Protocol; handshake, command-line options and the "not logged in" case were checked against the real Copilot CLI 1.0.89. A chat with the Copilot Free plan works.

- The **ADT bundle** compiles in CI (`-Padt`) against the current ADT SDK from SAP's p2 site. Generating, explaining and writing into the editor have been used with a real ABAP system; the SAP tools in the chat (search, where-used, ATC, activation …) still need broader testing.
  - Stateful sessions (for locks) and the object reference of an editor are read via reflection.
  - If an API is missing in your ADT version, Bella reports it in the chat. ARC-1 remains available as a route for the SAP tools.
  - Reading DDIC objects and loading definitions for editor actions was tested against a simulated ADT backend. The ADT endpoints for tables, structures and data elements differ between releases, so please report objects Bella cannot read.
- If ADT itself uses `Ctrl+↑` in the ABAP editor, *Preferences → General → Keys* shows a conflict. Change the shortcut there.
- Command names in *Keys* and *Quick Access* still follow the operating system language; Eclipse resolves them before Bella starts.
- All translations except German and English were machine-generated. Corrections are welcome.

## License, disclaimer and trademarks

[MIT](LICENSE) © 2026 Kilian Taubmann

Bella's Clean ABAP rules in the prompts are based on SAP's [Clean ABAP style guide](https://github.com/SAP/styleguides) (CC BY 3.0). The layout of the transport review report draws on ARC-1's [`sap-transport-review`](https://github.com/arc-mcp/arc-1/tree/main/skills/sap-transport-review) skill (MIT).

Bella is free software provided as is, without warranty. It can change ABAP code and, after you confirm, write and activate objects in SAP systems. Review what it generates, use it in development systems, and keep your usual transport and review process. You are responsible for the code you save and activate.

Bella is an independent project and is not affiliated with, endorsed or sponsored by SAP, Anthropic, GitHub, OpenAI or the Eclipse Foundation. SAP, ABAP and the other SAP products mentioned are trademarks or registered trademarks of SAP SE (or an SAP affiliate company) in Germany and other countries. Claude and Anthropic are trademarks of Anthropic, PBC. GitHub and GitHub Copilot are trademarks of GitHub, Inc. OpenAI is a trademark of OpenAI. Eclipse is a trademark of the Eclipse Foundation. All other names are trademarks of their respective owners and are used only to describe compatibility.
