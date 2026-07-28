# Java Sidecar: Analysis Method

This document provides an honest, detailed assessment of how
bisbom-gen generates SPDX SBOMs for Java in sidecar mode — what
it does, what it doesn't do, how it compares to standalone mode, and
why the current approach is the best option for a true sidecar
deployment.

## What the Java Sidecar Actually Does

The Java sidecar performs **post-build provenance analysis** — not
build interception. It analyzes build artifacts after the build
completes, with zero modifications to the build process.

### Standalone Mode — Kernel-Level Build Interception

```
bomtrace3 mvn package
  → strace -f -e trace=openat
  → kernel captures every file I/O syscall
```

- Kernel-level syscall interception via ptrace
- Observes `javac` reading `App.java` → writing `App.class`
- Observes `jar` reading `App.class` → writing `app.jar`
- Captures annotation processor I/O, resource copies, everything
- **This IS build interception** — the build process is directly
  observed at the OS level

### Sidecar Mode — Post-Build Provenance Analysis

```
mvn package                           # build runs unmodified
bomsh_create_bom_java.py              # reads .class bytecode SourceFile attribute
mvn dependency:tree -DoutputType=dot  # queries the build tool's dependency resolver
```

- No strace, no ptrace, no kernel interception, no compiler wrapping
- `bomsh_create_bom_java.py` runs **after** the build completes
- Reads the `SourceFile` bytecode attribute from compiled `.class`
  files (JVM Spec §4.7.10, inserted by `javac` per JLS §13.1)
- Uses path similarity heuristics to map `.class` → `.java` source
- Queries the build tool's dependency resolver for the declared
  dependency graph
- **This is NOT build interception — it is post-build analysis**

## Three Levels of Build Observation

| Level | Mechanism | What It Sees | Completeness |
|-------|-----------|-------------|--------------|
| **Kernel-level** | ptrace/strace | Every `openat()`, `read()`, `write()` by all processes | Complete |
| **Build-system-level** | Compiler wrappers (`CC=`, `-toolexec`, `RUSTC_WRAPPER`) | Compiler invocations routed through the hook | Partial |
| **Post-build** | Artifact analysis | Metadata embedded in compiled files | Inferred |

Standalone mode operates at the kernel level. Sidecar mode for C/C++,
Go, and Rust targets the build-system level (with caveats — see
below). Sidecar mode for Java operates at the post-build level.

### Build-system-level limitations

The `CC=` / `-toolexec` / `RUSTC_WRAPPER` approach assumes the build
system respects these hooks. Many enterprise build environments do not:

- **Hermetic build systems** (Bazel, Nix, Yocto) ignore `CC=` env
  vars and use their own toolchain resolution
- **Hardcoded compiler paths** in Makefiles (`gcc` instead of `$(CC)`)
  bypass `CC=` entirely
- **CMake cached builds** ignore `CC=` when the cache is already
  populated
- **Cross-compilation toolchains** set their own CC/CXX, overriding
  wrappers
- **Security-hardened CI** may restrict env var injection or PATH
  manipulation

In these environments, wrapper-based interception fails silently —
the build succeeds but no interception data is captured.

### Java has no compiler-wrapping mechanism

The JVM ecosystem does not provide a `JAVAC_WRAPPER` hook or any
equivalent of `CC=`. There is no standard, build-tool-agnostic way
to wrap `javac` invocations across Maven, Gradle, Ant, and Bazel.
This is why the Java sidecar uses post-build analysis instead.

## How the Industry Does Java SBOMs

Before evaluating alternatives, it's important to understand what the
industry actually uses for Java SBOM generation in enterprise CI/CD.
Every major tool falls into one of two categories:

### Category 1: Build plugins (require build modification)

| Tool | Method | Requires |
|------|--------|----------|
| **CycloneDX Maven Plugin** | Maven plugin generating CycloneDX SBOM from dependency resolution | `pom.xml` change |
| **CycloneDX Gradle Plugin** | Gradle plugin, same approach | `build.gradle` change |
| **SPDX Maven Plugin** | Maven plugin generating SPDX from dependency resolution | `pom.xml` change |

These produce accurate dependency graphs because they use the build
tool's own resolver. But they require modifying the project's build
configuration — something enterprises resist and that violates the
sidecar model.

### Category 2: Post-build artifact scanners (no build modification)

| Tool | Method | Artifact Access |
|------|--------|----------------|
| **Syft** (Anchore) | JAR manifest, `pom.properties`, `pom.xml` inside JARs | Same as sidecar |
| **Trivy** (Aqua) | Dependency file scanning (`pom.xml`, `build.gradle`) | Same as sidecar |
| **Black Duck** (Synopsys) | Binary signature matching against proprietary KB | Same as sidecar |
| **Snyk** | Dependency file analysis | Same as sidecar |
| **OWASP Dependency-Check** | Known-vulnerability matching from dependency metadata | Same as sidecar |

These require zero build changes and analyze the same post-build
artifacts that the bisbom-gen sidecar analyzes.

### Where bisbom-gen sidecar fits

The bisbom-gen Java sidecar is a **Category 2 tool** — a
post-build artifact scanner. It accesses the same artifacts as Syft,
Trivy, and Black Duck. What differentiates it:

| Aspect | Typical SCA (Black Duck, Syft) | bisbom-gen sidecar |
|--------|-------------------------------|--------------------------|
| **Source → binary mapping** | No | Yes — `SourceFile` bytecode attribute (compiler-inserted) |
| **Dependency resolution** | Inferred from signatures or manifest files | Exact — from the build tool's own resolver |
| **Scope awareness** | No — flat component list | Yes — compile, test, runtime, provided |
| **Direct vs transitive** | No — cannot distinguish | Yes — full hierarchy |
| **Unknown components** | Only finds what's in its database | Finds everything the build tool resolved |
| **Output** | Proprietary or CycloneDX | SPDX 2.3 (open standard) |

The advantages are real. The `SourceFile` attribute is
**compiler-inserted provenance metadata** — it comes from `javac`
itself, not from a signature database. And build-tool dependency
resolution gives the exact resolved graph including version conflict
resolution, not guessed matches.

## Why the Current Approach Is the Best Sidecar Option

The fundamental constraint of sidecar mode is: **zero modifications
to the build**. No `pom.xml` changes, no `MAVEN_OPTS`, no
`extensions.xml`, no `build.gradle` changes. The sidecar runs after
the build on the same artifacts.

Five alternative approaches were evaluated. **Every one violates the
sidecar constraint**:

### Alternative 1: Java Agent (`-javaagent:`)

Instruments JVM file I/O via `java.lang.instrument` (the API used
by JaCoCo, OpenTelemetry, and ByteBuddy).

- **Requires**: Setting `MAVEN_OPTS=-javaagent:bisbom-agent.jar`
  (build modification)
- **Maven fork problem**: `maven-compiler-plugin` defaults to
  `fork=false`, meaning `javac` runs in-process inside the Maven
  JVM. An agent in `MAVEN_OPTS` would see compiler I/O — but also
  ALL of Maven's own I/O (every `pom.xml` read, every plugin, every
  `settings.xml` access). When `fork=true`, the agent does NOT
  instrument the forked `javac` process at all — you'd need to
  inject `-J-javaagent:...` via `<compilerArgs>` in `pom.xml`.
- **JEP 451** (Java 21+): Warns about restricting dynamic agent
  loading. Future JDK versions may further limit agent use.
- **Agent compatibility**: Must coexist with whatever agents teams
  already use (JaCoCo, OpenTelemetry, APM agents).
- **Verdict**: Higher fidelity than SourceFile, but NOT a sidecar
  solution — requires build environment changes.

### Alternative 2: Maven Compiler Plugin Wrapper

Custom Maven plugin wrapping `maven-compiler-plugin` to record
source/class mappings.

- **Requires**: `pom.xml` change or `.mvn/extensions.xml`
  (build modification)
- **Maven-only**: Does not work for Gradle, Ant, or Bazel builds
- **Version-specific**: Plugin API differs between Maven 3.x and 4.x
- **Verdict**: NOT a sidecar solution. Maven-only. High maintenance.

### Alternative 3: Maven EventSpy / Build Extension

Maven's `EventSpy` SPI observes build lifecycle events. Used by
Develocity (Gradle Enterprise) and `maven-buildtime-extension`.

- **Requires**: `.mvn/extensions.xml` or
  `-Dmaven.ext.class.path` (build modification)
- **Maven 3.3+ only**: `EventSpy` introduced in Maven 3.0.2,
  `.mvn/extensions.xml` requires 3.3.1+. Does not exist for
  Maven 2.x, Gradle, Ant, or Bazel.
- **Lifecycle-level only**: Sees which phases executed, not
  individual file I/O
- **Verdict**: NOT a sidecar solution. Maven 3.3+ only. Low fidelity.

### Alternative 4: `javac -verbose`

Compiler flag printing compilation details to stderr.

- **Requires**: Compiler args change in `pom.xml` or CLI
  (build modification)
- **Output not standardized**: Format differs across JDK vendors
  (Corretto, Temurin, Oracle, Zulu produce different verbose output)
- **Fragile parsing**: Human-readable text, not structured data
- **No write tracking**: Sees reads, not class file writes
- **Verdict**: NOT a sidecar solution. Fragile. Vendor-dependent.

### Alternative 5: GraalVM Native Image

Compiles to native binary, traced through C/C++ pipeline with strace.

- **Requires**: GraalVM (not used by vast majority of Java builds)
- **Still needs SYS_PTRACE** for strace
- **Verdict**: Inapplicable to standard Java builds.

### Conclusion

**None of the alternatives work as true sidecar solutions.** Every one
requires modifying the build environment, is limited to specific build
tools, or has fundamental technical blockers.

The current SourceFile + dependency tree approach is the best option
because it:

1. **Requires zero build changes** — true sidecar
2. **Works across all Java build tools** — `javac` always inserts the
   `SourceFile` attribute regardless of whether Maven, Gradle, Ant,
   or Bazel invoked it
3. **Uses the build tool's own resolver** — not signature guessing
4. **Exceeds industry Category 2 tools** — compiler-inserted provenance
   + exact dependency resolution vs signature matching + manifest
   parsing

## Honest Fidelity Comparison: Standalone vs Sidecar

Standalone captures strictly more information with higher fidelity:

| What is captured | Standalone (strace) | Sidecar (post-build) |
|-----------------|--------------------|-----------------------|
| **Source files read by `javac`** | Exact — `openat()` syscall | Inferred — SourceFile attr + path heuristic |
| **Class files written by `javac`** | Exact — `openat()` with `O_WRONLY` | Inferred — scans `target/` after build |
| **Files bundled by `jar`** | Exact — every file read logged | Not captured |
| **Annotation-processor I/O** | Captured — strace sees generated files | Missed — generated sources may not exist post-build |
| **Resource files** | Captured — every copy logged | Not captured — SourceFile only in `.class` files |
| **Dependency resolution** | Observed — strace sees JARs opened from `~/.m2` | Declared — what the build tool says it resolved |

Standalone observes **what actually happened**. Sidecar infers **what
probably happened**. When the build does something unexpected
(annotation processors, shaded JARs, resource filtering, non-standard
layouts), standalone catches it and sidecar doesn't.

### Why standalone can't replace sidecar in enterprise CI/CD

Despite higher fidelity, standalone mode has hard blockers for
enterprise deployment:

1. **`SYS_PTRACE` required**: Enterprise CI/CD universally runs
   containers without elevated capabilities. GitHub Actions, GitLab
   CI SaaS, and Jenkins on Kubernetes all run unprivileged containers.
   Requesting `SYS_PTRACE` is a security exception most platform teams
   reject.

2. **x86_64 only**: `bomtrace3` has no ARM64 port.
   `bomsh_hook.c` includes `<sys/reg.h>`, an x86-only header. As
   CI/CD moves toward ARM (AWS Graviton, GitHub ARM runners), this
   becomes increasingly limiting.

3. **Environment matching**: The SBOM must reflect the user's actual
   build. This requires matching the exact OS, JDK vendor/version,
   build tool version, system libraries, and compiler version — a
   custom Docker image per customer per project, with ongoing
   maintenance.

## The SourceFile Attribute

The `SourceFile` attribute (JVM Spec §4.7.10) is a class-level
attribute inserted by `javac` during compilation. It contains the
**simple filename** only (e.g., `"App.java"`) — not the full path.

```
ClassFile {
    ...
    attributes {
        SourceFile: "App.java"    // simple name only
    }
}
```

bisbom-gen uses path similarity heuristics to resolve the simple
name to the actual source file in the project tree.

**Works well for:**
- Standard Maven/Gradle project layouts (`src/main/java/...`)
- One-to-one mapping between source files and class names

**Can be wrong when:**
- Annotation processors generate `.java` files at compile time
- Multi-module builds have non-standard source layouts
- Shaded/relocated classes have mismatched `SourceFile` attributes
- Obfuscated builds strip the `SourceFile` attribute entirely

The SourceFile approach works across **every Java build tool** because
`javac` always inserts this attribute (unless explicitly disabled
with `-g:none`). This universality is a key advantage — it doesn't
matter whether Maven, Gradle, Ant, or Bazel invoked the compiler.

## Build Tool Dependency Resolution

The sidecar captures the dependency graph from the build tool's own
resolver — not from binary signatures or manifest guessing:

| Build Tool | Command | What It Provides |
|-----------|---------|-----------------|
| **Maven** | `mvn dependency:tree -DoutputType=dot` | Full resolved graph with scope, version conflict resolution |
| **Gradle** | `gradle dependencies` | Resolved configurations with variant-aware selection |
| **Ant + Ivy** | Parse `ivy.xml` | Declared dependencies with conflict resolution |
| **Bazel** | `bazel query` / `bazel cquery` | Resolved dependency graph |

This gives the **exact build-time resolution** including:
- Version conflict resolution (Maven nearest-wins, Gradle variant-aware)
- Scope classification (compile, test, runtime, provided)
- Direct vs transitive dependency distinction
- Exclusion processing

## Summary

The Java sidecar performs post-build provenance analysis — the best
available approach for a true sidecar (zero build modifications) that
works across all Java build tools and enterprise environments.

| Dimension | Assessment |
|-----------|-----------|
| **What it is** | Post-build provenance analysis |
| **What it is NOT** | Build interception |
| **Fidelity vs standalone** | Lower — infers vs observes |
| **Enterprise deployability** | Higher — no SYS_PTRACE, no build changes, any OS/arch |
| **vs industry SCA tools** | Better — compiler-inserted provenance + exact dependency resolution |
| **Best alternative?** | None that preserves zero-build-modification sidecar constraint |
