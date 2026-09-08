# Payment Provider SPI

MyShop keeps payment integration behind `PaymentChannel` and the
`com.myshop.payment.PaymentProvider` SPI. A channel selects a registered
provider, payment type, and provider-owned `configSchema`; no additional
payment-method, route, or account layer is used.

## Provider contract

Implement a Spring `@Component` that provides:

- `id()`, `displayName()`, `paymentTypes()`, and `configSchema()`;
- `create(PaymentRequest)`, whose request contains the independent
  `merchantTradeNo`, payment type, amount, product name, device context, and
  channel configuration;
- `extractMerchantTradeNo(WebhookRequest)` and
  `verifyAndParseCallback(WebhookRequest, config)` when asynchronous callbacks
  are supported;
- `supportsServerSideQuery()` and `query(PaymentQueryRequest)` when a trusted
  server-to-server confirmation endpoint exists;
- `allowSignedCallbackWhenQueryUnavailable()` only when the provider has a
  documented, safe policy for a signed callback when its query endpoint is
  explicitly unavailable;
- `successAcknowledgement()` and `failureAcknowledgement()` for the provider's
  webhook protocol response.

`PaymentResult` contains only `providerTradeNo` and the standard
`PaymentNextAction`. `PaymentQueryResult` contains `PaymentStatus`, exact
`BigDecimal amount`, and `providerTradeNo`. The standard status values are
`PENDING`, `PAID`, `FAILED`, and `CLOSED`.

## PaymentNextAction

The wire contract is a discriminated object with a `type` field:

```json
{ "type": "qr_code", "value": "..." }
{ "type": "redirect", "url": "https://..." }
{ "type": "open_in_client", "url": "https://...", "client": "wechat" }
{ "type": "deep_link", "url": "weixin://...", "client": "wechat" }
{ "type": "form_post", "url": "https://...", "fields": { "token": "..." } }
```

The action is selected by the provider adapter. Core payment services and the
storefront render the declared action and never infer its meaning from a URL,
the device, or a provider field name.

## PaymentAttempt

Every external transaction is a `payment_attempts` row. It stores:

- the order and channel IDs;
- channel-code, provider, payment-type, and complete provider-config
  snapshots;
- a unique independent `merchantTradeNo`;
- provider trade number, exact amount, standard status, serialized
  `nextAction`, and expiration time.

The provider/config/payment-type/channel snapshots are captured before the
provider create call. The configuration snapshot is sensitive: it is never
returned by an API, written to application logs, or copied into a
`WebhookEvent` payload. A valid pending attempt is returned on repeated create
requests without another remote create call. Repay closes pending attempts and
creates a new merchant trade number.

The order stores no provider trade number or payment action. Its status API
reads the current valid pending attempt and returns its `payment_id` and
`next_action`. An order may have multiple attempts.

## Generic webhook flow

Callbacks use `/api/payments/webhook/{providerId}` and support both GET and
POST. The provider receives the HTTP method, headers, query parameters, form
parameters, and raw body. The core flow is:

1. Resolve `{providerId}` to a registered provider.
2. Ask that provider to extract the untrusted merchant trade number.
3. Load the `PaymentAttempt` by the unique merchant trade number and require
   that its provider snapshot matches the path provider.
4. Pass the attempt's immutable configuration snapshot to the provider for
   signature verification and callback normalization.
5. Require the normalized event ID, merchant trade number, `PAID` status, and
   exact amount. If the provider supports server-side query, require a paid
   query result with a matching amount and trade identity, subject to the
   provider's explicit query-unavailable policy.
6. In one confirmation transaction mark the attempt and order paid, save the
   webhook event from the attempt snapshots, and register fulfillment only
   after commit. Event IDs make repeated callbacks idempotent.

Channel edits, disablement, and soft deletion affect only new attempts. A
previous attempt always uses its own snapshot and can finish its callback.

## Epay channel configuration

The Epay adapter declares these channel-level fields:

- `pid` — required merchant ID;
- `key` — required secret, masked in administrator responses;
- `api_url` — required gateway root or `mapi.php` URL;
- `submit_fallback_enabled` — switch, default `false`. When enabled for this
  channel only, a successful create response without a usable carrier may use
  the signed `submit.php` page;
- `carrier_mode` — select, default `auto`, with `auto`, `qr_code`,
  `redirect`, and `open_in_client`.

In `auto`, an explicit app scheme becomes `deep_link`, an Epay QR field becomes
`qr_code`, and a URL carrier becomes `redirect`. `open_in_client` wraps a URL
carrier and selects `wechat` for `wxpay` or `alipay` for `alipay`. A forced
mode without a compatible returned carrier fails closed. The adapter never
turns a QR value into a browser page based on the device.

Epay protocol statuses and fields are translated to the standard SPI only
inside the Epay adapter/service. The generic payment and webhook services do
not contain Epay status strings or protocol branches.
