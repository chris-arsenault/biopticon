use async_trait::async_trait;
use aws_sdk_s3::{Client, primitives::ByteStream};
use biopticon_probe::{EventStore, IdentityPolicy, IngestError, authorize, ingest};
use lambda_http::{Body, Error, Request, RequestExt, Response, run, service_fn};
use std::sync::Arc;
use uuid::Uuid;

struct S3Store {
    client: Client,
    bucket: String,
}

#[async_trait]
impl EventStore for S3Store {
    async fn create(&self, key: &str, bytes: &[u8]) -> Result<(), IngestError> {
        let result = self
            .client
            .put_object()
            .bucket(&self.bucket)
            .key(key)
            .if_none_match("*")
            .content_type("application/json")
            .body(ByteStream::from(bytes.to_vec()))
            .send()
            .await;
        match result {
            Ok(_) => Ok(()),
            Err(error)
                if error
                    .as_service_error()
                    .is_some_and(|e| e.meta().code() == Some("PreconditionFailed")) =>
            {
                let existing = self
                    .client
                    .get_object()
                    .bucket(&self.bucket)
                    .key(key)
                    .send()
                    .await
                    .map_err(|_| IngestError::Storage)?;
                let content = existing
                    .body
                    .collect()
                    .await
                    .map_err(|_| IngestError::Storage)?;
                if content.into_bytes().as_ref() == bytes {
                    Ok(())
                } else {
                    Err(IngestError::Conflict)
                }
            }
            Err(_) => Err(IngestError::Storage),
        }
    }
}

struct State {
    store: S3Store,
    policy: IdentityPolicy,
}
fn required(name: &str) -> Result<String, Error> {
    std::env::var(name).map_err(Into::into)
}

#[tokio::main]
async fn main() -> Result<(), Error> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let config = aws_config::load_defaults(aws_config::BehaviorVersion::latest()).await;
    let state = Arc::new(State {
        store: S3Store {
            client: Client::new(&config),
            bucket: required("EVIDENCE_BUCKET")?,
        },
        policy: IdentityPolicy {
            issuer: required("COGNITO_ISSUER")?,
            client_id: required("COGNITO_CLIENT_ID")?,
            target_group_arn: required("TARGET_GROUP_ARN_PREFIX")?,
        },
    });
    run(service_fn(move |request| {
        let state = state.clone();
        async move { handle(request, state).await }
    }))
    .await
}

async fn handle(request: Request, state: Arc<State>) -> Result<Response<Body>, Error> {
    let started = std::time::Instant::now();
    if request.method() == "GET" && request.uri().path() == "/health" {
        return reply(200, "ok");
    }
    let context = serde_json::to_value(request.request_context())?;
    let group = context
        .pointer("/elb/targetGroupArn")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let bearer = request
        .headers()
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .unwrap_or("");
    if authorize(bearer, group, &state.policy).is_err() {
        return reply(401, "unauthorized");
    }
    let path: Vec<_> = request.uri().path().trim_matches('/').split('/').collect();
    if request.method() != "PUT"
        || path.len() != 5
        || path[0] != "v1"
        || path[1] != "runs"
        || path[3] != "events"
    {
        return reply(404, "not_found");
    }
    let (Ok(run_id), Ok(event_id)) = (Uuid::parse_str(path[2]), Uuid::parse_str(path[4])) else {
        return reply(400, "invalid_id");
    };
    let bytes = match request.body() {
        Body::Text(s) => s.as_bytes(),
        Body::Binary(b) => b,
        Body::Empty => &[],
    };
    let (status, code) = match ingest(&state.store, run_id, event_id, bytes).await {
        Ok(()) => (200, "persisted"),
        Err(IngestError::TooLarge) => (413, "event_too_large"),
        Err(IngestError::Conflict) => (409, "event_conflict"),
        Err(IngestError::Unauthorized) => (401, "unauthorized"),
        Err(IngestError::InvalidEvent) => (400, "invalid_event"),
        Err(IngestError::Storage) => (503, "storage_unavailable"),
    };
    eprintln!(
        "{}",
        serde_json::json!({"service":"biopticon-probe","run_id":run_id,"event_id":event_id,"result":code,"duration_ms":started.elapsed().as_millis()})
    );
    reply(status, code)
}

fn reply(status: u16, code: &str) -> Result<Response<Body>, Error> {
    Ok(Response::builder()
        .status(status)
        .header("content-type", "application/json")
        .body(Body::Text(serde_json::json!({"code": code}).to_string()))?)
}
