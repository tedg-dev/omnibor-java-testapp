# OmniBOR Analysis Java Sidecar Test Environment

> **This is the Java sidecar test environment.** The
> [omnibor-analysis](https://github.com/tedg-dev/omnibor-analysis)
> project maintains a separate test environment for each supported
> language — each with its own repository, build toolchain, OS, and
> CI/CD pipeline. The goal is to prove that the omnibor-analysis
> sidecar produces correct SPDX SBOMs from any language's build
> artifacts, on any OS, without modifying the build process.
>
> | Language | Test Repository | Build System | CI Build OS |
> |----------|----------------|-------------|-------------|
> | **Java** | **this repo** | Maven / Corretto 21 | Amazon Linux 2023 |
> | C | *(planned)* | make / gcc | *(TBD)* |
> | Go | *(planned)* | go build | *(TBD)* |
> | Rust | *(planned)* | cargo | *(TBD)* |

This project is a realistic Java application that simulates a product
team integrating the omnibor-analysis sidecar container into their
existing CI/CD pipeline to produce SPDX 2.3 SBOMs — without requiring
`SYS_PTRACE`, `strace`, or any modifications to the build process.

The sidecar performs **post-build provenance analysis**: it reads
compiler-inserted `SourceFile` bytecode attributes and queries the
build tool's dependency resolver to produce SBOMs with source → binary
provenance and full dependency hierarchy — capabilities that go beyond
typical SCA tools like Black Duck or Syft. For a detailed technical
assessment including industry comparison and alternative evaluation,
see [Java Sidecar: Analysis Method](docs/java-sidecar-analysis-method.md).

## Architecture

[![System Architecture](docs/architecture.png)](docs/architecture.png)
*Click to view full-size diagram ([editable source](docs/architecture.drawio))*

| Component | Description |
|-----------|-------------|
| **Test App** | Maven project depending on jsoup, Log4j2, Bouncy Castle |
| **CI Pipeline** | GitHub Actions: build on Amazon Linux 2023 / Corretto 21 |
| **Sidecar** | `ghcr.io/tedg-dev/omnibor-sidecar` — analyzes build artifacts, generates SPDX |
| **Output** | SPDX 2.3 JSON + HTML visualizations uploaded as CI artifacts |

## Phase Isolation

All omnibor-analysis test repos follow the same **two-runner,
phase-isolated** CI/CD pattern — regardless of language:

| Runner | Job | What it does |
|--------|-----|-------------|
| **Runner A** (instrumented build) | `build-and-phase1` | Build the project + run sidecar build interception → upload artifacts |
| **Runner B** (post-build analysis) | `phase2-analyze` | Download artifacts → verify integrity → generate SPDX SBOMs |

The two runners have **no shared filesystem**. Runner B receives
only what Runner A explicitly uploads. This validates the enterprise
deployment model where the instrumented build runs at the customer's
build site and SBOM generation runs in a separate analysis service.

A separate `baseline` job runs the build without the sidecar to
capture unmodified build time for overhead comparison.

Communication between the phase-isolated runners uses only:
- **`phase1_manifest.json`** — artifact paths, config, GitOID SHA-256 hashes
- **`actions/upload-artifact` / `actions/download-artifact`** — artifact transfer (no shared filesystem)

### Isolation Proofs (validated [2026-05-13](https://github.com/tedg-dev/omnibor-java-testapp/actions/runs/25828276164))

| # | Proof | Evidence |
|---|-------|----------|
| 1 | Different runners | 3 unique Worker IDs across 3 Azure regions |
| 2 | Artifact transfer only | Upload/download with SHA-256 verification |
| 3 | Manifest communication | Path mapping verified across host boundaries |
| 4 | GitOID integrity | Artifact hashes verified by Phase 2 |
| 5 | SPDX from Phase 2 only | Zero SPDX before Phase 2; 2 files + 2 HTML after |
| 6 | Build interception correct | 3 treedb entries, 6 dependencies |

## Two Independent Containers

The pipeline uses two completely independent containers — different
OS, JDK vendor, and installed software. The build container runs the
project's normal build with zero modifications. The sidecar then
analyzes the artifacts.

| Aspect | CI Build Container | Sidecar Container |
|--------|-------------------|-------------------|
| **Image** | `amazoncorretto:21-al2023` | `ghcr.io/tedg-dev/omnibor-sidecar` |
| **OS** | Amazon Linux 2023 (`dnf`) | Ubuntu 22.04 (`apt`) |
| **JDK** | Amazon Corretto 21 | OpenJDK 17 + 21 |
| **Build runs here?** | **Yes** (`mvn package -q`) | No |
| **SPDX generated here?** | No | **Yes** |
| **SYS_PTRACE** | Not used | Not required |
| **omnibor-analysis tooling** | Not installed | Installed |

## Dependencies

Chosen to exercise different dependency graph shapes:

| Dependency | Version | Why |
|-----------|---------|-----|
| **jsoup** | 1.18.3 | HTML parser — zero transitive deps |
| **Log4j2** | 2.24.3 | Logging — multi-module transitive tree |
| **Bouncy Castle** | 1.80 | Crypto provider — substantial dep tree |
| JUnit 4 | 4.13.2 | Test only — excluded from production SPDX |

## How It Works

[![CI/CD Pipeline Flow](docs/ci-pipeline-flow.png)](docs/ci-pipeline-flow.png)
*Click to view full-size diagram ([editable source](docs/ci-pipeline-flow.drawio))*

### 1. Build (Job 1 — Amazon Linux 2023)

The `build-and-phase1` job builds the project inside an
`amazoncorretto:21-al2023` Docker container with an unmodified
`mvn package -q`. This produces
`target/omnibor-java-testapp-1.0.0.jar` with all compiled `.class`
files. No omnibor-analysis tooling is present during the build.

> **Note:** This zero-modification sidecar approach is the closest
> architecture to standalone build interception available without
> `SYS_PTRACE`, and exceeds industry post-build scanners (Black Duck,
> Syft, Trivy) by using compiler-inserted provenance metadata and
> exact build-tool dependency resolution rather than signature
> matching. See [Analysis Method](docs/java-sidecar-analysis-method.md)
> for the full industry comparison.

### 2. Phase 1 — Build Interception (Job 1 — sidecar)

After the build, the sidecar container runs Phase 1 analysis:

- **Bytecode provenance** — reads the `SourceFile` attribute from
  every `.class` file (compiler-inserted per JLS §13.1), creating an
  OmniBOR treedb with SHA-256 hashes linking source → class → JAR
- **Dependency graph** — queries `mvn dependency:tree` for the exact
  resolved dependency hierarchy with scope classification
- **Manifest** — writes `phase1_manifest.json` with artifact paths,
  config, and GitOID SHA-256 hashes for integrity verification

Phase 1 artifacts are uploaded via `actions/upload-artifact`.

### 3. Phase 2 — SPDX Generation (Job 2 — separate runner)

The `phase2-analyze` job runs on a **different runner** with no
shared filesystem. It downloads Phase 1 artifacts, verifies GitOID
integrity, and generates SPDX 2.3 JSON documents:

[![SPDX Generation Flow](docs/spdx-generation.png)](docs/spdx-generation.png)
*Click to view full-size diagram ([editable source](docs/spdx-generation.drawio))*

| SBOM Type | Contents |
|-----------|----------|
| **Build** | Root package + all deps + build tools (`javac`, Maven) |
| **Analyzed** | Root package + all deps (no build tools) |

SPDX files are uploaded as GitHub Actions artifacts (90-day retention).

## Toggle Mechanism

The sidecar jobs (`build-and-phase1` + `phase2-analyze`) are
controlled by a generic, language-agnostic toggle — the same pattern
used by Jenkins, GitLab CI, and Azure DevOps for optional pipeline
stages:

| Source | Mechanism | Scope |
|--------|-----------|-------|
| **Manual trigger** | `workflow_dispatch` input `enable_sbom` (boolean) | Per-run choice |
| **Default** | Repository variable `vars.OMNIBOR_ENABLED` | Push/PR triggers |

The `baseline` job always runs regardless of toggle, providing a
timing reference.

## Performance

Each CI run executes three jobs: `baseline` (build only),
`build-and-phase1` (build + sidecar Phase 1), and `phase2-analyze`
(SPDX generation on a separate runner). The sidecar adds zero
overhead to the build itself — all analysis runs post-build.

| Job | Duration | Notes |
|-----|----------|-------|
| `baseline` | 34s | Build only (timing reference) |
| `build-and-phase1` | 71s | Build + Phase 1 + artifact upload |
| `phase2-analyze` | 38s | Download + Phase 2 SPDX generation |
| **Total wall time** | ~2 min | Includes image pulls on both runners |

## Output

SPDX artifacts are uploaded as GitHub Actions artifacts with 90-day
retention. Download via the Actions UI or CLI:

```bash
gh run download <run-id> \
  -R tedg-dev/omnibor-java-testapp \
  -n spdx-output -D ./output/
```

Each run produces:

```
spdx/java/omnibor-java-testapp/<timestamp>/
├── omnibor-java-testapp-1.0.0_build.spdx.json
├── omnibor-java-testapp-1.0.0_build.spdx.html
├── omnibor-java-testapp-1.0.0_analyzed.spdx.json
└── omnibor-java-testapp-1.0.0_analyzed.spdx.html
```

- **JSON files** — SPDX 2.3 machine-readable SBOMs
- **HTML files** — Interactive D3.js force-graph visualizations (open in browser)

## Local Development

### Prerequisites

- Java 17+ (any vendor)
- Maven 3.8+

### Build

```bash
mvn package
```

### Run

```bash
java -jar target/omnibor-java-testapp-1.0.0.jar
```

### Test

```bash
mvn test
```

## Documentation

| Document | Description |
|----------|-------------|
| [Analysis Method](docs/java-sidecar-analysis-method.md) | How the sidecar works, industry comparison, fidelity assessment |
| [Architecture](docs/architecture.png) | System architecture diagram ([source](docs/architecture.drawio)) |
| [CI Pipeline Flow](docs/ci-pipeline-flow.png) | Phase-isolated build → analyze → SPDX pipeline ([source](docs/ci-pipeline-flow.drawio)) |
| [Build Observation](docs/build-interception.png) | Standalone vs sidecar comparison ([source](docs/build-interception.drawio)) |
| [SPDX Generation](docs/spdx-generation.png) | Data flow into SPDX 2.3 ([source](docs/spdx-generation.drawio)) |
| [Proof of Execution](https://github.com/tedg-dev/omnibor-analysis/blob/main/docs/features/phase-isolation/phase-isolation-cicd-results_2026-05-13.md) | Phase isolation CI/CD validation results (omnibor-analysis) |
| [Phase Isolation Design](https://github.com/tedg-dev/omnibor-analysis/blob/main/docs/features/phase-isolation/phase-isolation-system-test.md) | System test design and proofs (omnibor-analysis) |

## License

Apache License 2.0 — see [LICENSE](LICENSE).
