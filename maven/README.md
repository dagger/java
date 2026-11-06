# Maven

A [Dagger](https://dagger.io) module written in Dang for compiling, testing,
packaging, and publishing Maven projects. Requires Dagger v1.0.0-beta.22 or later,
for selected collection arguments on checks.

## Install and verify

```sh
dagger install github.com/dagger/java/maven
dagger check --module maven
```

For local development, install this directory's path instead of the GitHub URL.

`dagger check` runs one check per discovered Java unit-test class, plus a
`verify` check per reactor (see [Test class checks](#test-class-checks)). One
`mvn verify` per reactor serves all of them: unit tests, packaging, and any
integration tests and quality gates configured in the POM, through the complete
[Maven lifecycle](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html)
including `post-integration-test` cleanup. The module does not add test or lint
plugins to your POM. Each failure is reported by exactly one kind of check: a
class check for its class and module, `verify` for the rest of the reactor.

Use `dagger check` for CI verdicts. Calling a check through the API returns a
`Check`; inspect its `pass` field to run it and obtain the verdict.

## Projects and reactors

`projects` is a collection keyed by workspace-relative root directory, with `.`
representing the workspace root. Automatic discovery selects outermost
directories containing `pom.xml`, excluding `target`, `.m2`, `.git`, and
`node_modules`. Maven itself selects and orders reactor members, including
profile-dependent modules; the module does not attempt to interpret POM XML.

From a reactor's subdirectory, discovery still selects its root. From elsewhere,
it selects reactors below the working directory. Members are not checked twice.
For a nested **independent** project, or aggregators referencing sibling projects,
set `roots` explicitly to the reactor roots you want built:

```toml
[modules.maven.settings]
roots = ["services/api", "tools/migration"]
```

`project(path)` also addresses any specific POM directory directly. All project
paths are relative to the workspace root, regardless of the current directory.
Paths must not contain `..`.

For a project at the workspace root:

```sh
dagger -c 'maven | project --path=. | compile | sync'
dagger -c 'maven | project --path=. | test | stdout'
dagger -c 'maven | project --path=. | build | export ./dist'
dagger -c 'maven | project --path=. | reports | export ./reports'
```

For a nested reactor:

```sh
dagger -c 'maven | projects | get services/api | build | export ./dist'
dagger -c 'maven | project --path=services/api | run --goals=dependency:tree | stdout'
```

## Test class checks

Each project has three collection levels, each adding a `dagger check` flag:

| Collection | Keys | Flag |
| --- | --- | --- |
| `projects` | Reactor root, relative to the workspace | `--maven-project` |
| `modules(ws)` on a project | POM directory, relative to the workspace | `--maven-module` |
| `tests` on a module | Fully qualified class name | `--maven-test` |

The reactor owns the container, cache, toolchain, and Maven command, so it is
the unit that runs. Modules are the directories Maven builds within it. A test
class is the finest unit Surefire both filters and reports individually, in its
XML reports; individual methods and runtime parameter values are not separate
collection items. Listing runs no Maven command.

```sh
dagger list maven-tests -a
dagger check --maven-module=app                                  # every class in one module
dagger check --maven-test=example.AppTest                        # that class in every module
dagger check --maven-module=app --maven-test=example.AppTest     # one class
dagger check --maven-project=services/api                        # one reactor, including verify
```

A class name in two modules selects both; `--maven-module` narrows that.
`--maven-module` and `--maven-test` leave out the reactor's verify check, which
does not belong to those collections.

Every class check in a reactor receives the engine's filtered module
collection and calls one shared helper, which runs one Maven command for the
selection and indexes its reports. The engine deduplicates identical container
operations, so each reactor runs Maven once however many classes are selected.
Separate reactors run separately, in parallel.

- With nothing selected out, the command is
  `mvn verify --fail-at-end -Dmaven.test.failure.ignore=true`, the same command
  the reactor's verify check runs.
- Otherwise it is `mvn test --fail-at-end` with a Surefire filter file in every
  module that selects exactly its chosen classes. Dependencies build in reactor
  order; unselected tests still compile but do not run, and integration tests
  do not run. Selections override `-Dtest` and `surefire.includesFile` from
  `args`.

Test failures never stop the shared command, and `--fail-at-end` keeps a
failed module from stopping independent ones, so every selected class gets its
own result. A small indexer in a pinned JDK image reads the Surefire and
Failsafe XML reports and Maven's per-project failures (`Failed to execute goal
... on project <artifactId>`, mapped to POM directories) once, and writes one
bounded result per check: the last 32 KiB of each diagnostic stream, at most
96 KiB in all. Each check reads only its own. Blame goes to the finest unit:

- A failed or errored test case fails its class's check, including nested and
  parameterized cases. Other classes still pass.
- A module whose goal failed (compilation, dependency resolution, plugin,
  forked VM) fails that module's class checks. Classes in modules Maven then
  skipped did not run, and fail saying which module stopped them. Other
  modules' classes pass or fail on their own results.
- A failed test case in a class without a check (an integration test or an
  undiscovered class) fails the class checks of its module.
- Everything else belongs to the reactor: failures in modules without
  selected class checks, failures Maven attributes to no module directory, a
  non-zero exit without a project failure, and failures Maven logs that no
  standard report records. In a whole run the reactor's `verify` check reports
  them, and only it. In a narrowed run, which has no verify check, every
  selected class check sharing the command fails with them.
- A selected class with no test case in the reports fails as "did not run",
  rather than passing: check active profiles, source layout, skip flags such
  as `-DskipTests`, and Surefire's XML report configuration.

So a failing test fails its class check and not `verify`, and a failed
packaging step in a module without tests fails `verify` and not the class
checks. Selecting every key explicitly, or only checks named `test`, is a whole
run without the verify check, so reactor-level failures are not reported by
that invocation. Logs and spans belong to whichever check started the shared
command; the others share its result.

Discovery defaults to `src/test/java` under each POM and Surefire's conventional
`Test*.java`, `*Test.java`, `*Tests.java`, and `*TestCase.java` names. Class names
come from source paths, so package declarations must match directory layout.
Configure custom source directories and filename patterns explicitly:

```toml
[modules.maven.settings]
testSourceDirectory = "src/spec/java"
testIncludes = ["**/*Spec.java"]
testExcludes = ["**/AbstractSpec.java"]
```

These settings only control discovery; configure the corresponding source
directory in your POM too. This version does not interpret effective POMs,
active profiles, annotations, generated tests, or other JVM source languages.
A candidate can therefore be a helper or belong to an inactive module; its
check fails as not run. Discovery filters never hide a failure: every test Maven
runs is attributed to a class check, its module's checks, or verify.

Listing (`dagger check -l`, `dagger list`) reads workspace files only: it runs
no container and no Maven command, so broken POMs and unresolvable
dependencies still list. A reactor root without a `pom.xml` lists no modules;
its verify check fails alone.

Class checks require modern Surefire (tested with 3.5.4), standard XML reports
under `target/surefire-reports`, and class-based test execution. JUnit 4 and
JUnit 5 are covered by the integration suite. Custom TestNG suite files,
hard-coded plugin `<test>` selectors, and custom report layouts are outside
this support. Use `project.test(..., args: [...])` or `run` for those
workflows, method selectors, and Failsafe integration-test selection.

From another Dang module, the collection helpers `MavenModules.test`,
`MavenTests.test`, and `MavenProjects.verify` are plain functions, not checks,
so `dagger check` never reports the same failure twice. They run through the
same shared command and fail with every failure of their selection, whoever
owns it. There is no `.batch` namespace. An empty subset does not run Maven.

Earlier versions used a flat `tests` collection keyed `app/example.AppTest`,
with `batch.test` returning a container, and a `verify` that reported every
failure. Callers now write:

| Before | Now |
| --- | --- |
| `project.tests(ws)` | `project.modules(ws).get(key: "app").tests` |
| `tests.get(key: "app/example.AppTest").test(ws)` | `tests.subset(keys: ["example.AppTest"]).test(ws)` |
| `classes.subset(keys: [...]).batch.test(ws)` | `tests.subset(keys: [...]).test(ws)`, per module |
| `projects.batch.verify(ws)` | `projects.verify(ws)`, raising on failure |
| `project.verify(ws).pass` for the complete verdict | `maven.projects(ws).subset(keys: [path]).verify(ws)` |

Selection helpers return nothing; use `reports` or `run` for report files.

```dang
let app = maven.project(".").modules(ws).get(key: "app")
app.tests.subset(keys: ["example.AppTest", "example.SecondTest"]).test(ws)
```

## Operations

| Function | Maven phase or goal | Result |
| --- | --- | --- |
| `compile` | `compile` | Container with compiled production code |
| `test` | `test` | Container with unit test results |
| `build` | `package` | All `target` trees, with module-relative paths |
| `verify` | `verify` | Dagger check for failures no class check owns |
| `reports` | `verify`, ignoring test failures | Surefire and Failsafe report trees |
| `install` | `install` | Container with artifacts installed in its local repository |
| `deploy` | `deploy` | Output from publishing to the configured repository |
| `run` | Caller-supplied phases/goals | Container for custom workflows |
| `container` | None | Prepared container for customization or debugging |
| `modules` | None | Module collection, each with its test class checks |

`build` runs unit tests by default. For a reactor with `library` and `app`, its
output includes `library/target/…` and `app/target/…`. It preserves JARs, WARs,
EARs, attached artifacts, classes, and other target contents. Custom output
directories are available through `run(...).directory(...)`.

`reports` returns XML and text reports even when tests fail, using
`-Dmaven.test.failure.ignore=true`. Compilation and other build errors still
fail. Report export is separate from the CI verdict: always run `dagger check` too.
Custom report directories are available through `run`.

`run` supports `clean`, `dependency:tree`, `dependency:go-offline`, `site`,
plugin goals, and other Maven commands without adding a helper for each one.
Project-level execution methods other than `verify` accept additional `args`.
Configure module-level `args` to affect checks and class runs too.

## Toolchain and settings

The default image is `maven:3.9.16-eclipse-temurin-21`. If the project root
contains `mvnw`, commands use `sh ./mvnw`, preserving the version and checksum
policy in `.mvn/wrapper/maven-wrapper.properties`. A wrapper failure is reported;
it does not silently fall back to another Maven version. Set `useWrapper=false`
to force the image's Maven.

Commit your wrapper and its distribution checksum for reproducible tool
selection. See the [Apache Maven Wrapper documentation](https://maven.apache.org/tools/wrapper/).
The wrapper selects Maven; `javaVersion` selects the JDK. Set the Java language
target in your POM, normally with `maven.compiler.release`.

```toml
[modules.maven.settings]
javaVersion = "21"
mavenVersion = "3.9.16"
useWrapper = true
args = ["-Pci", "-Dstyle.color=never"]
```

| Setting | Default | Purpose |
| --- | --- | --- |
| `javaVersion` | `"21"` | Temurin JDK tag suffix |
| `mavenVersion` | `"3.9.16"` | Image Maven version; wrapper takes precedence |
| `base` | None | Custom container with toolchain, environment, secrets, services, etc. |
| `useWrapper` | `true` | Prefer the project's wrapper |
| `args` | `[]` | Extra arguments for every Maven invocation |
| `roots` | `[]` | Explicit reactor roots; empty means automatic discovery |
| `settings` | None | Dagger `Secret` containing settings.xml |
| `toolchains` | None | Dagger `File` containing toolchains.xml |
| `cacheKey` | Workspace/project specific | Override the dependency-cache namespace |
| `testSourceDirectory` | `"src/test/java"` | Source directory per POM for class discovery |
| `testIncludes` | Surefire's conventional Java filenames | Discovery globs relative to the test source directory |
| `testExcludes` | `[]` | Exclusion globs for class discovery |

A custom `base` supplies its own JDK and Maven, so version settings are ignored.
It must provide `sh`, and either `mvn` or the prerequisites for your wrapper
(typically a JDK, curl/wget, and unzip). Multi-JDK builds can supply a custom
base and `toolchains`; paths in toolchains.xml refer to JDKs inside that image.

For reactor selection, pass Maven's own flags, such as `args = ["-pl", "app",
"-am"]`. The whole workspace stays available, preserving sibling modules,
relative parent POMs, resources, `.mvn/maven.config`, and `.mvn/jvm.config`.
Host `target` trees and local dependency caches are excluded, and `.gitignore`
is respected. Builds requiring Git metadata can supply it in a custom base.

## Caching and publishing

The local repository is `/maven/repository`; wrapper distributions also live
under `/maven`. A locked cache volume prevents concurrent Maven processes from
writing the same repository. By default each workspace and reactor has its own
cache; use `cacheKey` to choose a stable namespace for synthetic Directory
workspaces or intentionally share dependencies. Build outputs remain in the
container snapshot, outside the dependency cache.

Dependencies resolve during normal Maven execution. There is no mandatory
`dependency:go-offline` stage: reactor artifacts and plugins that need source
can make a POM-only prefetch incorrect. Maven commands use batch mode and suppress
transfer progress. Unchanged builds reuse Dagger's execution cache. To refresh
snapshots, use `run` with `fresh: true` and `args: ["-U"]`.

`install` and `deploy` request fresh execution. Deployment is explicit and never
runs as a check. Configure destinations in the POM's `distributionManagement`,
or pass `-DaltDeploymentRepository=id::url` with a compatible deploy plugin.
Credentials belong in `settings`, mounted as a secret outside the cache and
source tree. Server IDs must match the repository IDs in the POM. Signing and
staging follow your project's Maven plugins; supply additional secrets or tools
through `base` when needed.

From another Dang module with this module installed as a dependency:

```dang
publish(ws: Workspace!, settings: Secret!): String! {
  maven(settings: settings).project("services/api").deploy(ws)
}

# Verify every reactor and fail with every failing project, tests included.
check(ws: Workspace!): Void {
  maven.projects(ws).verify(ws)
}
```

Arguments are passed as an argument vector, without shell interpolation. Keep
credentials out of `args` and committed settings files.

## Development

From the repository root (the parent of this directory):

```sh
dang fmt -w maven/main.dang .dagger/modules/e2e/main.dang
dagger check
```

The suite covers discovery and cwd selection, source filtering, configuration,
wrapper selection and actual wrapper execution, single-module and reactor
builds, failed tests and report export, artifact installation, and deployment to
a disposable `file://` repository. It never publishes to a remote repository.
Class-collection checks cover discovery, custom source paths, selections across
modules, duplicate class names, empty subsets, failure propagation, and JUnit 5
parameterized and nested tests.

The `selected-*` checks run `dagger check` through the engine-provided,
version-matched CLI in an isolated session against generated fixtures, because
direct Dang calls do not apply the engine's filtered collection view. They
cover two reactors with two modules each, excluded classes not running, an
isolated class failure, a compilation failure blamed on its module, failures
outside any selected class (narrowed, and owned by verify in a whole run), a
selected class that never ran, the whole-reactor run, one Maven command per
reactor, and listing that runs no container:

```sh
dagger check 'selected-*'
```
The shared workspace configuration, test module, and `dagger.lock` live at the
repository root. Commit that lockfile to retain the SDK and image resolutions
used by the suite.
