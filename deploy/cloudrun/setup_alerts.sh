#!/usr/bin/env bash
# Alerts and a budget for the LEGION engine (architecture review 2026-11-10, item 4).
#
# WRITTEN, NOT RUN. Billing and alerting changes are Kevin's: this script prints every
# command and runs none of them unless `--apply` is given. Idempotence is not attempted;
# a second `--apply` creates duplicates, so run it once and check the console.
#
# What it creates:
#   1. An email notification channel (ALERT_EMAIL).
#   2. Two log-based metrics:
#        legion_error_events  - Error Reporting events the engine logged (service or job):
#                               JSON lines with the ReportedErrorEvent @type (`server/legion/log.py`).
#                               NOT `severity>=ERROR`: Cloud Run marks every plain stderr line
#                               ERROR, so that would page on a job that finished fine.
#        legion_http_5xx      - any request the `legion` service answered with 5xx (request log)
#   3. Three alert policies, all to the email channel:
#        job-failure  - a `legion-worker` execution that FAILED (Cloud Run's own
#                       run.googleapis.com/job/completed_execution_count, result="failed"),
#                       which also catches a crash that never reached the logger
#        error-events - any engine error event in 5 minutes
#        http-5xx     - more than 5 in 5 minutes
#   4. A $20/month budget on the project's billing account, alerting at 50%, 90%, 100%
#      (and 100% forecast). A budget alerts; it never caps or stops anything.
#
# Usage:
#   GOOGLE_CLOUD_PROJECT=<id> ALERT_EMAIL=<address> bash setup_alerts.sh            # print only
#   GOOGLE_CLOUD_PROJECT=<id> ALERT_EMAIL=<address> bash setup_alerts.sh --apply    # run
# Optional: BILLING_ACCOUNT (else read from the project), REGION (us-south1), SERVICE (legion),
#   JOB (legion-worker), BUDGET_USD (20).
# Needs: roles/monitoring.editor, roles/logging.configWriter, roles/billing.costsManager.
set -euo pipefail

APPLY=0
[ "${1:-}" = "--apply" ] && APPLY=1

PROJECT=${GOOGLE_CLOUD_PROJECT:?set GOOGLE_CLOUD_PROJECT}
EMAIL=${ALERT_EMAIL:?set ALERT_EMAIL (where alerts are mailed; never committed)}
SERVICE=${SERVICE:-legion}
JOB=${JOB:-legion-worker}
BUDGET_USD=${BUDGET_USD:-20}

run() {
	printf '$'
	printf ' %q' "$@"
	printf '\n'
	if [ "$APPLY" = 1 ]; then "$@"; fi
}

[ "$APPLY" = 1 ] || echo "# print only: nothing below runs without --apply"

echo "# 1. notification channel"
if [ "$APPLY" = 1 ]; then
	CHANNEL=$(gcloud beta monitoring channels create --project "$PROJECT" \
		--display-name="LEGION alerts" --type=email \
		--channel-labels="email_address=$EMAIL" --format='value(name)')
	echo "channel $CHANNEL"
else
	run gcloud beta monitoring channels create --project "$PROJECT" \
		--display-name="LEGION alerts" --type=email --channel-labels="email_address=$EMAIL"
	CHANNEL="projects/$PROJECT/notificationChannels/<id printed by the command above>"
fi

echo "# 2. log-based metrics"
run gcloud logging metrics create legion_error_events --project "$PROJECT" 	--description="LEGION: Error Reporting events logged by the engine" 	--log-filter="(resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"$SERVICE\" OR resource.type=\"cloud_run_job\" AND resource.labels.job_name=\"$JOB\") AND jsonPayload.\"@type\"=\"type.googleapis.com/google.devtools.clouderrorreporting.v1beta1.ReportedErrorEvent\""
run gcloud logging metrics create legion_http_5xx --project "$PROJECT" \
	--description="LEGION: requests the service answered with 5xx" \
	--log-filter="resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"$SERVICE\" AND httpRequest.status>=500"

echo "# 3. alert policies"
TMP=$(mktemp -d)
policy() { # name metric-filter threshold
	cat >"$TMP/$1.json" <<JSON
{
  "displayName": "LEGION: $1",
  "combiner": "OR",
  "conditions": [{
    "displayName": "$1",
    "conditionThreshold": {
      "filter": "$2",
      "comparison": "COMPARISON_GT",
      "thresholdValue": $3,
      "duration": "0s",
      "aggregations": [{"alignmentPeriod": "300s", "perSeriesAligner": "ALIGN_SUM",
                        "crossSeriesReducer": "REDUCE_SUM"}]
    }
  }],
  "notificationChannels": ["$CHANNEL"],
  "alertStrategy": {"autoClose": "86400s"}
}
JSON
	run gcloud alpha monitoring policies create --project "$PROJECT" --policy-from-file="$TMP/$1.json"
}
q='\"'
policy job-failure "metric.type=${q}run.googleapis.com/job/completed_execution_count${q} AND resource.type=${q}cloud_run_job${q} AND resource.label.job_name=${q}$JOB${q} AND metric.label.result=${q}failed${q}" 0
policy error-events "metric.type=${q}logging.googleapis.com/user/legion_error_events${q}" 0
policy http-5xx "metric.type=${q}logging.googleapis.com/user/legion_http_5xx${q} AND resource.type=${q}cloud_run_revision${q}" 5

echo "# 4. budget"
if [ -z "${BILLING_ACCOUNT:-}" ]; then
	if [ "$APPLY" = 1 ]; then
		BILLING_ACCOUNT=$(gcloud billing projects describe "$PROJECT" \
			--format='value(billingAccountName)' | sed 's#billingAccounts/##')
	else
		echo "\$ gcloud billing projects describe $PROJECT --format='value(billingAccountName)'"
		BILLING_ACCOUNT="<BILLING_ACCOUNT from the command above>"
	fi
fi
run gcloud billing budgets create --billing-account="$BILLING_ACCOUNT" \
	--display-name="LEGION monthly" --budget-amount="${BUDGET_USD}USD" \
	--filter-projects="projects/$PROJECT" \
	--threshold-rule=percent=0.5 --threshold-rule=percent=0.9 \
	--threshold-rule=percent=1.0 --threshold-rule=percent=1.0,basis=forecasted-spend
echo "# Budget email goes to the billing account's admins by default. To also notify the"
echo "# channel above, add: --notifications-rule-monitoring-notification-channels=$CHANNEL"
rm -rf "$TMP"
