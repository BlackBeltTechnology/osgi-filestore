# CI/CD Flow

This document describes the GitHub Actions workflows that automate building, testing, releasing, and merging for the OSGi FileStore project.

## Workflow Overview

```mermaid
graph TD
    PUSH_DEV["Push to develop"] -->|triggers| BUILD[build.yml]
    PR["PR to develop / master / release/*"] -->|triggers| BUILD
    BUILD -->|"on increment/*, release/*"| TAG["Create merge-pr/* tag"]
    TAG -->|triggers| MERGE[merge-pr-tagged.yml]
    MERGE -->|"major.minor.qualifier → master"| MASTER_PUSH["Push to master"]
    MERGE -->|"other → develop"| DEV_PUSH["Push to develop"]
    MASTER_PUSH -->|triggers| RELEASE_MASTER[create-release-on-master.yml]
    DEV_PUSH -->|triggers| BUILD
    MANUAL["Manual trigger"] -->|triggers| RELEASE[release.yml]
    RELEASE -->|"creates PR to master"| BUILD
    RELEASE -->|"creates PR to develop"| BUILD
```

## build.yml — Main CI/CD Pipeline

Triggered on pushes to `develop` and pull requests targeting `develop`, `master`, `increment/*`, or `release/*` branches.

```mermaid
flowchart TD
    START["Push / PR event"] --> CHECK{"Branch type?"}
    CHECK -->|"master, release/*"| RELEASE_VER["Set version from pom.xml<br/>(without -SNAPSHOT)"]
    CHECK -->|"develop, increment/*"| DEV_VER["Set version:<br/>major.minor.qualifier.date_commitId_branch"]
    RELEASE_VER --> BUILD_DEPLOY["Build & deploy to Nexus"]
    DEV_VER --> BUILD_DEPLOY
    BUILD_DEPLOY --> GIT_TAG["Create git tag v&lt;version&gt;"]
    GIT_TAG --> CHECK2{"Branch type?"}
    CHECK2 -->|"increment/*, release/*"| MERGE_TAG["Create merge-pr/&lt;version&gt; tag<br/>→ triggers merge-pr-tagged.yml"]
    CHECK2 -->|"develop"| CHANGELOG["Build changelog<br/>→ Create GitHub pre-release"]
    CHECK2 -->|"other"| DONE["Done"]
    MERGE_TAG --> DONE
    CHANGELOG --> DONE
```

### Version Formats

| Branch | Version Format | Example |
|--------|---------------|---------|
| `master`, `release/*` | `major.minor.qualifier` | `1.3.0` |
| `develop`, `increment/*` | `major.minor.qualifier.YYYYMMDD_HHMMSS_commitId_branch` | `1.3.1.20260226_143000_abc1234_develop` |

## merge-pr-tagged.yml — Automatic PR Merging

Triggered when a `merge-pr/*` tag is pushed (by build.yml).

```mermaid
flowchart TD
    TAG["merge-pr/* tag pushed"] --> PARSE["Extract version from tag"]
    PARSE --> CHECK{"Version format?"}
    CHECK -->|"major.minor.qualifier<br/>(release version)"| MERGE_MASTER["Merge PR to master<br/>→ triggers create-release-on-master.yml"]
    CHECK -->|"other format<br/>(development version)"| SQUASH_DEV["Squash PR to develop<br/>→ triggers build.yml"]
    MERGE_MASTER --> CLEANUP["Delete merge-pr/* tag"]
    SQUASH_DEV --> CLEANUP
```

## create-release-on-master.yml — Production Release

Triggered when commits land on `master` (typically via merge from a release PR).

```mermaid
flowchart TD
    PUSH["Push to master"] --> VERSION["Get version from tag"]
    VERSION --> CHANGELOG["Build changelog"]
    CHANGELOG --> RELEASE["Create GitHub release (latest)<br/>with changelog"]
```

## release.yml — Manual Release Trigger

Manually triggered with a version parameter. Creates the release and increment PRs that flow through the rest of the pipeline.

```mermaid
flowchart TD
    MANUAL["Manual trigger<br/>with version param"] --> CHECK{"Version = 'auto'?"}
    CHECK -->|yes| AUTO["Use version from pom.xml<br/>(without -SNAPSHOT)"]
    CHECK -->|no| CUSTOM["Use provided version"]
    AUTO --> NEXT["Calculate next version<br/>(qualifier + 1)"]
    CUSTOM --> NEXT
    NEXT --> PR_MASTER["Create PR to master<br/>with release version<br/>→ triggers build.yml"]
    NEXT --> PR_DEV["Create PR to develop<br/>with next version<br/>→ triggers build.yml"]
```

## Complete Pipeline Interaction

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant GH as GitHub
    participant Build as build.yml
    participant Merge as merge-pr-tagged.yml
    participant Master as create-release-on-master.yml
    participant Release as release.yml

    Dev->>GH: Push to develop
    GH->>Build: Trigger
    Build->>GH: Deploy to Nexus + create tag + pre-release

    Dev->>GH: Manual release trigger
    GH->>Release: Trigger
    Release->>GH: Create PR to master + PR to develop
    GH->>Build: Trigger (PR build)
    Build->>GH: Create merge-pr/* tag
    GH->>Merge: Trigger
    Merge->>GH: Merge PR to master
    GH->>Master: Trigger
    Master->>GH: Create GitHub release (latest)
```
