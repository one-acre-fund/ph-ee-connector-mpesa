# Safaricom mpesa connector
Part of payment hub. It is a microservice for Safaricom mpesa connector.

## Safaricom Documentation
[developer.safaricom.co.ke](https://developer.safaricom.co.ke/)

## Calls to Safaricom: timeouts, retries and circuit breaker
Every call to Safaricom (token, STK push, STK query) has its own timeouts, retries and circuit breaker, one per endpoint
and host (`mpesa-token-<host>`, `mpesa-stk-push-<host>`, `mpesa-stk-query-<host>`).

**The STK push is never sent twice.** Safaricom has no idempotency key, so the STK push is retried only when the
connection failed and the request never left. A timeout or gateway error is not retried: the payment is failed with
`CONNECTION_ERROR` (or `CIRCUIT_OPEN`) and should be reconciled if the customer still paid.

**Status checks are safe to repeat.** The STK query retries gateway errors (502/503/504) and timeouts. If it still has
no answer, the payment stays pending and is checked again, up to `mpesa.max-retry-count`. A `500` means "still
processing" and is handled as before.

**Retries stay within the Zeebe job.** An attempt (first call or retry) is only made if it can finish, at its connect
and read timeouts, `mpesa.resilience.job-deadline-margin` (10s) before the job's deadline. Otherwise the call ends
with the previous attempt's result, or as not sent (`JOB_DEADLINE`) when no attempt was made. A job therefore never
times out while a call is running, so Zeebe cannot hand it to another worker that would send a second STK push.

**Circuit breaker.** When `failure-rate-threshold` percent of the last `sliding-window-size` calls fail (connection
failures, timeouts and `failure-status-codes`; business errors do not count), the circuit opens and calls fail at once
without reaching Safaricom. After `wait-duration-in-open-state`, `permitted-calls-in-half-open-state` probe calls go
through; if they succeed the circuit closes, otherwise it opens again.

**Defaults** (`mpesa.resilience.<endpoint>` in `application.yml`, `<endpoint>` is `token`, `stk-push` or `stk-query`):

| Setting | token | stk-push | stk-query |
|---|---|---|---|
| `connect-timeout` | 5s | 5s | 5s |
| `read-timeout` | 20s | `mpesa.api.timeout` | 20s |
| `retry.max-attempts` (including the first call) | 3 | 3 | 3 |
| `retry.initial-backoff` / `backoff-multiplier` / `jitter` | 500ms / 2.0 / 0.5 | 500ms / 2.0 / 0.5 | 500ms / 2.0 / 0.5 |
| `retry.retry-status-codes` | 500,502,503,504 | none | 502,503,504 |
| `retry.retry-when-outcome-unknown` | true | false | true |
| `circuit-breaker.failure-status-codes` | 500,502,503,504 | 502,503,504 | 502,503,504 |
| `circuit-breaker.failure-rate-threshold` (%) | 50 | 50 | 50 |
| `circuit-breaker.sliding-window-size` / `minimum-number-of-calls` | 20 / 10 | 20 / 10 | 20 / 10 |
| `circuit-breaker.wait-duration-in-open-state` | 30s | 30s | 30s |
| `circuit-breaker.permitted-calls-in-half-open-state` | 3 | 3 | 3 |

Override any value with an environment variable, e.g. `MPESA_RESILIENCE_STKQUERY_READTIMEOUT=15s` or
`MPESA_RESILIENCE_STKPUSH_CIRCUITBREAKER_WAITDURATIONINOPENSTATE=1m`.

**Monitoring.**
- Metrics on `/actuator/prometheus`: `resilience4j_circuitbreaker_state{name,state}`,
  `resilience4j_circuitbreaker_calls_seconds_*{name,kind}`, `resilience4j_retry_calls_total{name,kind}`.
- Log events: `event=mpesa_circuit_state_changed`, `event=mpesa_call_rejected`, `event=mpesa_call_retry`,
  `event=mpesa_retry_exhausted`, `event=mpesa_call_skipped` (no time left before the job deadline). Reasons name the
  status code or exception class only, never URLs, headers or bodies.

## B2C payouts (FD-1946)
Pays money out to a customer's M-Pesa wallet with the Daraja B2C v3 API. It implements the scheme-neutral payout
contract of the channel (see the channel README, "Contract for a scheme connector"); MTN, Airtel and others implement
the same contract with their own `init-{ps}-payout` / `get-{ps}-payout-status` workers. The channel starts the
`mpesa_payout_{ams}-{tenant}` process from `POST /channel/payout`; this connector runs two of its jobs:

- `init-mpesa-payout` sends the B2C payment request. The transaction id is the B2C `OriginatorConversationID`, and Safaricom
  refuses a second request with the same id (`500.002.1001`), so a retried job or a resent request is never paid twice.
- `get-mpesa-payout-status` runs when no result arrived in time. It sends a transaction status query by
  `OriginatorConversationID`; the wait doubles up to `mpesa.payout.status.max-timer`.

Outcomes:
- **Failed** only when nothing can have been paid: invalid request (whole KES amount within the account limits,
  `254` + 9-digit MSISDN; missing or short remarks become "Payout", long ones are cut to 100), a 4xx or non-zero `ResponseCode`, or a request that never left.
- **Unknown** when the request may have reached Safaricom (timeout, 5xx, no response). It is never failed
  automatically: the result callback or a status query resolves it. After `mpesa.payout.status.max-checks` checks the
  process waits for a late result or for an operator: `POST /channel/transaction/{transactionId}/resolve` with
  `{"payoutFailed": false, "externalId": "<M-Pesa receipt>"}` or `{"payoutFailed": true}`.
- **Result callbacks** are only accepted for a payout this connector sent and, on success, only when the amount matches.
  Each payout publishes `payout-result` at most once.

Configuration, per account under `accounts.group[].b2c` (all from secrets, `enabled: false` by default):
`short-code`, `initiator-name`, `security-credential` (the initiator password encrypted with the Safaricom certificate
and base64 encoded, never the plain password), optional `client-key`/`client-secret` when the B2C shortcode has its own
Daraja app, `command-id` (default `BusinessPayment`), `min-amount` and `max-amount`.

Callbacks: Safaricom posts results to `mpesa.local.host` + `mpesa.local.payout-result` / `payout-status-result` (and
the two timeout paths). Expose them through Kong to Safaricom's IP addresses only, with hard-to-guess paths.

Monitoring: `mpesa_b2c_utility_balance{account}` (from each successful result), log events `event=payout_result`,
`event=payout_unknown` (needs an operator), `event=payout_result_unknown` and `event=payout_result_mismatch` (a result
that does not match a payout we sent), and the `mpesa-b2c-*` / `mpesa-b2c-status-*` circuit breakers.
