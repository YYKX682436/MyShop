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
- `create_mode` — select, default `api`, with `api` (the server calls
  `mapi.php`) and `page` (the browser submits `submit.php`);
- `carrier_mode` — select, default `auto`, with `auto`, `qr_code`,
  `redirect`, and `open_in_client`. This is an administrator declaration of
  what the API gateway's returned carrier means and applies only in `api`
  mode.

The two Epay creation protocols are separate:

- In `api` mode, MyShop builds the common signed fields, POSTs them to
  `mapi.php`, requires a successful response code, reads the provider trade
  number, and normalizes the returned carrier according to `carrier_mode`. If
  the successful response has no carrier compatible with the selected API
  carrier mode, creation fails with `支付创建失败：API 模式下网关未返回可用支付载体`;
  `submit.php` is never requested.
- In `page` mode, MyShop does not request `mapi.php`. It returns a
  `form_post` action whose URL is the gateway base plus `/submit.php` and whose
  fields are the signed `pid`, `type`, `out_trade_no`, `notify_url`,
  `return_url`, `name`, `money`, `clientip`, `device`, `sign`, and
  `sign_type=MD5`. The merchant key is never included in those browser
  fields, and the provider trade number is null until the gateway reports it.
  `carrier_mode` is not read in this mode.

The API adapter first extracts returned values without assigning domain
semantics to their provider-specific field names. The configured API carrier
mode then chooses the standard action:

- `auto` follows the common Epay convention: a valid non-HTTP(S) `urlscheme`
  becomes `deep_link`; otherwise `qrcode`/`qr_code` becomes `qr_code`; otherwise
  `payurl`, `payurl2`, `url`, or `pay_action_url` becomes `redirect`. An HTTP
  value in a QR field is not guessed into a redirect in this mode.
- `qr_code` treats the returned carrier as QR content. It prefers
  `qrcode`/`qr_code`, then the URL fields, and finally a valid app scheme. This
  explicit mode may encode an HTTP URL or deep link as the QR content.
- `redirect` searches, in order, `payurl`, `payurl2`, `url`, `pay_action_url`,
  `qrcode`, and `qr_code` for the first valid HTTP(S) URL and opens it as a
  payment page.
- `open_in_client` uses the same HTTP(S) carrier order, then selects `wechat`
  for `wxpay` or `alipay` for `alipay`.

`redirect` and `open_in_client` reject app schemes, `javascript:`, `data:`,
`file:`, and malformed URLs. A forced mode with a returned but incompatible
carrier fails closed. Some Epay gateways put a payment-page URL in the
`qrcode` field. For those gateways do not leave the channel in `auto`: choose
`redirect` or `open_in_client` according to the gateway's actual behavior.

Epay protocol statuses and fields are translated to the standard SPI only
inside the Epay adapter/service. The generic payment and webhook services do
not contain Epay status strings or protocol branches.
