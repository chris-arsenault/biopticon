use async_trait::async_trait;
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use biopticon_probe::{EventStore, IdentityPolicy, IngestError, authorize, ingest, validate_event};
use std::{collections::HashMap, sync::Mutex};
use uuid::Uuid;

#[derive(Default)]
struct MemoryStore(Mutex<HashMap<String, Vec<u8>>>);
#[async_trait]
impl EventStore for MemoryStore {
    async fn create(&self, key: &str, bytes: &[u8]) -> Result<(), IngestError> {
        let mut data = self.0.lock().unwrap();
        match data.get(key) {
            Some(existing) if existing != bytes => Err(IngestError::Conflict),
            Some(_) => Ok(()),
            None => {
                data.insert(key.into(), bytes.into());
                Ok(())
            }
        }
    }
}

fn fixture(run: Uuid, event: Uuid) -> Vec<u8> {
    serde_json::to_vec(&serde_json::json!({
        "schema_version": 1, "event_id":event, "run_id":run, "attempt_id":Uuid::new_v4(),
        "build":"0.1.0", "campaign":"discovery-1", "probe_id":"HC-09", "case_id":"BodyFatRecord.7d",
        "target":"health_connect", "operation":"read_attribute", "occurred_at":"2026-10-10T00:00:00Z",
        "status":"BLOCKED_PERMISSION", "reason_code":"READ_PERMISSION_NOT_GRANTED", "duration_ms":12,
        "findings":[{"kind":"attribute","name":"unexpected.discovered_field","text":"Mass"}]
    })).unwrap()
}

#[tokio::test]
async fn lost_ack_retry_has_one_object_and_conflicting_retry_preserves_original() {
    let store = MemoryStore::default();
    let run = Uuid::new_v4();
    let id = Uuid::new_v4();
    let body = fixture(run, id);
    ingest(&store, run, id, &body).await.unwrap();
    ingest(&store, run, id, &body).await.unwrap();
    let mut changed: serde_json::Value = serde_json::from_slice(&body).unwrap();
    changed["status"] = "PASS".into();
    assert_eq!(
        ingest(&store, run, id, &serde_json::to_vec(&changed).unwrap()).await,
        Err(IngestError::Conflict)
    );
    let objects = store.0.lock().unwrap();
    assert_eq!(objects.len(), 1);
    assert_eq!(objects.values().next().unwrap(), &body);
}

#[tokio::test]
async fn rejects_invalid_input_before_persistence_and_uses_fixed_event_keys() {
    let store = MemoryStore::default();
    let run = Uuid::new_v4();
    let id = Uuid::new_v4();
    assert_eq!(
        ingest(&store, run, id, b"{}").await,
        Err(IngestError::InvalidEvent)
    );
    assert!(store.0.lock().unwrap().is_empty());
    let bytes = fixture(run, id);
    ingest(&store, run, id, &bytes).await.unwrap();
    assert_eq!(
        store
            .0
            .lock()
            .unwrap()
            .get(&format!("diagnostics/owner/runs/{run}/events/{id}.json")),
        Some(&bytes)
    );
}

#[test]
fn validates_discovery_fields_and_rejects_extra_payloads_duplicate_keys_and_mismatched_ids() {
    let run = Uuid::new_v4();
    let id = Uuid::new_v4();
    let body = fixture(run, id);
    assert!(validate_event(&body, run, id).is_ok());
    let mut grouped: serde_json::Value = serde_json::from_slice(&body).unwrap();
    grouped["findings"] = serde_json::Value::Array(
        (0..30).map(|i| serde_json::json!({"kind":"metadata", "name":format!("field_{i}"), "flag":true})).collect()
    );
    let grouped_bytes = serde_json::to_vec(&grouped).unwrap();
    assert!(grouped_bytes.len() <= biopticon_probe::MAX_EVENT_BYTES);
    assert_eq!(
        validate_event(&grouped_bytes, run, id)
            .unwrap()
            .findings
            .len(),
        30
    );
    assert!(validate_event(&body, Uuid::new_v4(), id).is_err());
    let mut value: serde_json::Value = serde_json::from_slice(&body).unwrap();
    value["raw_health_values"] = serde_json::json!([123]);
    assert!(validate_event(&serde_json::to_vec(&value).unwrap(), run, id).is_err());
    let duplicate = String::from_utf8(body)
        .unwrap()
        .replacen('{', "{\"schema_version\":1,", 1);
    assert!(validate_event(duplicate.as_bytes(), run, id).is_err());
    assert!(matches!(
        validate_event(&vec![b' '; 4097], run, id),
        Err(IngestError::TooLarge)
    ));
}

#[test]
fn accepts_authenticated_users_and_rejects_wrong_client_issuer_token_type_or_source() {
    let policy = IdentityPolicy {
        issuer: "issuer".into(),
        client_id: "client".into(),
        target_group_arn: "alb".into(),
    };
    let claims = serde_json::json!({"iss":"issuer","client_id":"client","sub":"subject","token_use":"access"});
    let token = |value: &serde_json::Value| {
        format!(
            "header.{}.signature",
            URL_SAFE_NO_PAD.encode(serde_json::to_vec(value).unwrap())
        )
    };
    assert!(authorize(&token(&claims), "alb", &policy).is_ok());
    let mut other_user = claims.clone();
    other_user["sub"] = "another-authenticated-user".into();
    assert!(authorize(&token(&other_user), "alb", &policy).is_ok());
    assert!(authorize(&token(&claims), "forged", &policy).is_err());
    for field in ["iss", "client_id", "token_use"] {
        let mut bad = claims.clone();
        bad[field] = "wrong".into();
        assert!(authorize(&token(&bad), "alb", &policy).is_err());
    }
    assert!(authorize("invalid", "alb", &policy).is_err());
    let prefix_policy = IdentityPolicy {
        target_group_arn: "arn:targetgroup/biopticon-probe-ingest-tg/".into(),
        ..policy
    };
    assert!(
        authorize(
            &token(&claims),
            "arn:targetgroup/biopticon-probe-ingest-tg/0123456789abcdef",
            &prefix_policy
        )
        .is_ok()
    );
    assert!(
        authorize(
            &token(&claims),
            "arn:targetgroup/biopticon-probe-ingest-tg/0123456789abcdeg",
            &prefix_policy
        )
        .is_err()
    );
    assert!(
        authorize(
            &token(&claims),
            "arn:targetgroup/other/0123456789abcdef",
            &prefix_policy
        )
        .is_err()
    );
}

#[test]
fn shared_contract_example_and_invalid_dates_or_ambiguous_findings() {
    let bytes = include_bytes!("../../../contracts/probe/event.example.json");
    let mut value: serde_json::Value = serde_json::from_slice(bytes).unwrap();
    let run = Uuid::parse_str(value["run_id"].as_str().unwrap()).unwrap();
    let event = Uuid::parse_str(value["event_id"].as_str().unwrap()).unwrap();
    assert!(validate_event(bytes, run, event).is_ok());
    for bad in ["Z", "2026-02-30T00:00:00Z", "2026-10-10T25:00:00Z"] {
        value["occurred_at"] = bad.into();
        assert!(validate_event(&serde_json::to_vec(&value).unwrap(), run, event).is_err());
    }
    value["occurred_at"] = "2026-10-10T00:00:00.123456789Z".into();
    value["findings"][0]["flag"] = true.into();
    assert!(validate_event(&serde_json::to_vec(&value).unwrap(), run, event).is_err());
}

#[test]
fn android_producer_contract() {
    let Ok(path) = std::env::var("BIOPTICON_ANDROID_EVENT") else {
        return;
    };
    let bytes =
        std::fs::read(path).expect("Android unit tests must emit their actual event fixture");
    let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    let run = Uuid::parse_str(value["run_id"].as_str().unwrap()).unwrap();
    let event = Uuid::parse_str(value["event_id"].as_str().unwrap()).unwrap();
    let parsed =
        validate_event(&bytes, run, event).expect("Android producer must satisfy Rust ingestion");
    assert_eq!(parsed.findings[0].name, "new_vendor_attribute");
    assert_eq!(parsed.findings.len(), 3);
    assert_eq!(parsed.findings[1].count, Some(3));
    assert_eq!(parsed.findings[2].flag, Some(true));
}
