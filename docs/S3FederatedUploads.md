# S3 Federated Uploads — Phase 1 Artifacts to S3 and Phase 2 Fargate Orchestrator

## Overview

S3 replaces GitHub Actions artifacts as the transport between Phase 1 and
Phase 2. This models the enterprise deployment pattern: the build site pushes
Phase 1 artifacts to S3, and a separate analysis service pulls from S3 to
run Phase 2 (SPDX generation).

## S3 Path Structure

Given a merged PR's commit SHA, all artifacts for that build are stored under:

```
s3://omnibor-spdx-artifacts/java/omnibor-java-testapp/<commit-sha>/<run-id>/
├── phase1/    ← treedb, dep:tree, manifest (Phase 2 inputs)
└── build/     ← JARs, class files (optional)
```

The `run_id` sub-folder handles the case where the same commit is built
multiple times (re-runs).

To list everything for a specific commit:

```bash
aws s3 ls s3://omnibor-spdx-artifacts/java/omnibor-java-testapp/<sha>/ --recursive
```

## AWS Setup

### Step 1: Create the S3 Bucket

```bash
aws s3api create-bucket --bucket omnibor-spdx-artifacts --region us-east-1
```

### Step 2: Create the OIDC Identity Provider

This allows GitHub Actions to authenticate with AWS without long-lived keys:

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1
```

### Step 3: Create the IAM Role

**Trust policy** (`/tmp/trust-policy.json`):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {
      "Federated": "arn:aws:iam::930218373905:oidc-provider/token.actions.githubusercontent.com"
    },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
      },
      "StringLike": {
        "token.actions.githubusercontent.com:sub": [
          "repo:tedg-dev/omnibor-*-testapp:*",
          "repo:CiscoSecurityServices/*:*",
          "repo:gh-xr.scm.engit.cisco.com/*:*"
        ]
      }
    }
  }]
}
```

The `StringLike` wildcard array allows any of the following to assume this role:

- `tedg-dev/omnibor-*-testapp` — any OmniBOR testapp repo under `tedg-dev`
- `CiscoSecurityServices/*` — any repo under the `CiscoSecurityServices` GitHub org
- `gh-xr.scm.engit.cisco.com/*` — any repo on the Cisco GitHub Enterprise instance

**Note:** The Cisco GHE instance (`gh-xr.scm.engit.cisco.com`) may require a
separate OIDC provider if its issuer URL differs from `token.actions.githubusercontent.com`.
Verify the OIDC issuer URL for your GHE instance before adding it.
See Appendix for examples of multiple OIDC providers.

Create the role:

```bash
aws iam create-role \
  --role-name github-actions-s3 \
  --assume-role-policy-document file:///tmp/trust-policy.json
```

**S3 permissions policy** (`/tmp/s3-policy.json`):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject"],
      "Resource": "arn:aws:s3:::omnibor-spdx-artifacts/*"
    },
    {
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::omnibor-spdx-artifacts"
    }
  ]
}
```

Attach the policy:

```bash
aws iam put-role-policy \
  --role-name github-actions-s3 \
  --policy-name s3-spdx-access \
  --policy-document file:///tmp/s3-policy.json
```

## GitHub Actions Workflow

The `s3fedup.yml` workflow runs: Build → Phase 1 → S3 Upload.

```yaml
# S3 Upload Test — Build + Phase 1 → S3
#
# Manual-trigger only. Does not interfere with sbom.yml.
# Builds the project, runs Phase 1 sidecar (build interception),
# then uploads Phase 1 artifacts + build output to S3.
# A separate Phase 2 consumer will pull from S3 to generate SPDX.

name: S3 Upload Test

on:
  workflow_dispatch:
    inputs:
      upload_build_output:
        description: "Include JARs and class files in S3 upload"
        type: boolean
        default: true

permissions:
  id-token: write   # OIDC for AWS
  contents: read
  packages: read

env:
  SIDECAR_IMAGE: ghcr.io/tedg-dev/omnibor-sidecar:latest
  MVN_VER: "3.9.8"
  S3_BUCKET: omnibor-spdx-artifacts
  S3_PREFIX: java/omnibor-java-testapp

jobs:
  build-phase1-s3:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683  # v4.2.2

      # ── Build ──
      - name: Build on Amazon Linux 2023
        run: |
          docker run --rm \
            -v "${{ github.workspace }}:/project" \
            -w /project \
            -e MVN_VER="${{ env.MVN_VER }}" \
            amazoncorretto:21-al2023 \
            bash -c '
              dnf install -y tar gzip &&
              curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MVN_VER}/binaries/apache-maven-${MVN_VER}-bin.tar.gz" -o /tmp/mvn.tar.gz &&
              tar xzf /tmp/mvn.tar.gz -C /opt &&
              export PATH="/opt/apache-maven-${MVN_VER}/bin:$PATH" &&
              mvn --version &&
              mvn package -q
            '

      # ── Phase 1: Build interception ──
      - name: Login to GHCR
        uses: docker/login-action@74a5d142397b4f367a81961eba4e8cd7edddf772  # v3.4.0
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Pull sidecar image
        run: docker pull "$SIDECAR_IMAGE"

      - name: "Phase 1: Build interception via sidecar"
        run: |
          mkdir -p "${{ github.workspace }}/spdx-output"
          docker run --rm \
            -v "${{ github.workspace }}:/workspace/repos/omnibor-java-testapp" \
            -v "${{ github.workspace }}/spdx-output:/workspace/output" \
            -e OMNIBOR_MODE=sidecar \
            "$SIDECAR_IMAGE" \
            python3 /workspace/app/analyze.py \
              --repo omnibor-java-testapp \
              --mode sidecar \
              --phase build \
              --skip-clone

      # ── Upload to S3 ──
      - name: Configure AWS credentials (OIDC)
        uses: aws-actions/configure-aws-credentials@e3dd6a429d7300a6a4c196c26e071d42e0343502  # v4
        with:
          role-to-assume: arn:aws:iam::930218373905:role/github-actions-s3
          aws-region: us-east-1

      - name: Upload Phase 1 artifacts to S3
        run: |
          S3_PATH="s3://${{ env.S3_BUCKET }}/${{ env.S3_PREFIX }}/${{ github.sha }}/${{ github.run_id }}"
          echo "[INFO] Uploading Phase 1 artifacts to ${S3_PATH}/phase1/"
          aws s3 cp spdx-output/ "${S3_PATH}/phase1/" --recursive

      - name: Upload build output to S3
        if: inputs.upload_build_output
        run: |
          S3_PATH="s3://${{ env.S3_BUCKET }}/${{ env.S3_PREFIX }}/${{ github.sha }}/${{ github.run_id }}"
          echo "[INFO] Uploading build output to ${S3_PATH}/build/"
          aws s3 cp target/ "${S3_PATH}/build/" --recursive

      - name: Verify S3 upload
        run: |
          S3_PATH="s3://${{ env.S3_BUCKET }}/${{ env.S3_PREFIX }}/${{ github.sha }}/${{ github.run_id }}"
          echo "=== S3 contents ==="
          aws s3 ls "${S3_PATH}/" --recursive
          echo ""
          echo "=== Phase 2 can pull from ==="
          echo "  Phase 1: ${S3_PATH}/phase1/"
          echo "  Build:   ${S3_PATH}/build/"
```

### Triggering the Workflow

```bash
# Default (includes build output)
gh workflow run s3fedup.yml -R tedg-dev/omnibor-java-testapp --ref feat/s3-upload-test

# Skip build output
gh workflow run s3fedup.yml -R tedg-dev/omnibor-java-testapp --ref feat/s3-upload-test -f upload_build_output=false
```

## Phase 2 Consumer Architecture

### Event-Driven Flow

```
GitHub Actions (Build + Phase 1)
    │
    ▼
S3 bucket ──► S3 Event Notification ──► SQS queue
                                            │
                                            ▼
                                    Go service (long poll SQS)
                                            │
                                            ▼
                                    Pull Phase 1 artifacts from S3
                                            │
                                            ▼
                                    Launch Phase 2 container
                                            │
                                            ▼
                                    Write SPDX back to S3
```

A Go service monitors S3 via SQS long polling. When Phase 1 artifacts land,
it launches a Phase 2 container to generate SPDX.

### Container Launch Options

- **Docker SDK** (`github.com/docker/docker/client`) — simplest for
  single-host setups. Mount downloaded S3 artifacts into the sidecar.
- **ECS `RunTask`** — AWS-managed containers. The Go service calls
  `ecs:RunTask` with the sidecar image and passes the S3 path as an
  environment variable.
- **Local `exec.Command`** — shell out to `docker run` with the same
  sidecar image and volume mounts used in the workflow.

### Go Orchestrator Logic

```
loop:
    msg := sqs.ReceiveMessage(queue, waitTimeSeconds=20)
    if msg == nil: continue

    // Parse S3 event → extract key
    // e.g., java/omnibor-java-testapp/<sha>/<run_id>/phase1/phase1_manifest.json
    s3Key := parseS3Key(msg)

    // Launch Phase 2 ECS task with environment overrides
    ecs.RunTask({
        taskDefinition: "omnibor-phase2",
        overrides: {
            containerOverrides: [{
                environment: [
                    {S3_INPUT_PATH:  "s3://bucket/<sha>/<run_id>/phase1/"},
                    {S3_OUTPUT_PATH: "s3://bucket/<sha>/<run_id>/spdx/"},
                    {REPO_NAME:     "omnibor-java-testapp"},
                ],
            }],
        },
    })

    // Optionally wait for task completion, then delete SQS message
    sqs.DeleteMessage(msg)
```

## ECS Deployment via AWS CDK

### Architecture

```
GitHub Actions (Build + Phase 1)
    │
    ▼
S3 bucket
    │
    ├── Event Notification
    ▼
SQS queue
    │
    ▼
ECS Fargate Service (Go orchestrator)     ← long-running, 1 task
    │
    │  calls ecs:RunTask
    ▼
ECS Fargate Task (omnibor-sidecar)        ← ephemeral, per-job
    │
    │  reads Phase 1 from S3, writes SPDX back to S3
    ▼
S3 bucket (spdx/ prefix)
```

Fargate is a launch type within ECS — not a separate service. Any ECS
cluster supports both Fargate and EC2 launch types. If the target
environment is an existing ECS cluster with EC2 instances, Phase 2 tasks
can run on either launch type (one-line CDK change).

### CDK Stack (TypeScript)

```typescript
import * as cdk from 'aws-cdk-lib';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as s3n from 'aws-cdk-lib/aws-s3-notifications';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
```

#### S3 + SQS (event plumbing)

```typescript
const bucket = new s3.Bucket(this, 'ArtifactBucket', {
  bucketName: 'omnibor-spdx-artifacts',
  removalPolicy: cdk.RemovalPolicy.RETAIN,
});

const queue = new sqs.Queue(this, 'Phase1Queue', {
  queueName: 'omnibor-phase1-notifications',
  visibilityTimeout: cdk.Duration.minutes(15),  // must exceed Phase 2 runtime
  retentionPeriod: cdk.Duration.days(7),
  deadLetterQueue: {
    queue: new sqs.Queue(this, 'Phase1DLQ', {
      queueName: 'omnibor-phase1-dlq',
      retentionPeriod: cdk.Duration.days(14),
    }),
    maxReceiveCount: 3,  // retry 3 times, then DLQ
  },
});

// Only fire on phase1_manifest.json — signals Phase 1 is complete
bucket.addEventNotification(
  s3.EventType.OBJECT_CREATED,
  new s3n.SqsDestination(queue),
  { suffix: 'phase1_manifest.json' },
);
```

#### ECS Cluster + Task Definitions

```typescript
const vpc = new ec2.Vpc(this, 'Vpc', { maxAzs: 2 });
const cluster = new ecs.Cluster(this, 'Cluster', { vpc });

// ── Go orchestrator (long-running service) ──
const orchestratorTaskDef = new ecs.FargateTaskDefinition(this, 'OrchestratorTask', {
  memoryLimitMiB: 512,
  cpu: 256,
});

orchestratorTaskDef.addContainer('orchestrator', {
  image: ecs.ContainerImage.fromRegistry('ghcr.io/tedg-dev/omnibor-orchestrator:latest'),
  environment: {
    SQS_QUEUE_URL: queue.queueUrl,
    ECS_CLUSTER: cluster.clusterArn,
    PHASE2_TASK_DEF: 'omnibor-phase2',
    S3_BUCKET: bucket.bucketName,
    SUBNET_IDS: '',       // populated via CDK tokens
    SECURITY_GROUP: '',   // populated via CDK tokens
  },
  logging: ecs.LogDrivers.awsLogs({
    logGroup: new logs.LogGroup(this, 'OrchestratorLogs', {
      retention: logs.RetentionDays.ONE_MONTH,
    }),
    streamPrefix: 'orchestrator',
  }),
});

// ── Phase 2 sidecar (ephemeral task, launched per job) ──
const phase2TaskDef = new ecs.FargateTaskDefinition(this, 'Phase2Task', {
  memoryLimitMiB: 4096,
  cpu: 2048,
  family: 'omnibor-phase2',
});

phase2TaskDef.addContainer('sidecar', {
  image: ecs.ContainerImage.fromRegistry('ghcr.io/tedg-dev/omnibor-sidecar:latest'),
  // Environment variables set at RunTask time by the Go orchestrator:
  //   S3_INPUT_PATH, S3_OUTPUT_PATH, MANIFEST_PATH, REPO_NAME
  logging: ecs.LogDrivers.awsLogs({
    logGroup: new logs.LogGroup(this, 'Phase2Logs', {
      retention: logs.RetentionDays.ONE_MONTH,
    }),
    streamPrefix: 'phase2',
  }),
});
```

#### IAM Permissions

```typescript
// Orchestrator: read SQS, read/write S3, launch ECS tasks
queue.grantConsumeMessages(orchestratorTaskDef.taskRole);
bucket.grantRead(orchestratorTaskDef.taskRole);
orchestratorTaskDef.taskRole.addToPrincipalPolicy(new iam.PolicyStatement({
  actions: ['ecs:RunTask', 'ecs:DescribeTasks'],
  resources: [phase2TaskDef.taskDefinitionArn],
}));
orchestratorTaskDef.taskRole.addToPrincipalPolicy(new iam.PolicyStatement({
  actions: ['iam:PassRole'],
  resources: [
    phase2TaskDef.taskRole.roleArn,
    phase2TaskDef.executionRole!.roleArn,
  ],
}));

// Phase 2 sidecar: read + write S3
bucket.grantReadWrite(phase2TaskDef.taskRole);
```

#### Run the Orchestrator as a Service

```typescript
const sg = new ec2.SecurityGroup(this, 'OrchestratorSg', {
  vpc,
  description: 'Orchestrator — egress only',
});

new ecs.FargateService(this, 'OrchestratorService', {
  cluster,
  taskDefinition: orchestratorTaskDef,
  desiredCount: 1,
  assignPublicIp: true,  // or use NAT gateway
  securityGroups: [sg],
});
```

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **SQS filter on `phase1_manifest.json` suffix** | Only fires once per Phase 1 run, not per file |
| **Dead letter queue** | Failed Phase 2 jobs don't disappear silently |
| **Visibility timeout > Phase 2 runtime** | Prevents duplicate launches |
| **Separate task definitions** | Orchestrator is small (256 CPU); Phase 2 needs more compute |
| **Fargate (not EC2)** | No cluster management; pay per task-second |
| **Phase 2 reads/writes S3 directly** | No shared filesystem needed |

### Cost Estimate (low volume)

| Resource | Estimate |
|----------|----------|
| Orchestrator | ~$9/month (256 CPU, 512 MB, always-on) |
| Phase 2 tasks | ~$0.04 per run (2 vCPU, 4 GB, ~3 min) |
| SQS | Free tier covers up to 1M requests/month |
| S3 | Negligible for SPDX-sized files |

## SPDX Post-Processing Pipeline

A second SQS queue can trigger additional actions when Phase 2 writes
SPDX files back to S3:

```
Phase 2 sidecar writes SPDX to S3
    │
    ├── S3 Event Notification (suffix: .spdx.json)
    ▼
SQS queue #2 (omnibor-spdx-complete)
    │
    ▼
Go orchestrator #2 (or same orchestrator, second goroutine)
    │
    ▼
Post-processing actions
```

### Post-Processing Options

- **Vulnerability scan** — feed SPDX to Grype, Trivy, or OSV
- **Policy check** — verify license compliance, banned packages
- **Notification** — Slack/Teams alert, GitHub commit status update
- **Database ingest** — store SBOM metadata in a DB for querying
- **Dashboard update** — push to a web UI or API
- **Comparison** — diff against golden files or previous SPDX

### CDK Addition

```typescript
const spdxQueue = new sqs.Queue(this, 'SpdxCompleteQueue', {
  queueName: 'omnibor-spdx-complete',
  visibilityTimeout: cdk.Duration.minutes(5),
});

bucket.addEventNotification(
  s3.EventType.OBJECT_CREATED,
  new s3n.SqsDestination(spdxQueue),
  { suffix: '.spdx.json' },
);
```

### Extending the Orchestrator

**Option A: Single binary, multiple goroutines:**

```go
go pollQueue(phase1Queue, handlePhase1)  // launches Phase 2 container
go pollQueue(spdxQueue,   handleSPDX)    // post-processing actions
```

**Option B: Separate service** — independent scaling and different
compute/reliability requirements.

You can chain as many stages as needed — each writes to S3, each S3
event triggers the next queue. S3 serves as the data bus for the
entire event-driven pipeline.

---

## Appendix: Multiple OIDC Providers (GitHub.com + GitHub Enterprise)

GitHub.com and GitHub Enterprise Server (GHE) have **separate OIDC issuer
URLs**. Each issuer requires its own IAM OIDC Identity Provider in AWS,
and the trust policy needs a separate `Statement` block per provider.

### Step 1: Create OIDC Providers for Each Issuer

**GitHub.com** (already created above):

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1
```

**Cisco GitHub Enterprise** — replace the thumbprint with the actual value
from your GHE instance's TLS certificate chain:

```bash
aws iam create-open-id-connect-provider \
  --url https://gh-xr.scm.engit.cisco.com/_services/token \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list <GHE_THUMBPRINT>
```

To obtain the GHE thumbprint:

```bash
# Fetch the TLS certificate chain and extract the root CA thumbprint
openssl s_client -connect gh-xr.scm.engit.cisco.com:443 -servername gh-xr.scm.engit.cisco.com \
  </dev/null 2>/dev/null | openssl x509 -fingerprint -noout \
  | tr -d ':' | cut -d= -f2 | tr 'A-F' 'a-f'
```

### Step 2: Trust Policy with Multiple Principals

Each OIDC provider gets its own `Statement` block. A single statement
cannot reference two different `Federated` principals.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "GitHubComOIDC",
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::930218373905:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": [
            "repo:tedg-dev/omnibor-*-testapp:*",
            "repo:CiscoSecurityServices/*:*"
          ]
        }
      }
    },
    {
      "Sid": "CiscoGHEOIDC",
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::930218373905:oidc-provider/gh-xr.scm.engit.cisco.com/_services/token"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "gh-xr.scm.engit.cisco.com/_services/token:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "gh-xr.scm.engit.cisco.com/_services/token:sub": [
            "repo:*/*:*"
          ]
        }
      }
    }
  ]
}
```

### Key Differences from Single-Provider Setup

| Aspect | Single provider | Multiple providers |
|--------|----------------|--------------------|
| **OIDC providers** | 1 `create-open-id-connect-provider` call | 1 per issuer |
| **Trust policy statements** | 1 statement | 1 per provider (different `Principal`) |
| **Condition keys** | Prefixed with `token.actions.githubusercontent.com:` | Prefixed with each provider's issuer hostname |
| **Thumbprints** | GitHub.com's known thumbprint | Must be obtained per GHE instance |
| **S3 permissions** | Unchanged | Unchanged — same role, same S3 policy |

### Updating the Live Policy

After editing the trust policy JSON:

```bash
aws iam update-assume-role-policy \
  --role-name github-actions-s3 \
  --policy-document file:///tmp/trust-policy.json
```

The S3 permissions policy does not change — it grants access to the bucket
regardless of which OIDC provider authenticated the caller. Only the trust
policy (who can *assume* the role) needs per-provider statements.

### GHE OIDC Issuer URL

The issuer URL for GitHub Enterprise Server is typically:

```
https://<GHE_HOSTNAME>/_services/token
```

Verify by checking your GHE instance's Actions OIDC configuration, or
by inspecting the `iss` claim in a token from a GHE Actions workflow:

```yaml
- name: Debug OIDC token
  run: |
    TOKEN=$(curl -sS -H "Authorization: bearer $ACTIONS_ID_TOKEN_REQUEST_TOKEN" \
      "$ACTIONS_ID_TOKEN_REQUEST_URL&audience=sts.amazonaws.com")
    echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq '.iss, .sub'
```
