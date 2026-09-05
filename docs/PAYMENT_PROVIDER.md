# Payment provider SPI

Phase 1 keeps payment integration behind `com.myshop.payment.PaymentProvider`.
The order service knows only the provider ID, supported payment type, amount,
and provider result; it does not contain Epay-specific branches.

## Implementing a provider

1. Add a Spring `@Component` implementing `PaymentProvider`.
2. Return a stable lowercase `id()`, display name, supported payment types, and a complete `configSchema()`.
3. Implement `create()` using the validated provider configuration and the server-calculated `BigDecimal` amount.
4. If the provider uses asynchronous callbacks, implement `verifyCallback()` and `query()`. The webhook service calls both using the channel profile persisted on the order.
5. Never log secrets, signatures, full callback payloads, or access tokens. Return only public payment URLs/QR data from `create()`.
6. Add provider-specific unit tests for signing, response parsing, exact amounts, failed queries, and duplicate callbacks.

The SPI includes these records:

- `PaymentRequest`: order ID, payment type, product name, server amount, client metadata, and parsed channel config.
- `CallbackRequest`: untrusted callback parameters plus the stored channel config.
- `PaymentQueryRequest`: the merchant order ID plus the stored channel config.
- `PaymentQueryResult`: provider status, amount, and provider trade number.

`ConfigField` is the backend-owned form contract. Its type can be `text`,
`password`/`secret`, `url`, `number`, `switch`, or `select`; it also carries
label, required, placeholder, default value, and select options. The admin UI
fetches `/payment-providers` at startup and renders this schema. It does not
use a provider allowlist or Epay field list as its production data source.

## Channel administration rules

The admin API validates provider IDs and payment types against the registered
provider. Config keys must be declared by the provider schema, field types and
select options are checked server-side, and values are bounded scalar values.
Sensitive schema fields are masked in admin read responses. The server
generates `channel_code` from the payment type for a new channel; administrators
do not type it. The display name defaults to `支付宝` or `微信支付` and can be
overridden together with the optional storefront hint and short badge.

The current mainland storefront exposes only `alipay` and `wxpay`. When only
one provider is registered, its provider ID is selected by the server and is
not an admin choice. `status` and `sort` have safe defaults and are management
fields rather than protocol prerequisites.

The Phase 1 Epay-compatible implementation has exactly three configuration
fields: `pid`, `key`, and `api_url`. It accepts either a gateway root or a full
`mapi.php` URL and normalizes both forms to one `mapi.php` request endpoint.
It keeps the Epay parameter set, sorted MD5 signature, JSON response parsing,
and `payurl`/`qrcode` handling. It verifies the callback signature, then
queries the gateway server-side. The order is changed to PAID only after
signature, exact amount, paid status, and trade-number checks all succeed.

The server builds provider callback and payment-result URLs from the
canonical `PUBLIC_SITE_URL` environment variable (`app.public-site-url`),
never from an incoming request Host header. The Epay callback route is the
real `GET /api/payments/webhook/epay` route. The synchronous return goes
to the storefront `/pay/{orderId}` payment-result page with the visible order
number and payment type in its query string. These URLs are not administrator
configuration fields.

Disabling a channel prevents new payment creation only. Existing pending
orders continue to use the persisted channel profile for callback verification
and confirmation. While a normal pending order references a channel, changes
to its provider, payment type, generated code, or `pid`/`key`/`api_url`, as
well as deletion, return HTTP 409. Display-only fields remain editable.
