use async_trait::async_trait;
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

pub const MAX_EVENT_BYTES: usize = 4096;

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Finding {
    pub kind: String,
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub count: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub flag: Option<bool>,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Event {
    pub schema_version: u32,
    pub event_id: Uuid,
    pub run_id: Uuid,
    pub attempt_id: Uuid,
    pub build: String,
    pub campaign: String,
    pub probe_id: String,
    pub case_id: String,
    pub target: String,
    pub operation: String,
    pub occurred_at: String,
    pub status: String,
    pub reason_code: String,
    pub duration_ms: u64,
    pub findings: Vec<Finding>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum IngestError {
    Unauthorized,
    InvalidEvent,
    TooLarge,
    Conflict,
    Storage,
}

pub fn validate_event(bytes: &[u8], run: Uuid, event: Uuid) -> Result<Event, IngestError> {
    if bytes.len() > MAX_EVENT_BYTES {
        return Err(IngestError::TooLarge);
    }
    let parsed: Event = serde_json::from_slice(bytes).map_err(|_| IngestError::InvalidEvent)?;
    let status_valid = [
        "PASS",
        "FAIL",
        "BLOCKED_PERMISSION",
        "BLOCKED_AUTH",
        "NO_DATA",
        "UNSUPPORTED",
        "INCONCLUSIVE",
        "SKIPPED",
        "STARTED",
        "FINISHED",
        "INTERRUPTED",
    ]
    .contains(&parsed.status.as_str());
    let labels = [
        &parsed.build,
        &parsed.campaign,
        &parsed.probe_id,
        &parsed.case_id,
        &parsed.target,
        &parsed.operation,
        &parsed.reason_code,
    ];
    if parsed.schema_version != 1
        || parsed.run_id != run
        || parsed.event_id != event
        || !status_valid
        || parsed.occurred_at.len() > 40
        || !parsed.occurred_at.ends_with('Z')
        || time::OffsetDateTime::parse(
            &parsed.occurred_at,
            &time::format_description::well_known::Rfc3339,
        )
        .is_err()
        || labels.iter().any(|s| !label(s, 128))
        || parsed.findings.iter().any(|f| !valid_finding(f))
    {
        return Err(IngestError::InvalidEvent);
    }
    Ok(parsed)
}

fn label(text: &str, max: usize) -> bool {
    !text.is_empty() && text.chars().count() <= max && !text.chars().any(char::is_control)
}

fn valid_finding(finding: &Finding) -> bool {
    [
        "attribute",
        "protocol",
        "permission",
        "counter",
        "capability",
        "error",
        "metadata",
    ]
    .contains(&finding.kind.as_str())
        && label(&finding.name, 160)
        && finding.text.as_ref().is_none_or(|s| label(s, 256))
        && [
            finding.text.is_some(),
            finding.count.is_some(),
            finding.flag.is_some(),
        ]
        .iter()
        .filter(|&&v| v)
        .count()
            == 1
}

#[derive(Clone)]
pub struct IdentityPolicy {
    pub issuer: String,
    pub client_id: String,
    pub target_group_arn: String,
}

#[derive(Deserialize)]
struct Claims {
    iss: String,
    client_id: String,
    token_use: String,
}

pub fn authorize(
    token: &str,
    target_group: &str,
    policy: &IdentityPolicy,
) -> Result<(), IngestError> {
    // The source-restricted ALB validates the JWT signature and time claims.
    // Only its trusted request context permits interpretation of these claims.
    let trusted = if policy.target_group_arn.ends_with('/') {
        target_group
            .strip_prefix(&policy.target_group_arn)
            .is_some_and(|id| id.len() == 16 && id.bytes().all(|c| c.is_ascii_hexdigit()))
    } else {
        target_group == policy.target_group_arn
    };
    if !trusted {
        return Err(IngestError::Unauthorized);
    }
    let parts: Vec<_> = token.split('.').collect();
    if parts.len() != 3 {
        return Err(IngestError::Unauthorized);
    }
    let bytes = URL_SAFE_NO_PAD
        .decode(parts[1])
        .map_err(|_| IngestError::Unauthorized)?;
    let claims: Claims = serde_json::from_slice(&bytes).map_err(|_| IngestError::Unauthorized)?;
    if claims.iss != policy.issuer
        || claims.client_id != policy.client_id
        || claims.token_use != "access"
    {
        return Err(IngestError::Unauthorized);
    }
    Ok(())
}

#[async_trait]
pub trait EventStore: Send + Sync {
    /// Create once. An existing identical object succeeds; changed content conflicts.
    async fn create(&self, key: &str, bytes: &[u8]) -> Result<(), IngestError>;
}

pub async fn ingest(
    store: &impl EventStore,
    run: Uuid,
    event: Uuid,
    bytes: &[u8],
) -> Result<(), IngestError> {
    validate_event(bytes, run, event)?;
    let key = format!("diagnostics/owner/runs/{run}/events/{event}.json");
    store.create(&key, bytes).await
}
