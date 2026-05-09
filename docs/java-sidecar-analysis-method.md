# Java Sidecar: Analysis Method and Honest Assessment

## The Java Sidecar Is NOT Doing Build Interception

omnibor-analysis is a build-interception-driven, build-time SPDX SBOM
generation tool. For C/C++, Go, and Rust, both standalone and sidecar
modes perform genuine build interception — they instrument the actual
compiler invocations to observe source → binary transformations as they
happen.

**Java sidecar mode does not do this.** It performs post-build static
analysis of build artifacts — the same artifacts available to any SCA
tool.

## What Each Mode Actually Does

### Standalone Mode (strace) — True Build Interception

```
bomtrace3 mvn package
  → strace -f -e trace=openat
  → kernel captures every file I/O syscall
```

- Kernel-level syscall interception via ptrace
- Observes `javac` open `App.java` (read) → write `App.class` (write)
- Observes `jar` open `App.class` (read) → write `app.jar` (write)
- Produces cryptographic provenance chain: source → compiler → artifact
- **This IS build interception** — the build process is directly observed

### Sidecar Mode (Java) — Post-Build Analysis

```
mvn package                           # build runs unmodified, no instrumentation
bomsh_create_bom_java.py              # reads .class bytecode SourceFile attribute
mvn dependency:tree -DoutputType=dot  # queries Maven's dependency resolver
```

- No strace, no ptrace, no kernel interception, no compiler wrapping
- `bomsh_create_bom_java.py` runs **after** the build completes
- Reads the `SourceFile` bytecode attribute from compiled `.class` files
  (JVM Spec §4.7.10, inserted by `javac` per JLS §13.1)
- Uses path similarity heuristics to map `.class` → `.java` source
- Runs `mvn dependency:tree` to capture the declared dependency graph
- **This is NOT build interception — it is post-build static analysis**

## Three Levels of Build Observation

Not all "interception" is equal. There are three distinct levels:

| Level | Mechanism | What It Sees | Completeness |
|-------|-----------|-------------|---------------|
| **Kernel-level** (ptrace/strace) | Intercepts syscalls via OS kernel | Every `openat()`, `read()`, `write()` — ALL file I/O by ALL processes | Complete — nothing is missed |
| **Build-system-level** (compiler wrappers) | Hooks provided by the build system or language toolchain | Compiler invocations, their arguments, input/output files | Partial — only sees what the build system exposes through its hooks |
| **Post-build** (artifact analysis) | Scans artifacts after the build | Metadata embedded in compiled files | Inferred — reconstructs what probably happened |

Standalone mode operates at the kernel level. Sidecar mode for C/C++,
Go, and Rust operates at the build-system level. Sidecar mode for Java
operates at the post-build level.

## Comparison Across Languages

| Language | Standalone | Sidecar | Sidecar Level |
|----------|-----------|---------|---------------|
| **C/C++** | `bomtrace3` (ptrace/strace) | `CC=`/`CXX=`/`AR=`/`LD=` wrapper scripts | Build-system — wraps compiler invocations |
| **Go** | `bomtrace2` (ptrace/strace) | `go build -toolexec=wrapper` | Build-system — Go calls wrapper for each tool |
| **Rust** | `bomtrace2` (ptrace/strace) | `RUSTC_WRAPPER=wrapper` | Build-system — Cargo calls wrapper for each `rustc` |
| **Java** | `bomtrace3` (ptrace/strace) | bytecode `SourceFile` attr + `mvn dependency:tree` | Post-build — artifact analysis only |

### C/C++, Go, Rust sidecar: build-system-level instrumentation

For C/C++, Go, and Rust, sidecar mode uses **compiler wrapper
mechanisms** — hooks that each language's build system provides (`CC=`
for C/C++, `-toolexec` for Go, `RUSTC_WRAPPER` for Rust). These are
the same mechanisms used by ccache, distcc, and Coverity.

These wrappers run **during** the build and see compiler invocations as
they happen — this is genuinely build-time instrumentation. However,
they are **less complete than kernel-level interception**:

- They only see what the build system routes through the hook — not
  internal compiler file I/O (e.g., implicit header resolution)
- For C/C++ header tracking, wrappers rely on GCC/Clang's `-MD`
  dependency output — an approximation, not a syscall log
- They depend on the build system **actually using** the hook

### Build environments that break compiler wrappers

The `CC=` / `-toolexec` / `RUSTC_WRAPPER` approach assumes the build
system respects these hooks. Many do not:

- **Hermetic build systems** (Bazel, Nix, Yocto) explicitly ignore
  `CC=` environment variables and use their own toolchain resolution
- **Hardcoded compiler paths** in Makefiles (e.g., `gcc` instead of
  `$(CC)`) bypass `CC=` entirely
- **CMake cached builds** may ignore `CC=` if the CMake cache is
  already populated from a previous configure step
- **Docker multi-stage builds** may not propagate environment variables
  across stages
- **Security-hardened CI environments** may restrict environment
  variable injection or PATH manipulation
- **Cross-compilation toolchains** often set their own CC/CXX and
  override any wrapper

In these environments, the sidecar wrapper approach fails silently —
the build succeeds but no interception data is captured.

### Java sidecar: post-build analysis

Java has no equivalent compiler-wrapping mechanism. The JVM ecosystem
does not provide a `JAVAC_WRAPPER` hook or standard. The sidecar falls
back to analyzing build artifacts after the fact.

## How Is This Different From Black Duck?

Black Duck (BDBA) and omnibor-analysis Java sidecar both analyze
post-build artifacts — JAR/WAR files containing `.class` files. They
ask different questions of the same artifacts:

| Aspect | Black Duck (BDBA) | omnibor-analysis Java Sidecar |
|--------|------------------|-------------------------------|
| **What it scans** | JAR/WAR files | JAR/WAR files (same artifacts) |
| **Method** | SHA hashes + `pom.properties` + string patterns matched against proprietary KnowledgeBase | `SourceFile` bytecode attribute + `mvn dependency:tree` |
| **Question answered** | "What known components are in this binary?" | "Which source files produced which class files?" |
| **Source → binary provenance** | No | Yes — SHA-256 chain via OmniBOR treedb |
| **Dependency scope awareness** | No — flat list of detected components | Yes — compile vs test vs runtime vs provided |
| **Direct vs transitive** | No — cannot distinguish | Yes — full dependency hierarchy from Maven resolver |
| **Unknown components** | Only finds what's in its database | Finds everything the build system resolved |
| **Output format** | Proprietary | SPDX 2.3 (open standard) |

### What omnibor-analysis does better

1. **Source-to-binary provenance** via compiler-inserted `SourceFile`
   attribute — creates an OmniBOR treedb with SHA-256 hashes mapping
   `.java` → `.class` → `.jar`
2. **Build-system dependency resolution** — uses Maven's own resolver
   (`mvn dependency:tree`), not binary signature guessing
3. **Scope and hierarchy** — knows direct vs transitive, compile vs
   test vs runtime vs provided
4. **Open standard output** — SPDX 2.3, not proprietary format

### What both have in common

Both are analyzing the **same post-build artifacts** (JAR files
containing `.class` files). Neither is intercepting the Java build
process itself. The `SourceFile` attribute is marginally more
interesting than signature matching because it's compiler-inserted
provenance metadata rather than packaging metadata — but it's still
being read *after* the build, from the same `.class` files that any SCA
tool can access.

## The SourceFile Attribute — What It Is and Isn't

The `SourceFile` attribute (JVM Spec §4.7.10) is a class-level
attribute inserted by `javac` during compilation. It contains the
**simple filename** (e.g., `"App.java"`) — not the full path.

```
ClassFile {
    ...
    attributes {
        SourceFile: "App.java"    // simple name only
    }
}
```

omnibor-analysis uses **path similarity heuristics** to resolve the
simple name to the actual source file path in the project tree.

**Where this works well:**
- Standard Maven/Gradle project layouts (`src/main/java/...`)
- One-to-one mapping between source files and class names

**Where this can be wrong:**
- Annotation processors that generate `.java` files at compile time
- Multi-module builds with non-standard source layouts
- Shaded/relocated classes where the `SourceFile` attribute doesn't
  match the filesystem path
- Obfuscated builds that strip the `SourceFile` attribute entirely

## Could Standalone Mode Run on the User's CI/CD Instead?

If the goal is true build interception for Java, standalone mode
(strace/ptrace) provides it. The question is whether standalone can be
adapted to run in enterprise CI/CD environments.

### Environment matching is harder than it looks

The SPDX SBOM must reflect what the user's **actual production build**
produces. This requires matching not just the OS family but the exact
versions of everything in the toolchain:

| Requirement | Reality |
|------------|---------|
| **OS distribution + version** | omnibor-analysis has RHEL 9 and Alpine 3.19, but enterprises run RHEL 8.7, RHEL 9.2, AL2023, Ubuntu 20.04/22.04/24.04, SLES 15, etc. Each minor version ships different system library versions that affect linked binaries. |
| **JDK vendor + version** | Amazon Corretto 21.0.3, Eclipse Temurin 21.0.4, Azul Zulu 21.0.3, Oracle GraalVM 21 — each vendor patches differently. Corretto on AL2023 resolves different system libraries than Temurin on Ubuntu. |
| **Build tool versions** | Maven 3.8.x resolves dependency conflicts differently than 3.9.x. Gradle 7.x vs 8.x can produce different dependency graphs. These are not interchangeable. |
| **System libraries (glibc, OpenSSL)** | C/C++ binaries link against specific glibc and OpenSSL versions. An SBOM from a RHEL 8.7 build is not valid for a RHEL 9.2 build — the linked libraries differ. |
| **Compiler versions** | GCC 11 vs GCC 13, Clang 15 vs 17 — different default flags, different warnings, different codegen. The SBOM should reflect the actual compiler used. |

Matching the user's exact environment requires building a custom
Docker image per customer, per project. This is feasible but is an
ongoing maintenance burden — not a one-time setup.

### Hard blockers

1. **`SYS_PTRACE`**: Enterprise CI/CD environments almost universally
   run containers without elevated capabilities. Requesting `SYS_PTRACE`
   is a security exception that most platform teams will reject.
   GitHub Actions, GitLab CI SaaS, and Jenkins on Kubernetes all run
   unprivileged containers by default.

2. **x86_64 only**: bomtrace3 has no ARM64 port. `bomsh_hook.c`
   includes `<sys/reg.h>`, an x86-only header. As CI/CD moves toward
   ARM runners (AWS Graviton, GitHub ARM runners), this becomes
   increasingly limiting.

## Is Standalone Mode More Valid Than Sidecar for Java?

**Yes.** Standalone mode with strace/ptrace captures strictly more
information and with higher fidelity than the sidecar approach:

| What is captured | Standalone (strace) | Sidecar (SourceFile + dep:tree) |
|-----------------|--------------------|---------------------------------|
| **Which `.java` files `javac` opened** | Exact — observed via `openat()` syscall | Inferred — SourceFile attribute + path heuristic |
| **Which `.class` files `javac` wrote** | Exact — observed via `openat()` with `O_WRONLY` | Inferred — scans `target/` directory after build |
| **Which files `jar` bundled** | Exact — every file read by `jar` is logged | Not captured — relies on JAR manifest |
| **Annotation-processor-generated code** | Captured — strace sees generated `.java` files being opened | Missed — SourceFile attribute points to generated file that may not exist post-build |
| **Multi-module internal dependencies** | Captured — strace sees cross-module file reads | Partially captured — depends on `mvn dependency:tree` reporting reactor dependencies |
| **Resource files (XML, properties)** | Captured — strace sees every file copy into target | Not captured — SourceFile attribute only exists in `.class` files |
| **Dependency resolution** | Captured — strace sees every JAR opened from `~/.m2/repository` | Declared — `mvn dependency:tree` reports what Maven *says* it resolved |

The key difference: standalone observes **what actually happened** at
the kernel level. Sidecar infers **what probably happened** from
artifacts and build-system metadata. When the build does something
unexpected (annotation processors, shaded JARs, resource filtering,
non-standard layouts), standalone catches it and sidecar doesn't.

## Possible Paths Forward for Java

The current SourceFile approach was implemented first because it was
the simplest path to a working Java sidecar. The following alternatives
provide increasing levels of build-time fidelity:

### Option 1: Java Agent (`-javaagent:`)

A Java agent instruments the JVM itself. By hooking into
`java.io.FileInputStream` / `FileOutputStream` and
`java.nio.file.Files`, an agent can capture every file I/O operation
performed by `javac` and `jar` — similar to what strace captures at
the kernel level, but from within the JVM.

- **How it works**: Add `-javaagent:omnibor-agent.jar` to
  `MAVEN_OPTS` or the `javac` command. The agent uses
  `java.lang.instrument` to intercept file operations.
- **What it captures**: Source files read, class files written,
  resource files copied — the same provenance chain as strace.
- **Advantages**: No `SYS_PTRACE`, works on any OS/arch, works inside
  any CI/CD, minimal build overhead.
- **Disadvantages**: Requires modifying `MAVEN_OPTS` (a single env var
  change — less invasive than `CC=` wrappers). Only captures JVM-level
  I/O, not native processes (unlikely to matter for Java builds).
- **Effort**: Medium — the `java.lang.instrument` API is well-documented
  and widely used (JaCoCo, ByteBuddy, and OpenTelemetry all use it).

### Option 2: Maven Compiler Plugin Wrapper

A custom Maven plugin that wraps the `maven-compiler-plugin` and
records which source files are compiled and which class files are
produced.

- **How it works**: Configure as a Maven plugin in `pom.xml` or via
  `.mvn/extensions.xml`. Intercepts the `compile` phase.
- **What it captures**: Source → class file mappings per compilation
  unit, compiler arguments, classpath.
- **Advantages**: Runs within Maven's lifecycle, no external
  dependencies.
- **Disadvantages**: Requires adding a plugin to each project's
  `pom.xml` (more invasive than an agent). Does not capture `jar`
  packaging step or resource file copies.
- **Effort**: Medium.

### Option 3: `javac` Verbose Output

The `javac` compiler supports verbose flags that print compilation
details to stderr.

- **How it works**: Add `-verbose` or `-J-verbose:class` to the
  compiler arguments via `maven-compiler-plugin` configuration.
- **What it captures**: Which source files are being compiled and
  which class files are loaded. Less precise than strace — does not
  capture writes, only compilation events.
- **Advantages**: Zero code to write — just a compiler flag.
- **Disadvantages**: Output is human-readable, not structured. Parsing
  is fragile. Does not capture file write operations. Does not capture
  `jar` bundling.
- **Effort**: Low.

### Option 4: Accept the SourceFile Approach

Keep the current post-build analysis as-is. This is already
implemented and deployed.

- **Advantages**: Works everywhere, no build modifications required.
- **Disadvantages**: Weakest provenance fidelity. Cannot distinguish
  from what any other SCA tool could do with the same artifacts.

### Option 5: GraalVM Native Image

For teams using GraalVM native-image compilation, the output is a
native binary that can be traced through the C/C++ ADG pipeline.

- **Advantages**: Full kernel-level interception of the native
  compilation.
- **Disadvantages**: Only applies to GraalVM native-image builds, not
  standard JVM builds.
- **Effort**: High.

### Recommendation

**Option 1 (Java Agent)** provides the best balance of fidelity and
deployability. It captures the same source → binary provenance chain
as strace, requires only a single environment variable change
(`MAVEN_OPTS`), works on any OS/architecture, and requires no
`SYS_PTRACE` capability. This is the approach used by JaCoCo (code
coverage), OpenTelemetry (observability), and other production-grade
Java instrumentation tools.

## Implications for Documentation

The test repository README and diagrams should accurately describe what
the Java sidecar actually does:

- **Phase 1** should be called "Build Artifact Analysis" or "Post-Build
  Provenance Analysis" — not "Build Interception"
- The build-interception comparison diagram should be honest about the
  sidecar side: it analyzes artifacts, it does not intercept the build
- The value proposition should focus on what's genuinely different:
  source→binary provenance chain, build-system dependency resolution,
  scope awareness, and open-standard output
