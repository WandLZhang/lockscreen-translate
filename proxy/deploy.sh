#!/usr/bin/env bash
# Deploy the lockscreen-translate proxy (gen2 Cloud Function).
#
# Picks the default model from the latest benchmark winner.json if present, else falls back
# to claude-sonnet-4-6. Wires an optional shared-token secret if it exists in Secret Manager.
#
# Env overrides:
#   LT_PROJECT      GCP project            (default wz-cloud-claude)
#   LT_REGION       function deploy region (default us-central1)
#   LT_MODEL        default model id       (default: winner.json, else claude-sonnet-4-6)
#   LT_GROUNDING    auto|on|off            (default auto)
#   MIN_INSTANCES   warm instances         (default 0; set 1 to kill cold-start latency, small idle cost)
#   LT_SECRET_NAME  Secret Manager secret  (default lockscreen-translate-token; skipped if absent)
set -euo pipefail
cd "$(dirname "$0")"

PROJECT="${LT_PROJECT:-wz-cloud-claude}"
REGION="${LT_REGION:-us-east4}"  # us-east4 is the org-allowed Cloud Run region (matches cloud-claude)
GROUNDING="${LT_GROUNDING:-auto}"
MIN_INSTANCES="${MIN_INSTANCES:-0}"
SECRET_NAME="${LT_SECRET_NAME:-lockscreen-translate-token}"

# Default model = latest benchmark winner, if available.
if [[ -z "${LT_MODEL:-}" ]]; then
  WINNER=$(ls -t ../benchmark/results/*/winner.json 2>/dev/null | head -1 || true)
  if [[ -n "$WINNER" ]]; then
    LT_MODEL=$(python3 -c "import json,sys;print(json.load(open('$WINNER'))['id'])")
    echo "Using benchmark winner: $LT_MODEL  (from $WINNER)"
  else
    LT_MODEL="claude-sonnet-4-6"
    echo "No winner.json found; defaulting model to $LT_MODEL"
  fi
fi

ENV_VARS="LT_PROJECT=${PROJECT},LT_MODEL=${LT_MODEL},LT_GROUNDING=${GROUNDING}"

SECRET_FLAG=()
if gcloud secrets describe "$SECRET_NAME" --project "$PROJECT" >/dev/null 2>&1; then
  echo "Wiring shared-token auth from secret: $SECRET_NAME"
  SECRET_FLAG=(--set-secrets "LT_SHARED_TOKEN=${SECRET_NAME}:latest")
else
  echo "WARNING: secret '$SECRET_NAME' not found — deploying WITHOUT auth (open endpoint)."
  echo "  Create one with:  printf 'my-strong-token' | gcloud secrets create $SECRET_NAME --data-file=- --project $PROJECT"
fi

echo "Deploying translate -> project=$PROJECT region=$REGION model=$LT_MODEL grounding=$GROUNDING min-instances=$MIN_INSTANCES"
gcloud functions deploy translate \
  --gen2 \
  --project "$PROJECT" \
  --region "$REGION" \
  --runtime python312 \
  --source . \
  --entry-point translate \
  --trigger-http \
  --allow-unauthenticated \
  --memory 512Mi \
  --timeout 120s \
  --min-instances "$MIN_INSTANCES" \
  --set-env-vars "$ENV_VARS" \
  "${SECRET_FLAG[@]}"

echo
echo "URL:"
gcloud functions describe translate --gen2 --project "$PROJECT" --region "$REGION" \
  --format='value(serviceConfig.uri)'
echo
echo "NOTE: the function's runtime service account needs roles/aiplatform.user. If calls 403,"
echo "grant it, e.g.:  gcloud projects add-iam-policy-binding $PROJECT \\"
echo "  --member=serviceAccount:\$(gcloud functions describe translate --gen2 --region $REGION --format='value(serviceConfig.serviceAccountEmail)') \\"
echo "  --role=roles/aiplatform.user"
