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

The Epay adapter declares exactly these channel-level fields:

- `api_url` — required HTTP(S) gateway root or an explicit `submit.php`,
  `mapi.php`, or `pay/apisubmit` endpoint;
- `pid` — required merchant ID;
- `key` — required merchant secret, masked in administrator responses.

Creation protocol is selected by the configured endpoint, not by an
administrator-controlled mode field:

- A root gateway URL is normalized and returned as a `form_post` action to
  `<gateway>/submit.php`. An explicit `submit.php` URL is posted to directly.
  The browser receives only the signed `pid`, `type`, `out_trade_no`,
  `notify_url`, `return_url`, `name`, `money`, `clientip`, `device`, `sign`,
  and `sign_type=MD5`; the merchant key is never included.
- An explicit `mapi.php` or `pay/apisubmit` URL is called by the server with
  the same signed form fields. The response must be successful JSON; HTML or
  another non-JSON response fails with `支付网关请填写站点根地址或 submit.php`.

For a successful API response, the adapter returns one standard action using
these protocol field semantics:

- A valid non-HTTP(S) `urlscheme` becomes `deep_link`.
- `qrcode` / `qr_code` is always returned as `qr_code` raw content. An HTTP(S)
  value remains QR content and is rendered into a local QR code; it is never
  loaded as a payment page or embedded in the QR area.
- `payurl`, `payurl2`, `url`, or `pay_action_url` becomes a `redirect` only
  when the selected value is a valid HTTP(S) URL. Relative redirect values are
  resolved against the normalized gateway base. If no compatible carrier is
  returned, creation fails closed.

For server-side payment status checks, the adapter removes `/mapi.php`, `/pay/apisubmit`, or
`/submit.php` from the configured URL and calls `/api.php` on the normalized
gateway root. Root URLs with a trailing slash are normalized before endpoint
construction, so no double slash is generated. The admin schema rejects every
other configuration field; a forward migration canonicalizes existing Epay
objects to the same three keys and preserves their values.

Epay protocol statuses and fields are translated to the standard SPI only
inside the Epay adapter/service. The generic payment and webhook services do
not contain Epay status strings or protocol branches.
