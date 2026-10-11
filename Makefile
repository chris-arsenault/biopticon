.PHONY: ci test lint format format-check catalog-check android-check interop lambda
ci: format-check test lint android-check interop
test:
	cargo test --locked --workspace
lint:
	cargo clippy --locked --workspace --all-targets -- -D warnings
	terraform fmt -check -recursive infrastructure/terraform
format:
	cargo fmt --all
	terraform fmt -recursive infrastructure/terraform
format-check:
	cargo fmt --all --check
catalog-check:
	python3 scripts/generate-catalog.py --check
android-check: catalog-check
	android/gradlew --no-daemon -p android :apps:probe:assembleDebug :apps:probe:lintDebug :apps:probe:testDebugUnitTest :packages:formats:test
interop:
	BIOPTICON_ANDROID_EVENT=$(CURDIR)/android/apps/probe/build/interop/event.json cargo test --locked --package biopticon-probe --test ingestion android_producer_contract
lambda:
	cargo lambda build --release --package biopticon-probe
