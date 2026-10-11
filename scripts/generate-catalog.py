#!/usr/bin/env python3
"""Package current phone questions with their actual implementation boundaries."""
import json
import re
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AUTOMATIC = {
    "P-01": "inventory", "P-03": "recovery", "P-05": "permissions", "P-06": "delivery",
    "P-07": "artifact",
    "HC-01": "health", "HC-03": "health", "HC-06": "health", "HC-07": "health",
    "HC-08": "health", "HC-09": "health", "HC-10": "health", "HC-16": "health",
    "HC-18": "health", "HC-20": "health", "HC-21": "health", "HC-22": "health", "HC-24": "health",
    "HM-01": "inventory", "HM-03": "guided-crosswalk", "HM-08": "research",
    "HM-09": "ble-scan", "HM-10": "ble-gatt", "HM-11": "ble-gatt",
    "GA-01": "inventory", "GA-02": "research", "GA-15": "protocol", "GA-16": "guided-readback",
    "GI-01": "inventory", "GI-04": "protocol",
    "AP-01": "inventory", "AP-02": "health", "AP-03": "health", "AP-05": "artifact",
    "AP-06": "artifact", "AP-07": "artifact", "AP-08": "artifact", "AP-09": "artifact",
    "AP-10": "guided-semantics", "AP-12": "artifact", "AP-17": "artifact", "AP-18": "guided-mapping",
    "GS-01": "artifact", "GS-02": "artifact", "GS-19": "protocol",
    "GW-01": "artifact", "GW-03": "guided-mapping", "GW-05": "research",
}
AUTOMATIC.update({"HC-13": "health-aggregate", "GI-05": "health-snapshot"})
SUPPORT = {
    "inventory": ("automatic_partial", "Run campaign; visible package versions and intent handlers. App account-linked state still requires inspection in its UI."),
    "health": ("automatic_partial", "Grant selected types and run campaign. Bounded reads discover SDK fields, presence and origins; real source changes/latency require repeated phone passes."),
    "health-aggregate": ("automatic_partial", "Enable activity reads. Compare seven-day raw steps with aggregation; other aggregate families require a subsequent targeted case."),
    "health-snapshot": ("automatic_partial", "Run the same selected query before and after a source change. Local identity/content hashes yield window diffs; missing records are not proof of deletion."),
    "artifact": ("owner_selected_file", "Export from the actual source app and choose CSV/FIT. Parser reports structural fields, counts and CRC; semantic comparisons require owner observations."),
    "ble-scan": ("owner_opt_in", "Grant Bluetooth permission, enable scan consent, scan 12 seconds. Select your own scale from local results; no addresses or raw packets leave the device."),
    "ble-gatt": ("conditional", "Select your scale after disconnecting Hume. First APK enumerates UUIDs/properties only; safe characteristic-specific reads require observed service definitions and a follow-up probe."),
    "protocol": ("automatic_partial", "Select known vendor endpoint; GET/HEAD/OPTIONS without credentials. Observe status/challenge/methods. Expired vendor tokens, refresh and MFA require an established authorized vendor auth route."),
    "recovery": ("local_verification_and_phone", "Build tests cover durable queue/recovery. Force-stop/offline/Doze and real S3 delivery must be observed on the phone and deployment."),
    "delivery": ("deployment_required", "Configure deployed API and Cognito client, sign in, run offline, reconnect and inspect individual S3 events. Deployment must precede this test."),
    "permissions": ("automatic_partial", "Grant only chosen read group; campaign enumerates actual grants and confirms manifest has no health writes. Revoke/regrant is an owner action."),
    "research": ("research_or_follow_up", "Read the full question and inspect official documentation or phone evidence. Not automatically executed by this APK; record exact observations using the guided stage controls."),
}


def catalog():
    source = (ROOT / "contracts/probe/questions.md").read_text()
    rows = re.findall(r"^\| ((?:P|HC|HM|GA|GI|AP|GS|GW|AW|E2E)-\d+) \| ([^|]+) \| (.+) \|$", source, re.M)
    assert rows and len({r[0] for r in rows}) == len(rows)
    assert set(AUTOMATIC) <= {row[0] for row in rows}
    output = []
    for probe, mode, question in rows:
        prefix = probe.rsplit("-", 1)[0]
        route = AUTOMATIC.get(probe, "guided" if "G" in mode or "W" in mode else "research")
        classification, action = SUPPORT.get(route, ("owner_action", "Perform the full question's specific experiment in the target app; record attribute, units and observed stage. Write cases require separate approval; none is executed by this APK."))
        output.append({"id": probe, "mode": mode.strip(), "question": question,
                       "route": route, "optional": prefix in {"AP", "GS", "GW"} or probe in {"HC-21", "HC-22", "HC-23", "HC-25", "E2E-08", "E2E-09", "E2E-10", "E2E-11", "E2E-14", "E2E-15"},
                       "account_write": "W" in mode,
                       "implementation": classification,
                       "prerequisite": action,
                       "observations": ["target/interface", "case/attempt", "operation/stage", "status/reason", "attribute/unit/presence or protocol outcome"],
                       "execution_status": "NOT_EXECUTED"})
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if the packaged catalog is stale")
    args = parser.parse_args()
    output = ROOT / "android/apps/probe/src/main/assets/assays.json"
    rendered = json.dumps(catalog(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if not output.exists() or output.read_text() != rendered:
            raise SystemExit("Packaged catalog is stale; run python3 scripts/generate-catalog.py")
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered)
