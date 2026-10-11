# Biopticon phone proxy probe

Updated 2026-10-11. P0 and P1 local implementation/verification are complete. P2 awaits CI deployment and the owner phone pass. Local AWS credentials and manual identity inputs are not required for deployment.

## Purpose

Build a phone-installed proxy probe that performs the exploratory work we would do ourselves if we could directly inspect and exercise the target Android applications. Its only purpose is to discover capabilities and send machine-readable observations to S3 so we can define the eventual application's specification.

The working cycle is: prepare a broad probe APK → install and run it on the owner's phone → inspect S3 observations → identify unanswered questions → deliver revised probes → repeat until the relevant capabilities and limitations are understood. The first delivery should cover as many useful experiments as we can prepare beforehand; it must not assume one pass will answer everything.

The probe remains a separately installable and maintainable application after the bridge is built. Its usefulness includes investigating app updates, protocol changes and newly discovered capabilities. The future bridge consumes established capabilities; the probe continues discovering and testing them.

This replaces the earlier bridge-product plan. The original [Revision 4](.sulion-paste/paste-2026-10-10T02-23-20-299Z.txt) supplies investigation questions and target priorities, not a mandate to build the eventual bridge. Hume, Garmin and Health Connect are the initial focus. Alpha is an optional additional target and cannot block the other investigations. The 151 assay IDs remain useful references to questions; successful transfer is not the first application's completion criterion.

The owner has authorized execution of all probe phases. Local implementation and validation proceed now; live phone experiments require the actual device, and account mutations retain their specific approval requirement. The live phone catalog is [questions.md](contracts/probe/questions.md), currently 123 questions. The original 151 questions remain historical research, not a requirement to package superseded infrastructure tasks or synthetic self-tests in the app.

## What the probe does

For each selected target, enumerate accessible surfaces, attempt relevant operations, vary meaningful inputs, and record observed behavior. Discover attribute names, types, units, presence, cardinality and provenance; request supported data families and time windows; inspect export/share formats; exercise authentication and protocol behavior; investigate candidate import/encoding/readback paths where needed to answer a capability question.

Include bounded input variation and fuzzing where appropriate: optional fields present/absent, valid and invalid boundaries, alternate encodings and units, pagination and query variants, and controlled authentication error cases. Record the exact non-secret case parameters or fixture identity, seed and case index where applicable. Synthetic parser/encoder fuzzing runs in CI; phone campaigns exercise target interfaces and actual exports. Tests against a real account must distinguish read-only requests from mutations. Existing explicit approval requirements for account writes remain in effect; recording a result never executes or authorizes a write.

Explore unknowns rather than merely checking a predefined success path. A newly discovered attribute or response shape is itself a finding. Do not require discovery results to fit a preapproved attribute-name list. Distinguish inaccessible, absent, unsupported, undocumented, inconclusive and untested. Documentation and offline fixtures inform experiments but do not establish what an installed app actually does.

Each experiment has a question, target/interface, prerequisites, bounded case set, operation, timeout, observable result and stopping condition. Separate attempts and cases so a failure does not erase earlier results or stop unrelated experiments. Use finite request/case budgets and appropriate retry behavior, not uncontrolled request loops. Do not substitute a generic blocked label for a probe that has not been implemented.

Automate everything the Android interfaces permit. Ask the owner for targeted actions only where needed: granting permissions, signing in, taking a measurement, choosing an export, approving a specific write, or inspecting a target UI. Record user-observed findings separately from machine-observed findings. Respect Android sandbox boundaries; do not assume direct access to another app's private storage.

## First campaign

Use the supplied assay bodies to prepare a question-to-probe mapping before implementation. For each question, identify the executable experiment, any owner action, and the observation fields needed to interpret it. Mark documentation-only questions and conditional experiments explicitly. The mapping must cover the substance of the assays, not just count their IDs.

| Surface | Initial experiments |
|---|---|
| Phone and installed apps | Android/API/extension versions, target package/version discovery, available permissions, accessible intents/share/document interfaces and observable provider identities |
| Health Connect | SDK/runtime availability, per-type permissions, body and enabled activity attributes, origins, metadata availability, pagination, history/background access, changes/corrections, query variants and raw-versus-aggregate behavior |
| Hume | Displayed versus obtainable attributes, Health Connect export coverage, timing/correction behavior, actual export/share formats, documented authentication/access options, opted-in BLE discovery and permitted read-only service/characteristic investigation |
| Garmin | Authentication flow and challenges, refresh/expiry/errors, distinction between local app and cloud connection, candidate import/export protocols, accepted format/field variations and observable acceptance/retention/display/readback |
| Alpha, when enabled | Health Connect and CSV/share field discovery, completed versus prescribed sets, format/locale variants, actual versus adaptive targets, exercise identity, candidate Garmin activity versus workout paths |
| Probe and delivery | Interrupted/offline execution, resumption, individual failures, reproducible case identity, S3 delivery retries and readable partial results |

Independent target tests must run even if another target is unavailable. General Health Connect/provenance/authentication investigation must not wait for a successful Garmin import. A FIT encoder, parser or fixture is included only to execute a particular format/import experiment; this does not authorize a complete conversion product.

For protocols, record which interface and flow were actually attempted and which stage succeeded or failed. Cognito login authenticates status delivery; it proves nothing about Hume or Garmin authentication. Likewise, file generation, transport acceptance, storage, UI presentation and readback are separate observations.

## Separate applications and shared packages

Keep both applications in this repository with separate Android application modules and source directories. Use one Android Gradle build to consume shared library modules directly:

```text
android/
  apps/
    probe/             # Proxy-probe application; build now
    bridge/            # Future bridge application; reserved location
  packages/            # Shared Android/Kotlin library modules as needed
backend/
  probe/               # Probe status-event ingestion service
contracts/
  probe/               # Experiment and status-event contracts
infrastructure/
  terraform/           # Standard Ahara root; currently probe-owned resources
```

The probe application ID is `io.ahara.biopticon.probe`; reserve `io.ahara.biopticon` for the future bridge. They have distinct launcher identities, APK artifacts, manifests, permissions, app-private storage and authentication state, allowing both apps to remain installed together. Probe updates must not replace the bridge or require a bridge release. Shared source does not imply shared runtime data or credentials.

Place reusable target-interface clients, authentication/protocol helpers, parsers/codecs and data types in focused modules under `android/packages/` when implemented probe work establishes a concrete reusable component. The future bridge can depend on those same modules. Each app owns its UI, execution policy, persistence and workflows. Experiment definitions, case generation/fuzzing, the probe runner and status delivery belong to the probe; bridge synchronization and product workflows belong to the future bridge.

Dependencies point from apps to shared libraries. Neither application depends on the other application module, and shared libraries do not depend on app code. Do not build speculative packages or a bridge scaffold now. The reserved paths describe boundaries; create modules only as their implementation is needed. Reusable libraries must not execute experiments or send diagnostic events merely because an app calls a target interface.

Keep probe backend and event contracts in their own directories. Terraform uses the standard `infrastructure/terraform/` root, with probe-specific resource names, app client and component tags. Future bridge services retain separate runtime responsibilities and resource identities. Sharing a low-level package later does not merge their service responsibilities. This is repository organization, not a requirement to create another repository or publish packages.

## Phone implementation

Use Kotlin/Compose with ordinary target-specific probe code and a small shared runner. Keep the interface to target selection, permissions/sign-in when required, run/stop, prompts for guided experiments, current progress, local results and delivery status. Do not build body-composition dashboards, workout management, a synchronization UI, or product-level transformation workflows.

Keep enough local state to recover runs, preserve observations and retry status delivery. Room owns runs, attempts/cases, findings, required experiment state and the delivery queue. Test inputs/artifacts remain local only as needed for an experiment, comparison or resumption; no general health-record model or future archival subsystem is required.

Persist a result and its outgoing status event together. Checkpoint before operations whose uncertain outcome must be inspected rather than automatically repeated. Status delivery may retry automatically; a vendor account mutation must not automatically repeat after an uncertain response.

New experiments can be delivered in updated APKs using the same runner and event format. Version the probe build, campaign and case definitions so results from successive deliveries remain distinguishable. A remote command service, downloaded executable scripts and arbitrary code execution are not required.

## Observations sent to S3

Send structured status events describing what was attempted and observed. Include event/run/attempt IDs, probe build and campaign version, probe/case identity, target/interface, operation, diagnostic time, result, reason/error classification, elapsed time, and typed findings relevant to that question.

Findings may include discovered attribute names/types/units, supported formats, response field names, permission state, record counts, authentication challenge type, protocol stage, HTTP status and readback comparison outcome. Include reproducible non-secret request parameters or fixture identity. Preserve unknown discoveries as explicit typed findings rather than dropping them because their attribute names are new.

Construct events directly from observations. No health-data anonymization pipeline is involved. Actual measurements, raw files and credentials are not status-message payloads. Diagnostic findings must still carry enough context to explain the result and design the next experiment.

Stream each event through the shared ALB to a Rust Lambda, which authenticates the caller, validates the event and stores it in private S3. Use one event per request, a stable event ID and conditional creation so a lost acknowledgment can be retried without another stored copy. Acknowledge after persistence.

Use `PUT /v1/runs/{run_id}/events/{event_id}` and `diagnostics/owner/runs/<run_id>/events/<event_id>.json`, where `owner` is a fixed storage prefix. Compare original stored bytes on an identical-ID retry; changed bytes conflict. Keep authentication, validation and storage small.

A 4 KiB serialized-request guard is an implementation choice for the existing ingress, not an estimate of event size. Related findings stay together; only observations exceeding that measured size split into smaller groups sharing case and attempt IDs. No numbered batches, server report assembly, completion handshake or presigned upload flow is needed. The phone has one durable queue, independent of login identity; upgrades preserve existing bytes and delivery state.

The agent reads S3 objects directly using existing AWS read access, including partial runs. Ordinary run-started and run-finished events identify campaign execution and selected/skipped tests; a missing finish event leaves completion unknown. Local execution completion and successful delivery are separate states. The phone retains undelivered messages and retries after connection/authentication recovery.

S3 observations are the evidence for subsequent analysis and the future specification. Do not implement capability-query APIs, latest-report manifests, server-side matrices or dashboards unless an actual probe consumer requires them.

## Platform integration

Follow [Ahara integration instructions](../ahara/INTEGRATION.md): existing shared ALB/VPC, shared Cognito pool with a public native PKCE client, Rust Lambda, private project S3, shared Terraform state and CI, existing policy primitives and unchanged WAF. Terraform belongs in `infrastructure/terraform/`; deployment must use the shared workflow's standard deployment. Correct project layout/configuration to match the platform. The proposed probe API hostname is `api.biopticon.ahara.io`; the probe Android package is `io.ahara.biopticon.probe`. Give the probe its own OAuth client/redirect and build artifact identity; any future bridge deployment is separately specified.

Use AppAuth and Keystore-backed token storage for status delivery. Vendor authentication experiments are separate on-phone probe code. Normal platform authentication is sufficient; do not add a probe owner allowlist. Use the existing AWS role to inspect stored results; do not create a reader role or require identity identifiers as deployment inputs.

Use 90-day diagnostic retention and 14-day operational-log retention initially. Backend operational logs contain request outcome and identifiers rather than bodies or credentials. Compute current incremental costs before infrastructure implementation/apply, as required by `../ahara-infra/AGENTS.md`. No new fixed-capacity infrastructure is planned.

Relevant platform implementations and the supplied specification were inspected during preparation. Recheck exact module inputs, listener priorities and toolchain compatibility during implementation. Android build pins remain provisional; a usable JDK/SDK must first be established. Provisioning/publication and actual account mutations require their applicable authorization.

## Milestones

### P0 — Probe runner and status delivery

Establish the Android Gradle build with `android/apps/probe/` as its application module, minimal probe UI, versioned experiment/event contracts under `contracts/probe/`, local checkpointing and event queue, and the small Ahara-integrated S3 ingestion path under `backend/probe/` and `infrastructure/terraform/`. Put concrete reusable libraries under `android/packages/`; reserve `android/apps/bridge/` without implementing it. Include real device/interface inventory probes to exercise the complete path. Keep deployment authorization separate from local implementation.

Acceptance: the probe builds as its own APK with application ID `io.ahara.biopticon.probe` and app-owned storage/authentication. Dependencies preserve the app-to-library boundary. A run executes actual phone checks, preserves independent findings after interruption, and can deliver individually readable events after authorized deployment. Test local queue recovery/upgrades, schema compatibility, platform authentication and identical-ID retry behavior. Do not claim emulator or fixture behavior proves target-app behavior.

### P1 — Broad first probe campaign

Map the original investigation questions to concrete cases, implement as many useful experiments as can be prepared before access to the phone, and build the first broad campaign APK. Include discovery, input variation/fuzzing and protocol/authentication probes as described above. Owner-assisted and write experiments are visible, separate stages; unavailable prerequisites do not block other targets.

Acceptance: each planned case either has executable probe code or an explicit documentation/owner-action/conditional prerequisite. Results distinguish attempted failures from unexecuted cases and contain enough context to interpret and reproduce them. Deliver a campaign, not a scaffold presented as completed discovery.

### P2 — Phone pass, interpretation and follow-up probes

After owner installation, run the selected campaign, inspect actual S3 events, identify unknowns and contradictions, and prepare focused additional probes. Repeat with versioned APKs as evidence requires. Summarize findings and open questions in chat; build the eventual application's specification from observations rather than assumptions.

Acceptance: we have an evidence-backed map of relevant attributes, interfaces, authentication/protocol behavior and limitations sufficient to write the next application's specification. An unsupported capability is a result. A successful bridge or production synchronization system is not required. Do not claim this milestone complete before real phone results have been inspected.

## Plan state

Sulion root: `86aa51de-5f42-4884-9d00-207e0a13a221`.

| Milestone | Phase UUID |
|---|---|
| P0 | `24e3406d-0e98-4398-9966-120af3588229` |
| P1 | `5ce7eec4-a586-4a83-abce-c595860ada4a` |
| P2 | `d935c1eb-fc4b-4f38-8145-9f0fa84435e2` |

The previous M0–M6 bridge-oriented milestones are superseded by P0–P2 and retained as skipped history. P0 source/APK/Lambda and local gates are complete. The broad P1 campaign is built and locally validated. Actual phone observations have not occurred.

The first-campaign exit checks pass; next external prerequisites are landing the infrastructure registration/application through CI and owner installation. Local AWS access is not a deployment prerequisite. This document does not authorize building the eventual bridge, normalization engine, production connectors, synchronization service, health archive or workout-management application.

## P0 execution

Sulion expansion: `e89aca50-af0d-482a-9d1e-9f213a9e4525`.

1. Build and event contracts (`0fe46a03-d15f-40db-99fb-804aff1bb5a1`): establish the Android Gradle modules, Rust Lambda crate, status schema and interoperability fixtures. Verify with actual Android compilation and Rust schema tests.
2. Phone runner and delivery (`a9bf964d-33d3-4760-94a7-a0737c365924`): implement Room checkpoints/outbox, independent cases, inventory/permission observations, AppAuth PKCE and resumable event delivery. Verify persistence and lost-ack behavior, plus JVM tests and Android lint.
3. Backend and platform (`fcc96f75-7a1b-4f5d-aca9-d7f479772716`): implement authenticated ALB event handling, conditional S3 storage, costed Terraform and Ahara CI. Inspect results using existing AWS read access. Verify authentication/idempotency integration tests and Terraform validation before any apply.
4. P0 verification (`ef1e6038-ec3b-4feb-b4ba-218f6bbc3964`): run `make ci`, build the APK and Lambda artifact, inspect final contracts and dependency boundaries. Live deployed/phone checks remain explicitly unverified until executed.

Initial baseline: the repository has no application code or existing tests. A cached JDK 21 is available; Android SDK command-line tools and Gradle 8.9 are being installed in the user cache. Gradle 8.9 matches the current Ahara Android runner; exact dependency compatibility is verified by the build.

Toolchain evidence changed the initial assumption: Health Connect 1.2.0-alpha06's actual AAR metadata requires compile SDK 37 and AGP >=9.1.0. The probe uses AGP 9.2.1/Gradle 9.5.1/JDK 21 with a dedicated Android CI job; Ahara's shared workflow continues checking Rust/Terraform. Its fixed Gradle 8.9/JDK 17 Android job cannot build this dependency. The platform manifest records the Android configuration while its shared-workflow stack selects Rust/Terraform.

Local Rust ingestion tests pass for lost acknowledgments, conflicting retries, cross-owner keys, malformed events, unexpected discovery names and wrong identity/source. Android compilation and lint passed after the owner accepted SDK licenses; Room persistence and independent Garmin FIT decoder tests passed. Terraform initialization and validation passed. The platform-wrapped AWS `sts get-caller-identity` returned `Unable to locate credentials`; no alternate credential source was attempted. Deployment and real phone results remain unverified.

P0 exit evidence: Room reopen/order/owner-binding and dead-process recovery tests passed; seven CSV/FIT tests passed, including independently decoding synthetic weight_scale fields through Garmin SDK 21.218.0. The actual Android-generated event passes the Rust ingestion validator. The debug APK and optimized Lambda bootstrap build. Android lint reports zero errors and advisory dependency/style warnings. Final P1 changes reuse these gates; no live target capability is inferred from them.

## P1 execution

Sulion expansion: `21b8f4ef-4133-4c98-9f88-4f9c6a36834c`. Initial executable target cases were integrated while P0's toolchain and runner were being established, so this expansion reviews and completes that concrete first campaign rather than recreating it.

1. Question mapping (`0ed4aee0-71ee-49fb-9ec8-df06a164620c`): package current target and phone-recovery questions with honest executable/partial/owner-action/research boundaries and prerequisites. The original design stays as reference; obsolete AWS/report requirements and build/self-test tasks stay out of the phone catalog.
2. Executable first campaign (`a22a8274-d372-4012-9e2d-ae97f916c162`): inventory; selected per-type Health Connect reads/history/origins/metadata/getter presence; bounded pages and step aggregate comparison; local change tokens/window fingerprints; selected actual export inspection; opted-in BLE scan/selected service enumeration; selected HTTPS protocol requests; guided per-attribute/unit/stage observations. Synthetic FIT/CSV variation cases run in CI. Preserve independent target failures.
3. Campaign verification and delivery (`5aca4074-6704-49a8-9d11-05e41d4e12c1`): run `make ci`, build Lambda, validate Terraform, inspect APK identity/permissions/signature, and document the first phone pass in the existing README/campaign contract. Leave conditional vendor login/import/readback, characteristic-specific BLE reads and other follow-up probes explicitly unexecuted until their prerequisites or phone results exist.

The first campaign prepares useful experiments without inventing vendor endpoints or pretending to implement the final bridge. Current questions and limitations are visible in the app. Source/destination value comparisons, detailed latency schedules, route grants and full stale-token rescan are follow-up implementations; they are not claimed as covered by a generic sweep. P2 remains necessary to choose those probes from evidence.

P1 exit evidence: final Android compilation, lint and nine JVM/Room tests pass; Rust tests/clippy and actual Android-produced event ingestion pass. Terraform validation and optimized Lambda build pass. The APK's application ID is `io.ahara.biopticon.probe`, min SDK 28/target 37; its v2 signature verifies. The first APK is `android/apps/probe/build/outputs/apk/debug/probe-debug.apk`. Android lint has advisory warnings; no error is suppressed to pass the build. No deployment or vendor account mutation occurred.

## P2 execution and external prerequisites

Sulion expansion: `badc7e86-5bb6-48cb-9174-9c3722d74df7`.

1. Deploy and verify status path: land the Biopticon managed-project registration through Ahara infrastructure CI and deploy through the shared Ahara workflow on `main`. Use the existing platform login and AWS role. Verify real ALB/Cognito/S3 behavior, including negative auth and immutable retries. No owner subject, reader role or extra identity setup is required.
2. Owner phone campaign: install the built APK using [README instructions](README.md#first-phone-pass), select permissions/targets and run automatic plus targeted owner-assisted experiments. Offline observations can be collected before deployment and delivered afterward.
3. Interpret and revise probes: inspect actual S3 events, distinguish untested questions from failed operations, prepare focused additional APK cases, and repeat until the future application's specification is backed by observed capabilities.

Step 1 requires landing the changes and successful workflow execution described in the README. The earlier local AWS credential check is not a blocker: deployment belongs to CI. Step 2 needs owner installation and physical app/account interaction. No real phone/S3 results exist to support step 3 yet. Leave P2 and the root plan open; a fixture or packaged catalog cannot complete the discovery objective.

The shared-workflow correction is tracked under `aac83f6e-0388-4668-b231-d70b5f3111fc`. Terraform lives at `infrastructure/terraform/`, and the caller uses the shared Ahara workflow with standard deployment enabled after Android checks. Rust integration tests use the shared workflow's existing extra-check hook because its coverage command selects library tests. The Ahara managed-project registration uses existing bundles/primitives and grants cross-project SSM writes only to the probe's shared auth-client registration path.

Removal of unnecessary access requirements is tracked under `cdeef83d-e698-4534-a937-337c28a3a745`. Deployment requires no owner UUID, reader role, or identity tfvars. The shared workflow receives only the platform-managed OIDC/state secrets. Platform-authenticated users can send probe events, and the existing AWS role reads them. No workflow deployment or real phone delivery is claimed from local checks.

Further simplification is tracked under `0faa0024-7804-44cd-a031-db9a0526001a`: remove phone account bindings with a preserving database migration, group related findings by actual serialized size, replace the packaged historical design with current phone questions, remove approval claims from note-taking and move format self-tests to CI. These changes supersede the earlier owner-binding and verbatim-catalog implementation described in the initial P0/P1 exit history. Actual vendor-write approval and shared deployment remain in place.

### Deployment cost model

Pilot sizing assumption: 10,000 events/month, at most 4 KiB each, 250 ms at 256 MiB per Lambda invocation, one reader pass and three months of retained S3 events. Without free credits: Lambda is approximately $0.013/month at $0.20/million requests and $0.0000166667/GB-second; S3 PUTs approximately $0.05, GETs $0.004, and retained payload storage approximately $0.003 at $0.023/GB-month. Request/list retries increase these amounts. Existing shared ALB base cost is unchanged; conservatively budgeting one extra LCU for one hour/day at $0.008/LCU-hour adds $0.24/month. Log ingestion/storage and one Cognito MAU are small additional usage charges; Cognito pricing depends on the existing pool tier and features. DNS uses the existing zone and ALB alias. No additional fixed-charge service is introduced.

This is a usage estimate, not a verified account bill or an assertion about the unavailable pool configuration. Recheck pool tier/add-ons, listener availability and actual traffic before apply; any resource exceeding $10/month requires explicit approval. Sources checked 2026-10-10: [Lambda](https://aws.amazon.com/lambda/pricing/), [S3](https://aws.amazon.com/s3/pricing/), [ALB](https://aws.amazon.com/elasticloadbalancing/pricing/), [CloudWatch](https://aws.amazon.com/cloudwatch/pricing/), [Cognito](https://aws.amazon.com/cognito/pricing/).
