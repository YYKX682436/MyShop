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
queries the gateway server-side when the gateway exposes a usable JSON query
endpoint. The order is changed to PAID only after the signature, exact amount,
and paid status checks succeed; a trade-number and server-query check is also
required whenever the query endpoint returns a valid result.

Some gateways, including the production gateway verified for this deployment,
accept the same signed parameter set at a `submit.php` page but return only
`code=1`, `trade_no`, and no payment carrier from `mapi.php`. The API keeps the
JSON `mapi.php` request as the only server-side create call. For that specific,
operator-verified behavior, set
`EPAY_PAGE_REDIRECT_FALLBACK_ENABLED=true` in the deployment environment. The
provider then returns a signed `submit.php` page URL as `payUrl` only after a
successful response with a non-empty `trade_no`; the merchant key is never
placed in the URL or logs. The default is `false`, so an unverified gateway
still fails closed with the no-carrier error. This is not an `api.php` fallback
and does not add a fourth administrator configuration field.

Some Epay-compatible gateways expose no order-query API and return an HTML
storefront from the conventional `api.php?act=order` endpoint. `EpayService`
marks that explicit HTML response as query-unavailable; the Epay provider may
then accept the already verified callback using its MD5 signature, exact amount,
and `TRADE_SUCCESS` status. Network failures, empty responses, malformed
non-HTML responses, and query error responses remain failures and are not
treated as paid.

The server builds provider callback and payment-result URLs from the
canonical `PUBLIC_SITE_URL` environment variable (`app.public-site-url`),
never from an incoming request Host header. The Epay callback route is the
real `GET /api/payments/webhook/epay` route. The synchronous return goes
to the storefront `/pay/{orderId}` payment-result page with the visible order
number, payment type, and a return marker in its query string. The storefront
uses that marker to wait for asynchronous confirmation instead of redirecting
back to the gateway after the user returns. These URLs are not administrator
configuration fields.

Disabling a channel prevents new payment creation only. Existing pending
orders continue to use the persisted channel profile for callback verification
and confirmation. While a normal pending order references a channel, changes
to its provider, payment type, generated code, or `pid`/`key`/`api_url`, as
well as deletion, return HTTP 409. Display-only fields remain editable.
