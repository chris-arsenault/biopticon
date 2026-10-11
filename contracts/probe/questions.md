# Phone investigation questions

This is the current phone campaign source. IDs retain links to the original research, but the questions describe the current probe. `R` identifies read-only investigation, `G` needs a phone action, and `W` involves a separately approved vendor write. Recording a result never executes or authorizes that write. The APK shows each question's implementation boundary and prerequisites.

Build checks, synthetic parser/encoder tests and AWS infrastructure checks belong in CI and deployment verification. They are not phone investigation tasks. The original design remains in `research-input.md` for reference; it does not define the packaged campaign.

| ID | Mode | Question |
|---|---|---|
| P-01 | R | Inventory Android, SDK and Health Connect versions, model family, locale, timezone and visible Hume/Garmin app versions. |
| P-02 | G | Compare the phone clock with a trusted reference and inspect source timestamp precision and timezone behavior. |
| P-03 | G | Restart or terminate the probe and verify that committed observations remain inspectable and incomplete runs are identified. |
| P-04 | G | Reboot, force-stop, change networks or enter battery saver; observe queued-event recovery and background scheduling delays. |
| P-05 | R | Inspect granted permissions against the selected read types; verify that unrelated probes continue after a denial. |
| P-06 | G | Run with status delivery unavailable, inspect local observations, reconnect and verify that queued events reach S3. |
| P-07 | G | Select a source CSV/FIT through the system picker and test URI permission availability and revocation. |
| HC-01 | R | Check Health Connect availability, SDK status and runtime feature flags. |
| HC-02 | G | Grant, partially grant, deny and revoke selected read permissions; observe each affected record type. |
| HC-03 | R | Read each granted relevant type over 7-day and 30-day windows; record counts, origins and query outcomes. |
| HC-04 | G | Grant history access and test reads beyond 30 days. |
| HC-05 | G | Grant background access, close the UI during a campaign and inspect actual read outcomes and execution context. |
| HC-06 | R | Vary page sizes and inspect terminal page tokens, pagination limits and quota errors. |
| HC-07 | R | Compare unfiltered and per-origin reads; distinguish the source app from the physical recording device. |
| HC-08 | R | Inspect available record metadata: IDs, versions, modification time, recording method, device and origin. Keep identifiers local. |
| HC-09 | R | Discover accessible body measurement fields, types, units and presence across weight, fat, bone, lean mass, water, height and basal metabolism. |
| HC-10 | R | Discover enabled exercise, steps, distance, calories, heart rate, elevation, speed and sleep fields and associations. |
| HC-11 | G | Request exercise route access separately; distinguish an absent route from denied permission without uploading coordinates. |
| HC-12 | G | For a Garmin hike, inspect whether elevation records can be associated with its exercise session by time and source. |
| HC-13 | R | Compare raw records and aggregate totals on overlapping sources; inspect source priority and possible double counting. |
| HC-14 | G | After a real Hume weigh-in, repeat bounded reads at selected intervals and observe arrival and modification timing. |
| HC-15 | G | After a real repeat measurement or correction, inspect identity, version, update, deletion and late-arrival behavior. |
| HC-16 | R | Test changes-token creation and subsequent reads; inspect expiry, pagination and recovery requirements. |
| HC-17 | G | Inspect source units and missing or sentinel fields in an actual sample; compare conversions locally. |
| HC-18 | R | Distinguish readable empty types from unavailable or permission-denied types. |
| HC-19 | G | Revoke permissions, restart and regrant; observe partial operation and recovery. |
| HC-20 | R | Inspect write permissions and observed data origins; identify possible export loops without writing records back. |
| HC-21 | R | Inspect Alpha-origin exercise segments for repetitions, indices, weight, RPE, timing and absent fields. |
| HC-22 | R | Compare SDK/runtime feature availability with observed segment fields to separate version limits from source omissions. |
| HC-23 | G | Complete a real Alpha workout and compare its UI with newly available Health Connect sessions and segments. |
| HC-24 | R | Distinguish permission-denied queries from successful empty queries for the selected types. |
| HC-25 | G | Edit a completed Alpha workout and observe whether Health Connect segments update, replace or remain unchanged. |
| HM-01 | R | Discover the installed Hume version and actual Health Connect origins; installation alone does not establish export support. |
| HM-02 | G | Inventory fields displayed by Hume: names, units, precision, derived scores and whole-body or segmental scope. |
| HM-03 | G | Compare each Hume-displayed field with obtainable Health Connect fields and identify missing acquisition paths. |
| HM-04 | G | Compare a real Body Pod scan with its Health Connect representation, timestamp, version and arrival latency locally. |
| HM-05 | G | Compare two real scans before and after Hume sync for changing fields, rounding and export behavior. |
| HM-06 | G | If desired, sign out of Hume or go offline and observe which functions survive and recover on reconnect. |
| HM-07 | G | Inspect export/share mechanisms in the installed Hume app and the structure of actual obtainable exports. |
| HM-08 | G | Investigate documented Hume Health access and authentication options; distinguish undocumented access from demonstrated failure. |
| HM-09 | G | Scan near the selected scale and inspect advertisement service UUIDs, manufacturer-data types and discovery timing. |
| HM-10 | G | With Hume disconnected, enumerate the selected scale's GATT services, characteristics, properties and connection errors. |
| HM-11 | G | After service definitions are known, test permitted characteristic reads and distinguish readable, notify-only, encrypted or pairing-required interfaces. |
| HM-12 | G | If needed, collect an explicitly selected local Bluetooth diagnostic capture for a focused follow-up investigation. |
| HM-13 | G | With an established authorized Hume cloud route, inspect authentication, field inventory, history, pagination and errors. |
| HM-14 | G | Identify each Hume body-composition field's observable source, units and provenance without assuming equivalent measurements. |
| GA-01 | R | Inventory Garmin Connect version and visible local connection state; distinguish it from cloud authentication. |
| GA-02 | G | Investigate actual Garmin import/export interfaces and their documented or unofficial access requirements. |
| GA-03 | G | Using an established authorized Garmin authentication route, observe login, MFA, issuance, refresh, expiry, logout and errors without recording secrets. |
| GA-07 | G/W | With separate approval of the account, timestamp and fields, attempt one real Hume-derived measurement import. |
| GA-08 | G/W | During an approved import, observe transport response, interface, acceptance and time to visibility. |
| GA-09 | G | Read an imported measurement through UI, export or an authorized interface; distinguish acceptance, storage and display. |
| GA-10 | G/W | With separately approved real inputs, test which body-composition fields Garmin retains, rounds, ignores or exposes. |
| GA-11 | G | Observe whether imported weight or body fat reaches the watch profile and how long it takes. |
| GA-12 | G | Observe whether Garmin re-exports imported body measurements to Health Connect and identify their origins. |
| GA-13 | G/W | With approval for a repeated import, inspect duplicate/conflict responses and stored record counts. |
| GA-14 | G/W | With an approved correction case, observe overwrite, coexistence and backdated-import behavior. |
| GA-15 | G | With an established vendor auth route, inspect expired/revoked sessions, challenges and HTTP errors using bounded requests. |
| GA-16 | G | Determine which submitted FIT fields Garmin actually accepts, retains, exposes and re-exports. |
| GA-17 | G | Inspect timestamp precision, timezone handling, backfill ordering and source attribution in actual Garmin results. |
| GA-18 | G/W | If separately approved, test a proprietary developer field and observe retention and display without assuming support. |
| GI-01 | R | Inventory source apps and data paths actually visible on this phone. |
| GI-02 | G | Trace a selected attribute's source, intermediate type/origin and destination behavior; record each unknown boundary. |
| GI-03 | G | Inspect independent record timelines and whether related records can be associated by time and origin. |
| GI-04 | G | For a selected provider interface, observe authentication challenges, methods and authorized login stages. |
| GI-05 | G | Compare repeated source reads or exports after a correction, delay or deletion; inspect identities, fingerprints and query limits. |
| AP-01 | R | Discover Alpha's installed version and actual Health Connect origins. |
| AP-02 | R | Inventory Alpha-origin exercise sessions and distinguish summaries from set-level fields. |
| AP-03 | R | Inspect Alpha exercise segment fields and distinguish missing data from inaccessible SDK/runtime features. |
| AP-04 | G | Complete a real Alpha workout and compare its UI with Health Connect session and segment fields. |
| AP-05 | G | Select an actual Alpha CSV export and inspect headers, row counts, encoding and structural types. |
| AP-06 | G | Inspect actual Alpha exports across available locales for delimiters, quoting, multiline fields, decimal separators and units. |
| AP-07 | G | Determine whether export fields describe performed, planned or recommended sets and identify set categories. |
| AP-08 | G | Inspect actual export fields for exercise identity, equipment, order, session IDs, set indices and RIR. |
| AP-09 | G | Compare actual examples of reps, load, assistance, duration and distance exercise modes and their units. |
| AP-10 | G | Determine whether displayed/exported load is total, per-hand, added bodyweight or assistance. |
| AP-11 | G | Compare one completed workout across Alpha UI, CSV and Health Connect and identify missing fields. |
| AP-12 | G | Inspect actual export timezone/precision and compare midnight, DST and multiple-session behavior locally. |
| AP-13 | G | Export the same history twice and compare identities, ordering and structural/content fingerprints locally. |
| AP-14 | G/W | With an approved real edit/deletion, re-export and inspect correction, snapshot and Health Connect update behavior. |
| AP-15 | G | Investigate authorized unattended export or API access; identify when a manual export remains necessary. |
| AP-16 | G | If desired, test Alpha offline/sign-in behavior and whether exports include locally stored history. |
| AP-17 | G | Inspect presence and representation of notes, RIR, rest, actual/target volume and custom exercises without uploading note contents. |
| AP-18 | G | Compare exercise renames, collisions, aliases and languages with actual Garmin exercise identities. |
| AP-19 | G | Inspect Alpha's system share/document-provider options and whether the probe can select its export. |
| AP-20 | G | Compare selected Alpha fields across UI, CSV and Health Connect and record supported, missing and blocked stages. |
| GS-01 | G | Select an actual Garmin strength FIT export and inspect message definitions, set fields and timing locally. |
| GS-02 | G | Inspect fields present in an actual Garmin strength export, including vendor/developer fields and unknown definitions. |
| GS-05 | G/W | With separate approval, import one real completed Alpha workout through an authorized Garmin interface. |
| GS-06 | G | Inspect imported Garmin strength activity fields: exercises, order, reps, load, rest, duration and summary. |
| GS-07 | G | Re-export an imported Garmin activity and compare submitted and retained fields locally. |
| GS-08 | G | Observe whether Garmin sends an imported activity to the watch. |
| GS-09 | G | Compare actual Alpha exercise names with Garmin catalog entries, including custom and multilingual names. |
| GS-10 | G | Inspect retained semantics for assistance, per-hand load, bodyweight, timed sets, RIR and supersets. |
| GS-11 | G | Compare a watch-recorded and Alpha-recorded example of the same session for overlap and duplicate identity. |
| GS-12 | G | Investigate whether an authorized non-destructive interface can associate sets with an existing Garmin activity. |
| GS-13 | G/W | If separately approved, import a replacement test activity without modifying the original and inspect duplication and field loss. |
| GS-14 | G/W | After manual import is understood, test an established authorized upload route with separate write approval. |
| GS-15 | G/W | With approval for each real write, repeat a source/import case and inspect Garmin duplicate/conflict behavior. |
| GS-16 | G/W | With an approved real case, inspect backdated/timezone/session-edit handling and set chronology. |
| GS-17 | G | Observe imported activity effects on Garmin training metrics, records and Health Connect re-export. |
| GS-19 | G | Inspect Garmin interface/session requirements, refresh and MFA using an established authorized route. |
| GS-20 | G | For selected strength fields, observe submission, retention, display, re-export and watch receipt as separate stages. |
| GW-01 | G | Select an actual Alpha plan export and inspect routine, exercise, set-target, rest and identity fields. |
| GW-02 | G | Compare an exported plan with Alpha's displayed next session and identify adaptive targets that are missing. |
| GW-03 | G | Inspect Garmin's actual workout catalog and a manually created example to compare exercise identities and target fields. |
| GW-05 | G | Investigate authorized Garmin planned-workout import routes and their authentication requirements. |
| GW-06 | G/W | With separate approval, create one disposable Garmin strength workout and inspect retained identities and targets. |
| GW-07 | G | Send the approved workout to the watch and inspect sequence, targets and startability. |
| GW-08 | G/W | With separate approval, update the same test workout and inspect duplication, retention and device propagation. |
| GW-09 | G | Compare a selected plan field across acquisition, mapping, Garmin retention and watch execution. |
| E2E-01 | G | Trace one real Hume scan through its displayed fields and Health Connect representation. |
| E2E-02 | G | Compare a selected Hume field with submitted FIT fields and actual Garmin retention/display/readback. |
| E2E-03 | G/W | With separate approval, import one real Hume measurement and inspect Garmin readback. |
| E2E-04 | G | Inspect origins and identities of Garmin re-exported measurements to identify possible repeated-import loops. |
| E2E-06 | G | Exercise offline, permission, restart and session-expiry recovery and inspect observation preservation and delivery. |
| E2E-07 | G | Compare source and destination field definitions and record uncertainty instead of assuming semantic equivalence. |
| E2E-08 | G | Compare exercise, reps and load separately across an actual Alpha workout, Health Connect and CSV export. |
| E2E-09 | G/W | With separate approval, import a real Alpha workout into Garmin and inspect its actual FIT re-export. |
| E2E-10 | G | Compare overlapping Alpha/watch recordings and investigate association options without modifying the original. |
| E2E-11 | G | Compare repeated real exports/reads to determine whether available IDs and fingerprints distinguish duplicates and corrections. |
| E2E-14 | G | Inspect an Alpha next-session export and compare prescribed sets and exercise identities with Garmin's representation. |
| E2E-15 | G/W | With separate approval, create a planned Garmin workout from a real Alpha plan and inspect watch receipt and execution. |
