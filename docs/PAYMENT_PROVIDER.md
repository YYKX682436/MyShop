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
Sensitive schema fields are masked in read responses. `channel_code` is the
unique code of a concrete channel instance, not the payment type; for example
`epay-alipay-main` and `epay-alipay-backup` can both use `payment_type=alipay`.
Production configuration must use HTTPS callback and return URLs and must not
be placed in source control.

The Phase 1 Epay implementation maps `alipay` and `wechat` to Epay's
`alipay`/`wxpay` request types, verifies the callback signature, then queries
the gateway server-side. The order is changed to PAID only after signature,
exact amount, paid status, and trade-number checks all succeed.
