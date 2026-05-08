# OmniBOR Java Test Application

A realistic Java application for **end-to-end validation** of the OmniBOR
sidecar SPDX generation pipeline in CI/CD environments.

This project simulates a real product team integrating OmniBOR's sidecar
container into their existing CI/CD pipeline to produce SPDX 2.3 SBOMs
without requiring `SYS_PTRACE`, `strace`, or any kernel-level build
interception.

## Architecture

[![System Architecture](docs/architecture.png)](docs/architecture.png)
*Click to view full-size diagram ([editable source](docs/architecture.drawio))*

| Component | Description |
|-----------|-------------|
| **Test App** | Maven project depending on jsoup, Log4j2, Bouncy Castle |
| **CI Pipeline** | GitHub Actions: build on Amazon Linux 2023 / Corretto 21 |
| **Sidecar** | `ghcr.io/tedg-dev/omnibor-sidecar` — generates SPDX from build artifacts |
| **Output** | SPDX 2.3 JSON uploaded as CI artifacts |

## Why This Project Exists

The OmniBOR standalone pipeline runs on Ubuntu 22.04 with OpenJDK and
uses `strace` + `bomtrace3` (requiring `SYS_PTRACE` capability) to
intercept build commands. This is unsuitable for most enterprise CI/CD
environments where:

- Containers run without elevated capabilities
- The build OS may be Amazon Linux, RHEL, or Alpine — not Ubuntu
- The JDK vendor may be Corretto, Temurin, or GraalVM — not OpenJDK
- Teams cannot modify their build process to add strace wrappers

The **sidecar mode** solves this by running alongside the build without
any kernel instrumentation. This test project proves it works in a
realistic, non-Ubuntu, non-OpenJDK environment.

## Environment Comparison

| Aspect | EC2 Standalone | This CI Pipeline |
|--------|---------------|-----------------|
| **OS** | Ubuntu 22.04 (dpkg) | Amazon Linux 2023 (dnf/rpm) |
| **JDK** | OpenJDK 21 (apt) | Amazon Corretto 21 |
| **Maven** | Apache 3.9.15 | AL2023 system Maven |
| **Interception** | strace + bomtrace3 | dep:tree only |
| **SYS_PTRACE** | Required | Not used |
| **Build host** | EC2 c6i.xlarge | GitHub Actions runner |
| **Package manager** | apt/dpkg | dnf/rpm |

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

### 1. Build Phase (Amazon Linux 2023)

The GitHub Actions workflow builds the project inside an
`amazoncorretto:21-al2023` Docker container:

```
docker run amazoncorretto:21-al2023 \
    bash -c "dnf install -y maven && mvn package -q"
```

This produces `target/omnibor-java-testapp-1.0.0.jar` with all compiled
`.class` files. The build runs on a completely different OS and JDK than
our standalone analysis environment.

### 2. Sidecar Analysis Phase

[![Build Interception: Standalone vs Sidecar](docs/build-interception.png)](docs/build-interception.png)
*Click to view full-size diagram ([editable source](docs/build-interception.drawio))*

After the build, the OmniBOR sidecar container runs:

```
docker run ghcr.io/tedg-dev/omnibor-sidecar:latest \
    python3 /workspace/app/analyze.py \
    --repo omnibor-java-testapp --mode sidecar --skip-clone
```

The sidecar performs two independent analyses:

#### a) Bytecode Provenance (bomsh\_create\_bom\_java.py)

Scans every `.class` file in `target/` and reads the `SourceFile`
bytecode attribute (inserted by `javac` per JLS §13.1). This maps
each class back to its `.java` source file without needing strace.

#### b) Dependency Graph (mvn dependency:tree)

Runs `mvn dependency:tree -DoutputType=dot` to capture the full
declared dependency graph in DOT format. The parser extracts:

- **Direct dependencies** (compile scope → `DEPENDS_ON`)
- **Transitive dependencies** (pulled in by direct deps → `DEPENDS_ON`)
- **Test-scope dependencies** (excluded from production SPDX)
- **Optional/provided scope** (annotated but included)

### 3. SPDX Generation

[![SPDX Generation Flow](docs/spdx-generation.png)](docs/spdx-generation.png)
*Click to view full-size diagram ([editable source](docs/spdx-generation.drawio))*

The Java SPDX generator (`app/spdx/java_generator.py`) combines both
data sources into SPDX 2.3 JSON documents:

| SBOM Type | Contents | Relationship Types |
|-----------|---------|-------------------|
| **Build** | Root package + all deps + build tools | `DEPENDS_ON`, `BUILD_TOOL_OF`, `DESCRIBES` |
| **Analyzed** | Root package + all deps (no build tools) | `DEPENDS_ON`, `DESCRIBES` |

Build tools detected and emitted as `BUILD_TOOL_OF`:

- **javac** (JDK compiler) — version from `javac -version`
- **Maven** — version from `mvn --version`

### 4. Artifacts

SPDX JSON files are uploaded as GitHub Actions artifacts, downloadable
via:

```bash
gh run download --repo tedg-dev/omnibor-java-testapp \
    --name spdx-output --dir ./spdx-output
```

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

## Syncing Output to omnibor-analysis

To compare CI-generated SPDX against golden files:

```bash
# Download latest CI artifacts
gh run download --repo tedg-dev/omnibor-java-testapp \
    --name spdx-output --dir /tmp/testapp-spdx

# Compare against golden files in omnibor-analysis
cd /path/to/omnibor-analysis
python3 tests/compare_spdx.py \
    /tmp/testapp-spdx/spdx/java/omnibor-java-testapp/*/omnibor-java-testapp_build.spdx.json \
    tests/golden/spdx/java/omnibor-java-testapp/omnibor-java-testapp_build.spdx.json
```

## Documentation

| Diagram | Preview | Editable Source |
|---------|---------|-----------------|
| System Architecture | [`architecture.png`](docs/architecture.png) | [`architecture.drawio`](docs/architecture.drawio) |
| CI/CD Pipeline Flow | [`ci-pipeline-flow.png`](docs/ci-pipeline-flow.png) | [`ci-pipeline-flow.drawio`](docs/ci-pipeline-flow.drawio) |
| Build Interception | [`build-interception.png`](docs/build-interception.png) | [`build-interception.drawio`](docs/build-interception.drawio) |
| SPDX Generation | [`spdx-generation.png`](docs/spdx-generation.png) | [`spdx-generation.drawio`](docs/spdx-generation.drawio) |

## License

Apache License 2.0 — see [LICENSE](LICENSE).
