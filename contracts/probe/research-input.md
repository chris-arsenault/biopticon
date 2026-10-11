# Ahara Health Bridge — AWS Capability Pilot (Revision 4: Body Composition First)

**Status:** Proposed technical design, 2026-10-09. **Deliverable:** an Android diagnostic app plus a minimal AWS endpoint, producing reproducible machine-readable evidence in S3. **No production sync, vendor account mutation without confirmation, or permanent health-data system of record in this pilot.**

**Priority, in order:**

1. **Primary — Hume body composition → Garmin:** discover all accessible Hume measurements, test acquisition and authentication, generate Garmin-compatible measurements, perform explicitly approved imports, and verify what Garmin *retains* and *displays*. A negative result backed by evidence is still a valid conclusion.
2. **Secondary — General health-data integration:** characterize Health Connect, vendor read/write boundaries, provenance, historical access, transformations, corrections, latency, authentication, and reusable data-movement constraints. Preserve the possibility of a future AWS source of truth without building it now.
3. **Optional side quest — Alpha Progression:** assay completed workout import **and** planned workout transfer to Garmin. Include exercise identity, target sets/reps/weight/rest, adaptive next-session recommendations, and device execution. This work does **not** replace or gate the primary pilot.

**Change control:** This revision restores Revision 2 as the foundation. The only significant feature addition is a **bounded, optional** planned-workout assay group. It does not re-architect the Android/AWS stack or reprioritize the Hume and general-integration goals.

## 1. Scope and placement decisions

This is deliberately **an AWS workload**, not an integration with every available Ahara service. It depends on **Android Health Connect** where the data is local, and on **AWS Cognito, Lambda, S3 and CloudWatch** for authentication, evidence storage and operational logging. No TrueNAS, database, Grafana/Loki, OTLP forwarding, Windmill, cross-service data bus or shared observability application is in the runtime path.

```text
 Hume Body Pod → Hume app → Health Connect ─┐
 Hume official export / opted-in BLE ───────┤
 Other Health Connect data origins ─────────┤
 Garmin owner-approved exports/readback ────┤
 [OPTIONAL: Alpha CSV / plans / history] ───┤
                                            ▼
                                  Android diagnostic pilot
                               [probes + SQLite evidence/outbox]
                                            │
                                    Cognito login + PKCE
                                            │
                                   API Gateway HTTP API/JWT
                                            │
                                    Rust ingest/query Lambda
                                          │          │
                              sanitized JSON       safe runtime logs
                                          ▼          ▼
                                     Private S3    CloudWatch
                                          │
                             scoped agent read access

 User-approved Garmin writes/readback occur separately on the phone:
 CORE: body composition FIT import test → Garmin Connect
 OPTIONAL: completed Alpha activity / planned Alpha strength workout probes
```

**Boundary:** Garmin/Hume/Alpha authentication or data acquisition stays on-device for the pilot. The Lambda is *not* a vendor API proxy, health record database, or workout transformation engine. Source observations, selected diagnostics and future synchronization contracts are separate. S3 currently stores **redacted diagnostic evidence**, not the user's actual health records; an opt-in future AWS source-of-truth channel is specified in §7 but not built. Core body-composition and general-integration probes do not depend on Alpha being installed, authorized or exportable.

### Why a small HTTP API rather than the whole Ahara platform?

- **Preferred for this pilot:** an isolated **API Gateway HTTP API + native Cognito JWT authorizer + Lambda + S3**. This needs no shared ALB rules, WAF body-size workaround, SSM-based OTLP exporter, VPN or runtime service dependency. It is a deliberate exception to Ahara's current general **"shared ALB; do not create API Gateways"** integration standard; document and approve that exception before implementation. Compare cost to the existing ALB route before provisioning, but do not solve cost by reconnecting unrelated services.
- **Reuse only what's directly useful:** the existing AWS account and optionally the **existing Cognito user pool**. Provision a new public mobile PKCE client; use AWS-managed JWT validation. No confidential `client_credentials` secret in the APK.
- **Operational logging:** emit bounded, redacted structured diagnostic events to Lambda **CloudWatch Logs**, with immutable detailed capability reports in S3. The agents' source is S3 JSON, not CloudWatch or a dashboard. CloudWatch is not the durable evidence ledger.
- **Persistence:** SQLite is an Android execution/outbox detail, S3 is AWS evidence persistence; there is no PostgreSQL, DynamoDB, or central measurement database. Per-run S3 objects are append-only; a `latest.json` manifest is an optional convenience pointer.

References for the intentional platform trade-off: [Ahara integration standard](https://github.com/chris-arsenault/ahara/blob/main/INTEGRATION.md), [Ahara deployment cost constraints](https://github.com/chris-arsenault/ahara-infra/blob/main/AGENTS.md). No infrastructure is being provisioned in this design. Before doing so, check the actual incremental costs, and explicitly approve exceptions to platform policy.

## 2. Runtime components and contracts

### Android diagnostic application

- **Stack:** Kotlin/Compose, Health Connect, Android Storage Access Framework for Alpha CSV and Garmin FIT files, WorkManager, Room/SQLite, AppAuth for Cognito PKCE, Android Keystore-backed token storage.
- **Screens:** `Connections & permissions` / `Body composition` / `General integration` / `Optional workout probes` / `Evidence` / `Uploads`. Present field-level acquisition and Garmin-retention results first, not an opaque sync spinner.
- **Probe contract:** `id`, `prerequisites`, `mode`, `required_permissions`, `input_fixture`, `side_effect_level`, `timeout`, `run()`, `evidence_schema`, `cleanup`. Individual failures must not abort the whole suite.
- **Execution modes:** `R` read-only automatic, `G` guided user action, `W` separately confirmed real write, `O` optional specialist investigation. The default sweep runs **R-only**, with permissions requested by feature group, never in one giant grant dialog.
- **Durability:** local records and a retryable outbox with stable run and probe IDs; background retry best-effort, manual retry always available. An offline run remains inspectable without AWS.
- **Statuses:** `PASS`, `FAIL`, `BLOCKED_PERMISSION`, `BLOCKED_AUTH`, `NO_DATA`, `UNSUPPORTED`, `INCONCLUSIVE`, `SKIPPED`. Report `UNKNOWN` rather than assuming nonexistent APIs from empty datasets.
- **Source boundaries:** Hume discovery/export, Health Connect extraction, Garmin body-measurement FIT encoding/readback and, optionally, Alpha CSV/workout conversion are isolated adapters. No generalized event bus or adapter framework required.
- **Private values:** raw weight/reps/HR/body composition, exact timestamps and CSV notes stay on Android by default. Export to S3 only sanitized names, types, counts, hashes and result metadata. Allow explicitly approved local preview of real sample values for FIT verification, never routine cloud logging.

### Minimal AWS service

**Auth:** Cognito native authorization-code + PKCE. API Gateway validates the JWT. Lambda independently checks expected access-token type, allowed `client_id`/`aud`, authorized `sub`, any configured scope, and report-key ownership. For the one-owner pilot, explicit sub allowlisting is sufficient; adding custom scopes later must be justified. The agents use an **independent read-only IAM role**, never the mobile token.

**Ingress:** single HTTPS JSON API. Lambda validates strict report schema/size (initial limit **256 KiB**), sanitization, checksum, subject, probe catalog, and run ID; it writes an immutable per-run object to private S3. No pre-signed multipart machinery in phase 1. Large artifacts remain local or use a distinct future opt-in evidence-upload endpoint.

| API | Responsibility |
|---|---|
| `POST /v1/runs` | Idempotent `run_id` + validated redacted report → append-only S3 object; return object checksum and accepted probe count. |
| `GET /v1/runs/{id}` | Read exactly one authorized owner's report; used by app and optional service agents. |
| `GET /v1/runs/latest` | Return latest completed report via S3 manifest or ordered listing; never return someone else's data. |
| `GET /v1/capabilities` | Derive most recent field/authorization matrix from stored reports; no database. |
| `GET /health` | Minimal health, no identity or diagnostic content. |

**S3 layout**, SSE-S3 encrypted, bucket block-public-access and narrow IAM policies:

```text
health-pilot-evidence/
  diagnostics/<opaque-owner-id>/runs/<run-id>.json
  diagnostics/<opaque-owner-id>/latest.json
  diagnostics/<opaque-owner-id>/artifacts/<run-id>/<approved-artifact>.json   # not used in phase 1
```

- A new S3 bucket or dedicated restricted prefix is acceptable; do **not** use the Ahara security-log bucket. Set lifecycle to e.g. **90 days** for pilot runs and define retention separately for any later archival data. Raw values and credentials never enter object keys, object tags, report bodies or CloudWatch logs.
- Object key uniqueness and canonical JSON checksum provide idempotence. Latest manifest writes must not regress on out-of-order runs; either conditionally update or derive latest from object creation order.
- After `PutObject` succeeds, failure to update the convenience manifest must be detectable/recoverable; S3 per-run evidence remains authoritative.
- Agents get a managed `GetObject`/limited `ListBucket` policy over only this diagnostic prefix or a narrow authenticated GET route. No vendor account permissions, mobile Cognito refresh tokens, S3 PutObject or bulk health histories.
- Lambda logs only `service`, `run_id`, `probe_id`, `phase`, `result`, `duration_ms`, safe error code and count. Structured per-probe evidence belongs in S3, **not operational logs**. Include a test injecting fake tokens and health values to prove redaction.
- AWS price check before deployment (API Gateway requests, Lambda requests/duration, CloudWatch retention, S3 storage/requests, Cognito usage); no fixed-capacity infrastructure without approval.

### Report schema (public AWS channel)

```json
{
  "schema_version": 2,
  "run_id": "<uuid>",
  "created_at": "<UTC>",
  "app_build": "<git-sha>",
  "environment": {"android_api": 36, "health_connect_sdk": "<version>"},
  "probe_results": [
    {
      "id": "HM-04", "provider": "hume",
      "mode": "G", "status": "PASS", "duration_ms": 180,
      "evidence": {"fields_observed": 8, "fields_in_health_connect": 2, "raw_values_uploaded": false}
    }
  ],
  "field_coverage": [],
  "authorization_matrix": [],
  "unresolved_questions": []
}
```

Do not embed real account IDs, device addresses, source record IDs, auth headers, CSV rows or sensitive free-text notes in reports. A locally generated random correlation ID is sufficient. Ingest and report authorization are **verified with negative tests**, not assumed from the API Gateway configuration.

## 3. Diagnostic assay catalog

All real vendor writes, even benign tests, are off by default. The catalog is intentionally thorough: discovering what cannot be automated is an acceptable result. Probe IDs are stable for agent diffing between runs. **Execution priority is Hume (`HM`) + body records (`HC-09`, `HC-14`, `HC-15`) → Garmin composition (`GA`) → general integration (`GI`) → optional Alpha (`AP`, `GS`, `GW`).** The shared permissions and AWS (`P`, `AW`) tests run whenever needed. Assay catalog ordering does not imply implementation priority.

### P — Device, authentication, and local reliability

| ID | Mode | Experiment / evidence sought |
|---|---|---|
| P-01 | R | Inventory Android release, SDK, Health Connect feature versions, device model family, locale, timezone, pilot version, Hume/Garmin app version if available. No device serials. |
| P-02 | R | Check system clock vs AWS server, UTC/local conversions, source timestamp precision; identify skew. |
| P-03 | R | Check app cold start, process death, run persistence, SQLite outbox, incomplete-run recovery. |
| P-04 | G | Reboot/force-stop, disable Wi-Fi, switch to cellular, enter Doze/battery saver; compare queued event recovery and background scheduling delays. |
| P-05 | R | Verify no Android permission granted beyond selected probe set and that record values remain local by default. |
| P-06 | R | Verify app works with the AWS endpoint unreachable; all results remain locally accessible and outbox drains after reconnection. |
| P-07 | R | Exercise CSV file-picker URI grants, lifecycle/revocation and no storage-wide access. |
| P-08 | R | Ensure the phone does not contain AWS IAM keys, Cognito M2M secrets or vendor credentials in build configuration. |

### HC — Health Connect read surface (body measurements first)

| ID | Mode | Experiment / evidence sought |
|---|---|---|
| HC-01 | R | Availability and version; test `HealthConnectClient.getSdkStatus` and supported platform feature flags. |
| HC-02 | G | Manifest-declared per-type permissions: allowed, denied, partial grant, and revoked after grant; handle each without failing unrelated probes. |
| HC-03 | R | For each granted relevant type, read 7-day and 30-day windows; record `supported`, `permission`, `record_count`, `dataOrigin` set, not raw values. |
| HC-04 | G | Request optional `READ_HEALTH_DATA_HISTORY`, probe >30 days; record whether history is actually accessible. |
| HC-05 | G | Request optional `READ_HEALTH_DATA_IN_BACKGROUND` and verify an actual WorkManager background read after UI closes. |
| HC-06 | R | Paginated reads (including blank or null terminal page token), quota behavior and appropriate backoff on bounded queries. |
| HC-07 | R | Compare unfiltered reads against per-origin filters (Hume, Garmin Connect, phone/Pixel providers) and distinguish origin app from physical device. |
| HC-08 | R | Capture available metadata flags: Health Connect ID, client record ID/version, last-modified, device manufacturer/model presence, recording method, app origin. Hash IDs locally for correlation; do not ship identifiers by default. |
| HC-09 | R | All supported body measurements: `WeightRecord`, `BodyFatRecord`, `BoneMassRecord`, `LeanBodyMassRecord`, `BodyWaterMassRecord`, `HeightRecord`, and `BasalMetabolicRateRecord` where supported. Presence, unit, cardinality, precision; avoid interpreting lean as skeletal muscle. |
| HC-10 | R | Relevant activity data: `ExerciseSessionRecord`, steps, distance, active/total calories, heart rate series, resting heart rate, elevation gained, speed/pace where present, and sleep (optional). Record parent/child associations and source provenance. |
| HC-11 | G | Exercise routes: separately request route access when supported, distinguish route absent from route permission withheld. No GPS coordinates uploaded. |
| HC-12 | R | For one Garmin hike, compare separately stored `ElevationGainedRecord` intervals with imported exercise-session interval and test association by time/source. Distinguish API availability from Google Health UI presentation. |
| HC-13 | R | Raw records vs `aggregate` totals on overlapping step/calorie sources to detect double counting and source-priority selection. |
| HC-14 | G | After a new Hume weigh-in, poll at bounded intervals (e.g. 0, 1, 5, 15, 60 min) for record arrival and modification timing. No tight polling. |
| HC-15 | G | Repeat weigh-in / app correction to observe duplicate IDs, updates, deletions, `clientRecordVersion`, and late records. |
| HC-16 | R | Obtain type-specific changes tokens, process change pages; simulate stale/expired token and recovery by bounded rescan plus record-ID dedup. |
| HC-17 | R | Unit conversions and missing/invalid/sentinel values; derive canonical units *only in an offline preview*. |
| HC-18 | R | Determine which record types **can** be read vs which currently contain data; never label an empty type as unsupported. |
| HC-19 | G | Android permission revoke → app restart → regrant; confirm safe partial operation and no logging of denied data. |
| HC-20 | R | Confirm no Health Connect **write** permission and no feedback loops from Garmin exports into the same bridge. |
| HC-21 | R | For Alpha-origin `ExerciseSessionRecord` inspect `exerciseSegments`: count, exercise/segment types, repetitions, set indices, optional `weight`, RPE, start/end, pause/rest intervals, and absent-field flags. Record compiled Health Connect client library and platform extension versions. |
| HC-22 | R | Verify runtime/SDK feature gating for per-segment `weight` and `setIndex` (Android platform extensions and AndroidX support differ); absence due to version must not be misclassified as an Alpha export omission. |
| HC-23 | G | Perform one short real Alpha workout; diff Health Connect session and segment data before/after and compare session count, timing, repetition totals, weights, activity energy, and source package to the app. |
| HC-24 | R | Verify privacy-restricted queries return `PERMISSION_DENIED`, not `NO_DATA`; enumerate data-origin package and session details only for granted data types. |
| HC-25 | G | Test whether set segments persist after editing a completed Alpha workout and reopening/re-syncing Health Connect; identify update vs replace behavior. |

**Initial permission scope:** request body-measurement types and `READ_EXERCISE` only for the corresponding enabled assays. Ask for route/sleep/heart-rate types only when the user activates those diagnostics. Do not request unrelated medical, reproductive, or nutrition information in a bulk discovery scan.

### HM — Hume source / export / Bluetooth (primary)

| ID | Mode | Experiment / evidence sought |
|---|---|---|
| HM-01 | R | Detect installed Hume package/version and its Health Connect origin as actually reported in data records. No assumption that an installed app has exported anything. |
| HM-02 | G | User-assisted **field inventory** from Hume's Body Pod screens/report: field names, units, precision, measurement vs derived score, whole-body vs segmental. Do not use an accessibility scraper. |
| HM-03 | R | Crosswalk each Hume-displayed field to Health Connect types: present, absent, representable-but-not-written, no standard HC counterpart, unknown. |
| HM-04 | G | One real Body Pod scan: compare Hume display timestamp and value locally with corresponding Health Connect measurement, correction/version and arrival latency. |
| HM-05 | G | Two consecutive scans, before/after Hume app sync; compare stable vs changing attributes and rounding. This is about **repeatability and export**, not clinical accuracy. |
| HM-06 | G | Hume app sign-out/offline/restore, if user chooses; determine what works without cloud login and what is rehydrated on reconnect. Never request/share a Hume password in the pilot's own UI for the first phase. |
| HM-07 | R/G | Inventory **official** data export/share mechanisms available in the installed app; inspect obtainable formats and whether segmental metrics survive. Hume's FAQ contains an export feature marked 'coming soon' in its Band discussion; do **not** infer Body Pod support from that text. Verify Body Pod export behavior in the installed app. |
| HM-08 | R | Document existence or absence of a published Hume Health API and supported authentication flow; classify as `NOT_DOCUMENTED`, not `IMPOSSIBLE`. Hume Health is **not** Hume AI's unrelated developer API. |
| HM-09 | O | Bluetooth LE **advertisement discovery** around the selected scale only: advertised names, service UUIDs, manufacturer-data length/type, discoverability and timing. Scan requires appropriate Android Bluetooth permission. No raw unique identifiers or packets sent to AWS. |
| HM-10 | O/G | With explicit consent and the Hume app disconnected, attempt safe GATT service/characteristic enumeration of *the user's own scale*, capture UUIDs, properties and authentication errors. Test standard Weight Scale (0x181D) / Body Composition (0x181B) services if advertised. |
| HM-11 | O/G | Optional read-only GATT reads where permitted; distinguish readable standardized weight/composition characteristics from proprietary, encrypted, notify-only, or pairing-required fields. Stop on any pairing / write requirement, no blind commands. |
| HM-12 | O/G | Optional user-controlled Android Bluetooth diagnostic capture for protocol investigation, with local-only storage and explicit review/redaction; never silently intercept credentials or undermine TLS. Avoid collision with Hume's own live measurement session. |
| HM-13 | O | Any direct Hume cloud adapter is deferred until a documented or authorized account access route is established. Record required auth type, token lifetime, supported field inventory, historical access, pagination, export/ToS constraints. No endpoint guessing, account probing, or automation of app credentials. |
| HM-14 | R/G | Categorize each body composition attribute (fat %, fat mass, hydration, skeletal muscle, bone, visceral rating/mass, metabolic age, segmental values, impedance if exposed) by **observable source and provenance** without inventing measurement equivalence. |

### GA — Garmin body-composition format, ingestion and readback (primary)

| ID | Mode | Experiment / evidence sought |
|---|---|---|
| GA-01 | R | Inventory installed Garmin Connect version and local account-linked status if visible through supported interfaces; distinguish local app status from Garmin cloud authentication. |
| GA-02 | R | Review documented consumer import/export routes and classify each as official manual workflow, unofficial private API, or not available. Do not treat Garmin consumer OAuth as a supported third-party write API. |
| GA-03 | G | User-triggered Garmin connection test for the **unofficial** HC2Garmin-style SSO flow: user login, MFA required/not, token issuance, token refresh, expiry, clear/logout, errors **without capturing credentials or tokens in evidence**. Use well-bounded retries; do not brute-force. |
| GA-04 | R | FIT profile discovery and code-generation matrix: weight, percent fat, percent hydration, visceral-fat mass, bone mass, muscle mass, basal metabolism, physique rating, active metabolism, metabolic age, visceral-fat rating and user-profile index (when relevant). Report units, scale factors and invalid sentinels. |
| GA-05 | R | Generate offline FIT files with a known synthetic fixture; check header, file ID, message types, timestamps, endianness, profile compatibility, CRC, invalid fields; **decode them with an independent FIT decoder**. Nothing uploaded. |
| GA-06 | R | Value conversion/property tests on offline encoder: metric/imperial conversions, range boundaries, precision, overflow, zero/unknown vs missing, unicode not placed in FIT numeric fields. |
| GA-07 | G/W | Explicitly approve one real, existing Hume-derived measurement for upload. Preview the exact fields/timestamp/account before making a Garmin write. Never upload synthetic fixtures to the real account by default. |
| GA-08 | W | Observe FIT upload transport status, response metadata, time-to-visible, any documented/private API difference; no raw response body in cloud reports. |
| GA-09 | R/G | Read back the imported measurement by sanctioned UI/manual export or authenticated read path, distinguishing `file accepted`, `measurement stored`, and `visible in Connect`. |
| GA-10 | G/W | Per-field acceptance test (smallest safe set of approved **real** readings): Garmin stored/exposed/rounded/ignored, including optional fields absent from its UI. Only try another upload with explicit consent. |
| GA-11 | G | Check if weight/body fat propagate to watch profile; capture observed delay (without assuming a recalculation of historic calories). |
| GA-12 | G | Check if Garmin re-exports imported body measures to Health Connect. Observe origin IDs and source loops, don't write them back. |
| GA-13 | R/W | Idempotence: repeat upload of the *same approved* FIT file only with consent; inspect HTTP duplicate response and stored record count. Use source ID + timestamp + content fingerprint in local outbox. |
| GA-14 | G/W | Corrections/backdated date: whether user-edited values overwrite, coexist, or are ignored; do not run without a user-approved correction case. |
| GA-15 | R | Read-only authentication-negative tests: expired token, revoked connection, 401/403/429 handling, MFA required, rate-limited backoff, maintenance/network errors. Do not deliberately trigger lockouts. |
| GA-16 | R | Determine which of the FIT-supported attributes are **actually** accepted and preserved; a defined FIT field is not evidence that Garmin Connect imports/displays it. |
| GA-17 | R/G | Check timestamp/zone offset behavior, backfill import chronology, duplicate records from scale and manual entries, and source attribution when Garmin Connect makes those observable. |
| GA-18 | O/W | Optional controlled developer-field experiment for proprietary segmental metrics **only if explicitly approved**. Expected outcome is likely `not displayed`; do not claim standard Garmin support for segmental body data. |

**Important:** `hc2garmin` is a useful technical reference, but the reviewed repository has no visible license file, and its private Garmin API/auth code can break without notice. Do not copy its implementation into a redistributable project without verifying rights. Prefer independent FIT encoding and testing against Garmin's published FIT SDK/profile.


### GI — General health-data integration (secondary)

This is a **capability and boundary** investigation, not a mandate to connect every Ahara service. It consumes the existing `HC`, `HM`, `GA` and `AW` findings rather than inventing another backend or persistence layer.

| ID | Mode | Experiment / evidence sought |
|---|---|---|
| GI-01 | R | Inventory relevant source apps and data paths actually observed on the phone: source → Health Connect → other consumers, or direct source export; do not assume bidirectional connectivity. |
| GI-02 | R | Build per-attribute lineage: original measurement name/units/time, intermediate record type and origin, destination encoding, accepted/persisted/displayed/readback status; explicitly mark each unknown boundary. |
| GI-03 | R/G | Determine which records have independent timelines versus workout/session association (e.g., elevation gained outside the exercise record); test joinability by time and data origin without promising receiving-app UI behavior. |
| GI-04 | R/G | Produce provider-by-provider authentication matrix: app permission, interactive OAuth/MFA, manual export, private/unofficial interface, expiration, background access, and whether unattended operation is realistic. Never leak credentials to S3. |
| GI-05 | R/G | Test source corrections, duplicate exports, delayed records, deletion, pagination, and historical-access bounds; record stable identity vs content fingerprint availability. |
| GI-06 | R | Simulate at-least-once delivery, partial failure, lost network, out-of-order observations, and export feedback loops using local test fixtures only. Verify deterministic resume and idempotence. |
| GI-07 | R | Record a small *future* source-native envelope contract separating acquisition from transformations and delivery. Test ability to represent unknown attributes without choosing S3 archival schemas or a database now. |

### AP — Alpha Progression acquisition, authorization, structure and fidelity (optional side quest)

**Known from Alpha's documentation:** Android Health Connect integration shares workouts (including calories), bodyweight and fat; the app offers CSV export of workout history, plans, custom exercises and body measurements, including on the free tier. Alpha explicitly states it **does not currently offer a public API**; treat any private/internal API as unapproved. Its published descriptions do not prove that Health Connect receives **individual set loads/reps**. Verify those experimentally. Sources: [official guide](https://alphaprogression.com/en/blog/alpha-progression-guide), [public API statement](https://alphaprogression.com/en/blog/alpha-progression-vs-gravl).

| ID | Mode | Experiment / evidence sought |
|---|---|---|
| AP-01 | R | Discover app package/version and actual Health Connect `dataOrigin` names; do not infer origin from install state. |
| AP-02 | R | Inventory Alpha-origin exercise sessions: title, start/end, type, activity calories and provenance. Separate session *summary* from granular set-level data. |
| AP-03 | R | Inspect `ExerciseSessionRecord.exerciseSegments` for exercises, repetitions, set indices, weights, RPE, timings and optional fields; record *missing* vs *unreadable on this Android version*. |
| AP-04 | G | Run one real short Alpha workout; diff Alpha UI, Health Connect exercise session and segments field-by-field after completion. |
| AP-05 | G | Use Android system file picker to import a user-created **CSV export**, without Alpha account credentials; inventory file names, row counts, column names, encodings and data types. |
| AP-06 | R | Test CSV dialect/locale: delimiter, quoting, multiline notes, UTF-8/BOM, decimal comma/dot, units, negative assisted load, empty fields and odd export row layouts. |
| AP-07 | R | Distinguish **performed** reps/loads from planned/recommended reps/loads and distinguish warm-up, working, drop, superset and deload sets where actually represented. |
| AP-08 | R | Preserve exercise name, custom exercise identity/category, equipment, exercise order, workout ID/plan/day, repeated occurrences, set index and RIR where CSV allows it. |
| AP-09 | R | Probe exercise modes: reps-only, weight+reps, counterweight+reps, duration, weight+duration, distance+duration, weight+distance and their semantic units. |
| AP-10 | R | Determine if load is total vs per-dumbbell, added vs bodyweight, or machine counterweight; do not silently convert an assistance number into a positive lift. |
| AP-11 | G | Compare one completed workout between Alpha UI, CSV and Health Connect, reporting lost/preserved fields at each step. |
| AP-12 | R | Identify timestamps with no timezone in CSV; require an **explicit source timezone** before making absolute timestamps; test midnight, DST and same-day multiple sessions. |
| AP-13 | G | Export the same history twice; check stable workout/set IDs, ordering, fingerprints and whether identical exports are idempotent. |
| AP-14 | G | Edit/delete a real old set in Alpha, re-export and compare: correction detection, deletion behavior, CSV snapshot semantics and HC update delay. |
| AP-15 | R/G | Determine whether Alpha offers an authorized unattended export or API. If only manual CSV is available, report `MANUAL_EXPORT_REQUIRED`, not a pretend automated connector. |
| AP-16 | G | Test Alpha sign-in/offline workflow only if wanted; establish whether a CSV export works without network or Alpha account and whether it carries all locally stored history. |
| AP-17 | R | Inspect completeness of notes, RIR (including half values), rest time, actual-vs-target volume and custom exercises; don't upload notes. |
| AP-18 | R | Check name collisions, exercise renames, multilingual names, aliases and exact exercise identity mapping to Garmin's exercise categories/subcategories. |
| AP-19 | G | Inspect system share sheet and document provider: can file be delivered into pilot via `ACTION_SEND`/SAF? Do not automate other app UI, bypass sandboxing or scrape private storage. |
| AP-20 | R | Publish `Alpha UI → CSV → Health Connect` fidelity and auth matrix, marking *supported*, *observed*, *missing* and *blocked* separately. |

### GS — Garmin **completed strength activity** import and watch coexistence (optional side quest)

**Critical distinction:** A FIT **Activity** records what happened; a FIT **Workout** defines instructions for a future session. Alpha's logged reps/weights belong in the former. Garmin officially supports importing FIT activity files, but warns that third-party files may fail structural validation. A Garmin FIT forum experiment shows that set details may display when encoded properly; this is evidence of feasibility, **not** a guarantee for our account. See [FIT Activity requirements](https://developer.garmin.com/fit/articles/file-types/activity.html), [Garmin manual upload](https://support.garmin.com/en-CA/?faq=Ht3ZP52Kju075uKvqTqu99), and [strength FIT developer discussion](https://forums.garmin.com/developer/fit-sdk/f/discussion/270009/examples-for-encoding-strength-training-activity-files/1728662).

| ID | Mode | Experiment / evidence sought |
|---|---|---|
| GS-01 | G | Obtain via file picker one user-approved **actual Garmin-recorded strength FIT export** as a structural fixture. Locally inspect `file_id`, `activity`, `session`, `lap`, `set`, `event`, `record`, sport/subsport and timestamps; do not upload original FIT to S3. |
| GS-02 | R | Use the FIT SDK/profile to map `SetMesg` fields: exercise category/subcategory, repetitions, weight, duration, set type, start/end and exercise title. Record vendor/file-specific fields and unknowns. |
| GS-03 | R | Build a tiny offline FIT **Activity** fixture from normalized Alpha sets; include all mandatory summary messages, correct sport/subsport, elapsed/time/timer values and ordered events; decode independently and verify CRC. |
| GS-04 | R | Roundtrip known set fixture through independent FIT decoding; ensure exercise order, all reps, kg/lb mass conversion, negative assistance semantics, missing values and rest sets do not silently mutate. |
| GS-05 | G/W | With explicit preview and confirmation, import **one real, completed Alpha workout** through the official Garmin Connect website activity-import flow; log accepted/rejected and reported file structure. |
| GS-06 | G | Inspect Garmin Connect's Strength activity UI: exercise labels, set count, set order, repetitions, weights, rest timing, duration, calorie figure, RIR/RPE and summary. Distinguish stored vs displayed vs dropped. |
| GS-07 | G | Export the imported Garmin activity as FIT and compare **field-by-field** with submitted data to establish persistence, including values not visible in the UI. |
| GS-08 | G | Determine if Garmin automatically syncs imported activity *to the watch* (likely different from syncing watch activities *to Connect*); record clearly either way. |
| GS-09 | R/G | Exercise mapping: Garmin known exercise vs Alpha custom name; unknown category, abbreviations, duplicate names, local language, optional custom title. Test mapping in offline fixtures before a real write. |
| GS-10 | R/G | Preserve Alpha set semantics: dumbbell per-hand loads, weighted/bodyweight, negative assistance, timed exercise, AMRAP/rep only, RIR and superset identifiers; report losses rather than invent Garmin support. |
| GS-11 | G | If Garmin recorded the **same session** on the watch, detect overlapping sessions, IDs and timestamps. Establish whether importing the Alpha session produces a duplicate Garmin activity. |
| GS-12 | R/G | Investigate *non-destructive* ways to associate Alpha sets with the existing watch-recorded Garmin activity, retaining Garmin HR/calories; **do not assume modifying an existing activity through a supported API is possible**. |
| GS-13 | O/W | Optional user-approved import of a combined **replacement test activity** that uses Garmin HR samples and Alpha set data, without deleting/overwriting the existing record; explicitly inspect duplicate and data-loss behavior. |
| GS-14 | G/W | Check authorized private upload route only after official **manual** FIT import proves detailed set data survives. Bound MFA attempts, no guessed endpoints or raw token capture; automate only if authorized and reliable. |
| GS-15 | R/G | Idempotence: same Alpha CSV import twice and same FIT again (second write approval needed); identify Garmin duplicate vs conflict behavior; record stable source fingerprints. |
| GS-16 | G | Backdated activity, timezone, DST, workout duration and edited session start; verify Garmin ordering and how it handles missing per-set timestamps. |
| GS-17 | G | Whether imported strength activities affect training load, intensity minutes, calories, records/PRs, and Health Connect re-export; observe rather than promise parity with a Garmin-recorded workout. |
| GS-18 | R | Explicitly classify FIT **Workout** (planned routine to watch) vs FIT **Activity** (completed workout to history); do not route achieved Alpha sets to the Workout API. |
| GS-19 | R | For private Garmin read/write authentication, separate official web session, unofficial SSO, refresh/MFA and token audience; never copy secrets into AWS reports. |
| GS-20 | R | Produce field-level `Alpha CSV/HC → FIT Activity → Garmin imported FIT → Garmin UI → watch/HC` matrix with `NOT_TESTED`, `ACCEPTED`, `PERSISTED`, `DISPLAYED`, `DROPPED` and evidence IDs. |

**Priority gate:** Determine whether an imported completed FIT activity reliably preserves **exercise + reps + load**. If not, do *not* call the Alpha→Garmin goal solved simply because Garmin shows a workout duration. If the user wants to record the workout on Garmin for HR, a duplicate-free merge may be the separate blocking problem.

### GW — Alpha **planned strength workout** → Garmin watch (optional side quest)

This is **not a new product goal or acceptance gate**. A completed FIT Activity is historical data; a planned Garmin Strength Workout is an executable routine. Assess separately and do not assume Garmin Connect's ordinary activity importer accepts Workout files. Alpha's static plan export may not expose its adaptive *next-session* recommendations. Developer/partner Garmin APIs and undocumented consumer workout APIs have different authorization requirements; never treat the latter as an official supported interface.

| ID | Mode | Experiment / evidence sought |
|---|---|---|
| GW-01 | G | Obtain one **user-exported Alpha plan** (CSV or approved share action). Inspect routine/day names, exercise order, sets, rep ranges, target load, rest, warmups, supersets, RIR and source identities; no app scraping. |
| GW-02 | G | Compare exported template with the actual *next planned session* displayed in Alpha. Check whether adaptive recommendations survive the export or are only present inside Alpha. |
| GW-03 | R | Inventory Garmin's structured Strength workout catalog and schema through permitted UI or user-authorized readback of a manually created example; map Alpha exercise identities as `EXACT`, `EQUIVALENT`, `LOSSY`, `UNMAPPED`. |
| GW-04 | R | Build a local-only Garmin workout representation / FIT Workout fixture from the Alpha sample. Verify set targets, ranges, load units, rest and semantics; keep unsupported concepts explicitly marked as lost, not silently converted. |
| GW-05 | R/G | Categorize potential Garmin import routes: official Training API (partner/approval), manual/on-device file path, or unofficial consumer workout API (experimental and subject to breakage). Record required owner authorization, MFA/refresh, write boundaries and blocking conditions. |
| GW-06 | G/W | With separate confirmation, create **one disposable pilot-owned** planned Strength workout using a permitted import route. Verify Garmin Connect actually retains the correct exercise identities and step targets; a successful HTTP response is not proof of retention. |
| GW-07 | G | Send the created workout to the actual fēnix, inspect watch-side sequence and startability, and optionally execute a short real session to distinguish planned fields from achieved reps/weights/HR. |
| GW-08 | G/W | Change the Alpha test plan or next-session recommendation, re-export, and test a user-approved update of that *same* pilot Garmin workout without duplicate plans or destructive edits. Document whether automatic updates are possible. |
| GW-09 | R | Publish a separate plan-fidelity and authorization matrix with `ACQUIRED`, `MAPPED`, `ENCODABLE`, `ACCEPTED`, `RETAINED`, `DEVICE_RECEIVED`, `EXECUTABLE`, `BLOCKED`; do not require this report for core pilot completion. |

### AW — AWS authentication, evidence ingestion and agent access

| ID | Mode | Experiment / evidence sought |
|---|---|---|
| AW-01 | G | Native Cognito authorization-code + PKCE login/redirect/refresh/logout in the Android app; prove no client secret exists in APK or source. |
| AW-02 | R | API Gateway JWT authorizer validates issuer/signature/audience; Lambda enforces `token_use`, client ID and owner allowlist. Negative tests: missing, wrong, expired, wrong-owner and ID-token-as-access-token. |
| AW-03 | R | Endpoint inaccessible without correct auth; test least-privilege controls on both GET and POST, including IAM role denied write. |
| AW-04 | R | `POST /v1/runs` validates schema, max body size, checksum, run ID and safe field allowlist; reject unapproved raw measurements and free-text CSV notes. |
| AW-05 | R | S3 `PutObject` under owner-scoped immutable run key and reader-only access for agents; test key traversal, cross-owner prefix reads, bucket public access block and encryption. |
| AW-06 | R | Idempotent retry under network failure and Lambda retry; same `run_id` must not create another report or quietly overwrite a different payload. |
| AW-07 | R | Out-of-order completion and latest manifest: ensure an older run cannot replace the latest pointer, and manifest failure cannot hide an uploaded run. |
| AW-08 | R | Verify CloudWatch Lambda operational event has safe `run_id`, result and duration, while S3 report holds detailed per-probe evidence; no dependencies on a second telemetry stack. |
| AW-09 | R | Inject synthetic passwords, bearer tokens, cookies, fake health values, BLE identifiers and notes; block release if any appear in CloudWatch or S3 report. |
| AW-10 | R | Exercise offline Android outbox → connection restoration → exactly-once-visible S3 evidence despite at-least-once HTTP deliveries. |
| AW-11 | R | Validate redacted reports via authorized agent's IAM GetObject/ListBucket or GET API; deny unauthenticated and broad role reads. |
| AW-12 | R | Confirm TLS, timeouts, backend error codes, bounded retries, exponential backoff, clock skew and expired Cognito refresh handling. |
| AW-13 | R | Validate S3 lifecycle and CloudWatch retention; no hidden long-term archive of personal measurements. |
| AW-14 | R | Mobile app upgrade, schema version bump, unexpected probe fields and older report readers maintain forward-compatible capability reporting. |
| AW-15 | R | Measure per-run request latency, payload size, approximate AWS resource usage and cost; verify no VPC, database, managed cluster or always-on compute is introduced. |

### E2E — Proof of actual source-to-destination behavior

| ID | Mode | Experiment / evidence sought |
|---|---|---|
| E2E-01 | R/G | One Hume scan → Hume app → Health Connect: discover every displayed metric, HC record type and observable loss. |
| E2E-02 | R | Hume attribute → FIT weight profile → Garmin retention and display matrix; absent data and failed imports explicitly reported. |
| E2E-03 | G/W | After approval, one real Hume measurement uploaded to Garmin; manual readback verifies field survival. |
| E2E-04 | R | Avoid Garmin-exported measurements looping back into Garmin through origin tracking and idempotence. |
| E2E-05 | R | Persist a redacted multi-provider report in S3, read back using scoped agent role, and identify all unsupported/unverified edges with evidence IDs. |
| E2E-06 | G | Offline/permission/reboot/auth-expiry recovery without loss of local evidence or duplicate uploads. |
| E2E-07 | R | Preserve original vendor semantics and uncertainty (e.g. Hume body fat estimates vs actual weight), never infer accuracy from exportability. |
| E2E-08 | G | One Alpha workout → Health Connect segment-level field crosswalk and CSV field crosswalk; show **reps/exercise/load coverage separately**. |
| E2E-09 | G/W | Approved Alpha workout → offline FIT Activity → Garmin manual import → Garmin FIT export/readback. Prove or falsify set-level fidelity. |
| E2E-10 | G | Alpha workout recorded simultaneously on Garmin: detect duplicates and determine if HR/calories can be combined without mutating original Garmin activity. |
| E2E-11 | R | Validate that source IDs/timestamps/fingerprints are sufficient to dedupe and identify corrections across CSV snapshots and HC activity records. |
| E2E-12 | R | Publish an agent-readable capability matrix that names next missing authorization or information, without assuming a production sync path exists. |
| E2E-13 | R | **Secondary:** Produce an all-source data-path matrix (origin, available data, permission/auth, transformation, destination, readback and missing linkage) without adding a production synchronization engine. |
| E2E-14 | G | **Optional:** Alpha next-session plan export → local prescribed-set/exercise-mapping preview → loss report; no Garmin write required for this milestone. |
| E2E-15 | G/W | **Optional:** Approved planned Alpha workout → Garmin workout creation/readback → fēnix reception and execution, with no duplicate or overwrite of an existing routine. |

## 4. Diagnostic outputs

Produce five logical **sanitized S3 report sections** per completed run (initially one JSON object; the optional fifth section may be absent):

- `assay_report`: each probe status, prerequisites, elapsed time, safe error classification, source method and evidence ID.
- `attribute_matrix` (**primary**): Hume → Health Connect/direct export → FIT Weight Scale → Garmin acceptance/storage/UI/readback, per field, with exact definitions (lean mass ≠ skeletal muscle mass; body-water % ≠ water mass).
- `integration_matrix` (**secondary**): per-source acquisition, permissions, time ranges, provenance, change/deletion semantics, downstream reachability and absence of feedback loops.
- `authorization_matrix`: who authenticates, protocol, permissions/scopes, background/history availability, token handling, supported-vs-unofficial status, and unresolved capabilities.
- `strength_matrix` (**optional**): Alpha completed history and planned Strength workouts, including exercise identity/targets and fēnix readback, as **separately gated sections**. A skipped optional section cannot invalidate core results.

Example **body-composition** row (illustrative; never presume supported):

```json
{
  "field": "hume.body_fat_percent",
  "source_semantics": "Hume-estimated fat percentage",
  "hume_app": "OBSERVED",
  "health_connect": "UNTESTED",
  "garmin_fit_weight_scale": "ENCODABLE",
  "garmin_import": "UNTESTED",
  "garmin_readback": "UNTESTED",
  "garmin_ui": "UNTESTED",
  "evidence": ["HM-02", "HM-03", "HC-09", "GA-10"]
}
```

**Critical:** `ENCODABLE` does not imply `ACCEPTED`; `ACCEPTED` does not imply `PERSISTED`; `PERSISTED` does not imply `DISPLAYED` or re-exported. For authentication, distinguish `PUBLIC_DOC`, `APP_PERMISSION`, `MANUAL_EXPORT`, `UNOFFICIAL_PRIVATE_API`, `NOT_DOCUMENTED` and `NOT_AUTHORIZED`.

## 5. Privacy and safety boundaries

1. **Default cloud evidence is metadata only.** Never upload raw Alpha CSV, actual body composition measurements, granular HR, raw FIT files, routes, notes, Hume BLE packets, Garmin session tokens or client refresh tokens. A future separately consented archive may be designed, but logs cannot silently become that archive.
2. **No automatic vendor account mutations in the pilot.** Garmin FIT body-measurement imports and optional workout creation require a review of account, timestamp, exact fields and expected output. Avoid test uploads that create fictitious health/activity records; never replace existing Garmin routines or activities without separate approval.
3. **Use only permitted, user-visible acquisition.** Health Connect uses Android-granted permissions; Alpha uses official manual CSV/HC until an authorized API exists; Hume BLE requires explicit opt-in and safe reads only; Garmin private API testing is gated and does not brute-force MFA or bypass platform authentication.
4. **No credential delegation to agents.** Agents inspect redacted S3 artifacts; they do not receive mobile Cognito tokens, Alpha/Hume account passwords, Garmin cookies, device pairing keys or a general AWS write identity.
5. **Don't invent value equivalence.** A strength workout may be "exported" by Alpha as one summary without any reps; an assisted pull-up load is not the same as a loaded curl; a Health Connect exercise segment weight feature may exist but not be written by the installed Alpha version.
6. **Failure isolation and priority matter.** Missing Alpha permissions/exports, unsupported Garmin Workout APIs or optional vendor capabilities cannot block Hume→Garmin composition experiments or the general data-integration report. Reports must distinguish permission failure, unsupported data, absent samples and deferred side quests.

## 6. Build phases and decision gates (priority-preserving)

**A — AWS evidence loop.** Build the small Cognito-PKCE Android login, JWT-authenticated Lambda `POST /runs`, private S3 report write, and scoped agent read access. Verify redaction and local outbox retries. This is enabling infrastructure, not a standalone source of truth.

**B — Hume acquisition and body-composition inventory (primary).** Run `HM` and body-related `HC` assays first: field inventory from Hume UI vs Health Connect, units, source metadata, lag, update behavior, manual export options and safely gated BLE discovery. Identify *every available metric* and the exact missing acquisition boundaries. Do **not** wait for Alpha integration.

**C — Garmin body-composition ingestion and readback (primary).** Build offline FIT Weight Scale fixtures and independent decoders; investigate allowed Garmin authentication/import routes. With an explicit user-approved *real* Hume measurement, test import, re-export/readback, display, units, precision, chronology and deduplication. Iterate per field only when authorized. Produce the definitive Hume→Garmin matrix with `DEFINED`, `AVAILABLE`, `ENCODABLE`, `ACCEPTED`, `RETAINED`, `DISPLAYED` and `BLOCKED` states.

**D — General health-data integration analysis (secondary).** Run `GI` and the broader `HC` probes: record families, source origins, historical/background permissions, association of related records, correction/deletion, synchronization loops, and authentication constraints. Return a general integration capabilities and failure-mode matrix, without creating an operational data platform.

**E — Hardening and core report.** Verify interruption/retry, partial failure isolation, S3 immutable reports, agent-readable retrieval, revoked permissions, auth-expiration handling and privacy tests. Publish the core report while explicitly marking optional work `DEFERRED` or `SKIPPED` if not run.

**F — Alpha Progression side quest (optional; independent).** Reuse the same diagnostic harness for `AP`, `GS` and `GW`: Alpha completed history → Garmin FIT Activity, *and* Alpha prescribed plan → Garmin executable Strength Workout. Investigate static export vs adaptive targets; test Garmin catalog mapping, permitted creation/readback and watch delivery. Any approved account write is a separate confirmation. Optional findings append to the evidence report without changing the architecture or core exit criteria.

**Core pilot complete when:** an authorized AWS agent can retrieve a redacted S3 report explaining (a) exactly which Hume body-composition fields can be acquired and under what permissions, (b) what Garmin accepts, persists and presents for each, and (c) general Health Connect/vendor read-write provenance, authorization, failure/retry and loss boundaries. A well-evidenced *unsupported* or *blocked* Hume→Garmin field is a valid finding. **Alpha planned/completed workout transfer is not a core completion requirement.**

## 7. Future AWS system-of-record seam — deliberately unimplemented

Keep a narrow internal abstraction between **source acquisition**, **source-native observation**, **transformation**, and **destination delivery**. The pilot stores only diagnostic metadata in S3, but should keep stable source IDs and correction events locally so a future independently authorized **source-native archival stream** could write to another private S3 prefix or bucket.

Future-only observation envelope (do **not** send in current S3 diagnostic reports):

```json
{
  "schema_version": 1,
  "origin": "hume_body_pod_health_connect",
  "source_record_id": "<stable-if-available>",
  "content_sha256": "<canonical-source-value-hash>",
  "observed_at": "<UTC>",
  "effective_at": "<instant or timezone-aware local time>",
  "operation": "upsert|delete",
  "original_units": "<source-units>",
  "original_payload": "<encrypted, only with separate opt-in>"
}
```

Do not assume S3 query mechanisms, SQL databases, normalized health schemas, periodic APIs or long-term retention now. A future AWS source of truth can be added behind the same redacted diagnostic/report protocol without moving current measurement normalization into the Lambda.

## 8. Known unknowns and technical references

**Known unknowns:**
- Alpha officially supports manual **CSV export** and Health Connect, but **not a public API**. Background set-level extraction is unproven. Its CSV might need nontrivial normalization, and third-party observations describe timezone-less wall-clock dates. Do not assume Alpha exports `ExerciseSegment.weight` simply because Health Connect now has that field.
- Health Connect segment weights/set indices depend on Android platform extension and AndroidX library version; report capability for both runtime and actual source data.
- **Primary:** Hume full body composition fields may not be present in Health Connect, and a public direct Hume API is not verified. Garmin's FIT Weight Scale profile defines fields, but actual import, storage and display require direct tests.
- **Optional:** Garmin FIT supports completed **Activity** and planned **Workout** as separate file types. A structurally valid FIT file can still be rejected by Garmin Connect or lose set details. Imported history need not appear as a usable watch workout or update watch-derived training metrics. Garmin's official Training API requires developer approval; consumer workout APIs are unofficial.
- This is a conscious small exception to Ahara's API transport standard; before implementation, obtain approval for API Gateway versus shared ALB and review incremental AWS pricing. No database, TrueNAS or external telemetry stack should be added as a workaround.

**Principal references:**
- [Alpha Progression complete user guide / exports / HC](https://alphaprogression.com/en/blog/alpha-progression-guide) and [Alpha no-public-API statement](https://alphaprogression.com/en/blog/alpha-progression-vs-gravl).
- [AndroidX `ExerciseSegment` reference](https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/ExerciseSegment) and [platform extension availability](https://developer.android.com/reference/android/health/connect/datatypes/ExerciseSegment.Builder).
- [Garmin FIT SDK and file types](https://developer.garmin.com/fit/overview/), [FIT Activity file](https://developer.garmin.com/fit/articles/file-types/activity.html), [FIT Workout file](https://developer.garmin.com/fit/articles/file-types/workout.html), [Garmin manual activity import](https://support.garmin.com/en-CA/?faq=Ht3ZP52Kju075uKvqTqu99), [Garmin third-party import limitations](https://support.garmin.com/en-US/?faq=j3epr66mmJ0V26Yq0SO6IA), [FIT strength developer discussion](https://forums.garmin.com/developer/fit-sdk/f/discussion/270009/examples-for-encoding-strength-training-activity-files/1728662).
- [Android Health Connect records](https://developer.android.com/health-and-fitness/health-connect/data-types), [Health Connect access](https://developer.android.com/health-and-fitness/health-connect/read-data).
- [Cognito authorization-code PKCE](https://docs.aws.amazon.com/cognito/latest/developerguide/using-pkce-in-authorization-code.html), [API Gateway JWT authorizer](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-jwt-authorizer.html).
- [Ahara deployment standards](https://github.com/chris-arsenault/ahara/blob/main/INTEGRATION.md), [Ahara infrastructure cost guardrails](https://github.com/chris-arsenault/ahara-infra/blob/main/AGENTS.md).
- [HC2Garmin source](https://github.com/gsaw0/hc2garmin) is a protocol and failure-mode reference only; no visible license was found in the prior code review, so do not copy code without clarifying reuse rights.
