# Bella – notes for Claude

Eclipse plug-in (Tycho build) that brings Claude into SAP ABAP Development Tools. README.md describes the features.

## Layout

- `bundles/de.kiliantaubmann.bella.core` – everything without Eclipse UI: providers (`llm`, `claudecode`, `copilot`), tool loop (`agent`), ADT client and tools (`adt`), MCP client/server, style check (`lint`), prompts.
- `bundles/de.kiliantaubmann.bella.ui` – Eclipse UI: chat view (`views/ChatView.java` + `html/chat.html`), editor actions, preferences, `messages*.properties`.
- `bundles/de.kiliantaubmann.bella.adt` – binding to the SAP ADT SDK; only built with `-Padt`.
- `tests/…core.tests` (JUnit 5, simulated backends and CLIs), `tests/…ui.tests` (workbench smoke test, needs a display).

## Build and test

- `mvn -B -ntp verify` (`xvfb-run -a mvn …` for the UI tests). In the cloud container `./mvnw` cannot download Maven; use the installed `mvn`.
- Partial builds with `-pl … -am` do not resolve in Tycho; build the whole reactor (`-DskipTests` for a quick compile).
- `-Padt` needs `tools.hana.ondemand.com`, which the cloud container cannot reach; the ADT integration is built in CI only.

## Conventions

- UI texts: edit `releng/i18n/<lang>.py` (all 14 languages, no ASCII apostrophes, same `{n}` placeholders), then run `python3 releng/i18n/generate.py`. Never edit `messages*.properties` or `plugin*.properties` by hand; CI fails if they are not regenerated.
- New tools: a `ToolProvider` in core, registered in `BellaPlugin`; `ToolSpec.Kind.READ` runs without confirmation and also in plan mode.
- Icons: SVG source in `bundles/de.kiliantaubmann.bella.ui/icons/src/`, PNG at 16 px and `@2x` at 32 px, registered in `BellaPlugin.IMAGES`.
- Document user-visible changes in README.md.
- Version bump ("Version x.y.z" commit): the same 17 files every time – root `pom.xml`, the `pom.xml` and `MANIFEST.MF`/`feature.xml` of every bundle, feature and test bundle, the update site `pom.xml`, and `BellaPlugin.VERSION`.

## Branches and releases

Work happens on the `claude/…` branch. A change goes out in this order:

1. Push the work branch, then push the same commit to `test` (`git push origin HEAD:test`). `test-build.yml` builds, tests and replaces the pre-release `test-build` (`bella-update-site-test.zip`).
2. Wait until that test build is green; it must be for the exact commit.
3. Only then push the commit to `main` (`git push origin HEAD:main`). `release.yml` runs on every push to `main`: if the version in `pom.xml` has no tag yet, it checks that the commit passed the test build, then builds, tags `v<version>`, publishes the GitHub release and the update site on GitHub Pages. A push to `main` without a new version releases nothing.

Pushing tags is blocked from the cloud container; the release workflow creates the tag itself. A manual release ("Run workflow" on `release.yml`) only runs on `main`.
