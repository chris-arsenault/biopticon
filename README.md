# Biopticon Probe

An Android proxy probe for investigating Hume, Garmin and Health Connect capabilities. Optional Alpha questions use the same runner. The app records attempted operations, discovered attributes, permission/protocol results and owner-observed stages. Individual JSON observations queue locally and stream through Ahara's shared ALB to a Rust Lambda and private S3.

The probe is a separate application (`io.ahara.biopticon.probe`) under `android/apps/probe/`. The future bridge's reserved location is `android/apps/bridge/`, with a different application ID and runtime state. Shared, app-independent CSV/FIT code lives in `android/packages/formats/`. No bridge is implemented here.

## Build and local checks

Install JDK 21, Android SDK platform `platforms;android-37.0`, build tools `36.0.0`, Rust, cargo-lambda and Terraform. Set `JAVA_HOME` and `ANDROID_HOME` for your installation. The checked-in Gradle wrapper uses 9.5.1; AGP 9.2.1 and Health Connect 1.2.0-alpha06 require the newer Android toolchain.

```sh
make ci
make lambda
terraform -chdir=infrastructure/terraform init -backend=false
terraform -chdir=infrastructure/terraform validate
```

APK: `android/apps/probe/build/outputs/apk/debug/probe-debug.apk`.
Lambda: `target/lambda/biopticon-probe/bootstrap`.
The debug signing key is local to the build environment. Keep the same key for sideloaded updates; replacing it requires uninstalling the app, which erases local observations and auth state.

## First phone pass

1. Install the APK on the owner's Android phone (Android 9 or later). Health Connect availability depends on the phone/provider; unavailable interfaces produce observations while other tests continue.
2. Grant body reads. Enable activity/heart-rate/sleep only if wanted, then grant the selected additional reads. History and background permissions have separate buttons.
3. Run probes. The campaign inventories visible apps and interfaces, reads bounded Health Connect variants, compares available step aggregates and saves local query fingerprints for the next pass. Enabling Alpha adds its exercise-session investigation. Tap an observation to inspect its JSON. Synthetic CSV/FIT self-tests run in CI.
4. For a background test, close the UI after starting a campaign. For correction/arrival tests, run before and after a real source action. Query window diffs are observations, not proof of deletion. Changes tokens are saved for subsequent passes.
5. Export an actual source CSV/FIT through its own UI and select it with **Inspect CSV / FIT**. Only structural fields/counts/CRC are emitted. Files larger than 2 MB are rejected locally.
6. For Bluetooth investigation, disconnect Hume, grant Bluetooth permission, explicitly opt in, scan, then select your own scale for service/characteristic enumeration. This release does not pair, write characteristics or subscribe to measurements. Read experiments can be added after service definitions are observed.
7. For protocol checks, enter a known vendor HTTPS endpoint. Three credential-free requests (GET, HEAD, OPTIONS) observe status/challenge/method behavior. Interactive vendor authentication and import/readback require the actual authorized route and owner action; delivery Cognito login proves nothing about vendor authentication.
8. Search the 123 current phone questions by ID or text, perform the specific requested experiment, and record the attribute, unit, stage and result. Questions come from [the current campaign source](contracts/probe/questions.md), with implementation boundaries and prerequisites. A saved owner observation is explicitly labeled `OWNER_OBSERVED`. Account writes require specific approval outside this app. Saving an observation executes no vendor operation and requires no approval claim; record skipped and blocked cases directly.

The campaign's run-finished event means local execution ended, not that every capability is supported or every question was answered. Missing permissions, absent data and unavailable prerequisites remain distinct. Force-stop/process death preserves committed observations and queued messages; the next campaign marks dead-process runs interrupted and starts a new pass. Interrupted vendor operations are never automatically repeated.

## Status delivery and deployment

The phone works offline without AWS configuration. To enable S3 delivery, deploy `infrastructure/terraform/`, then enter its `api_url`, `oauth_base_url` and public `client_id` outputs in the app. Sign in through the shared Cognito login. Tokens remain Keystore-encrypted on the phone; the Lambda checks the original access token's issuer, client and token type after ALB signature validation. Any user authenticated for the probe client can deliver observations. A local sign-out clears the app token state; the hosted browser's Cognito session can still exist.

Deployment uses `chris-arsenault/ahara/.github/workflows/ci.yml@main` with its standard deployment enabled. The shared job waits for the Android checks, runs the platform checks, builds the declared Rust Lambda and applies Terraform from `infrastructure/terraform/` on `main`. Pull requests run checks without applying. Local AWS credentials are not required. Deployment uses the shared Ahara state bucket, ALB/VPC/Cognito pool and unchanged WAF; no API Gateway, new VPC, database or always-running server is provisioned.

Deployment:

1. Land `../ahara-infra/infrastructure/terraform/control/project-biopticon.tf` through that repository's CI first. Its managed-project registration creates the scoped OIDC deployer and supplies Biopticon's `OIDC_ROLE` and `STATE_BUCKET` repository secrets.
2. Land Biopticon on `main`. The shared workflow obtains AWS credentials through the managed project's GitHub OIDC role and applies Terraform from the standard directory. There are no owner UUID or reader-role inputs to configure.

The deployment publishes its mobile client to `/ahara/auth-trigger/clients/biopticon-probe-mobile`, following the shared authentication contract. The platform's existing `chris` account is already admitted by its pre-authentication trigger. Other accounts follow the platform's normal app access administration and client-map refresh process.

Deployment stays in the shared workflow; keep project layout and configuration compatible with that workflow. The usage estimate lives in [the plan](BIOPTICON-PLAN.md). CI execution, live listener priorities 380/381, owner access and end-to-end phone delivery remain unverified until the pipelines and phone pass run.

Each message uses `PUT /v1/runs/{run_id}/events/{event_id}`. A 200 response follows S3 persistence; retrying identical bytes with the same ID succeeds, while changed bytes conflict. S3 keys are `diagnostics/owner/runs/<uuid>/events/<uuid>.json`; `owner` is a fixed storage prefix. Inspect partial runs using the existing AWS role's read access. The phone has one delivery queue, independent of the signed-in account. The database upgrade preserves existing event bytes, ordering and delivery states while removing old account bindings. Android retries transient network/server failures with WorkManager backoff, preserves events across sign-in recovery, and keeps failed events available for manual retry. A periodic 15-minute delivery recovery covers enqueue/worker completion races; Android scheduling remains best effort.

Status JSON contains diagnostic findings, never raw records, files, measurement values or credentials. Related findings stay together in one event. Only observations exceeding the 4 KiB serialized-byte guard are split into smaller groups with the same attempt and case IDs. Each group is readable independently; no report assembly is required. S3 expires events after 90 days; Lambda operational logs retain 14 days.

## Contracts and next passes

[Status schema](contracts/probe/status-event.schema.json) defines the transport. [Campaign notes](contracts/probe/campaign.md) explain executable case budgets and unexecuted prerequisites. [Current questions](contracts/probe/questions.md) define the packaged phone campaign; `contracts/probe/research-input.md` preserves the original design as reference. `python3 scripts/generate-catalog.py` regenerates the catalog after question or mapping changes; CI checks that it is current. AWS/build checks and synthetic format tests stay outside the phone question list.

The next milestone requires actual phone observations and deployed S3 access. Inspect those events before choosing revised experiments or writing the eventual bridge specification. Offline fixtures and a built APK cannot establish what Hume or Garmin accepts, retains or displays.
