# Biopticon

This repository currently builds the phone proxy probe only. Read BIOPTICON-PLAN.md and ../ahara/INTEGRATION.md before changing architecture.

Keep android/apps/probe separate from the future android/apps/bridge. Share concrete libraries under android/packages. Probe findings discover the future product specification; do not implement production synchronization or a health archive.

Keep Terraform in infrastructure/terraform. Deployment must use the shared Ahara workflow with its standard deployment enabled. Fix project layout/configuration to match the platform; do not add project-specific deploy jobs or scripts or change the shared workflow to accommodate a nonstandard layout.

Use ordinary platform authentication for probe delivery and existing AWS access for reading results. Do not add an owner-subject allowlist, a dedicated reader role, or manual identity inputs.

The live phone catalog comes from contracts/probe/questions.md; research-input.md is historical reference. Keep synthetic format self-tests in CI. Keep related findings together unless their serialized event exceeds the ingress limit. One durable queue serves all signed-in accounts; recording an observation executes no vendor operation and requires no approval claim.

Use native agent editing tools. Run make ci for changes. Actual phone observations and cloud deployment must be reported separately from local build/test results. Never log credentials or health measurements. Account mutations require specific owner approval.
