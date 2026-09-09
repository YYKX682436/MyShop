# Storefront dock design QA

final result: passed

## Visual inputs

- Reference image: `C:\Users\15853\.codex\attachments\77cccccb-2766-4b67-a7eb-f255d5960d30\image-1.jpg` (1440 × 416, a cropped bottom-navigation reference).
- Implementation: `http://localhost:3000/`, Codex in-app browser tab 1. The implementation was captured in the same QA pass at the default viewport and as a 1440px focused Dock clip; the browser tool emitted the captures inline but does not expose a persistent screenshot filesystem path.
- Compared state: guest storefront, Search closed, zh/light for the primary comparison. Dark and English states were checked separately. The local API was unavailable, so the product area remained in its existing error state; comparison was scoped to the Dock/Search surface shown by the reference.

## Focused comparison

The reference and implementation both use the requested composition: one continuous rounded pill for the primary navigation, a detached circular action with a visible gap, vertically stacked icon and label, and a distinct active surface. The implementation uses the task's explicit product semantics (`首页 / 订单 / 我` plus Search) instead of the reference image's illustrative `消息` and `+` labels. It also follows the task's explicit responsive constraint of a centered 440–480px Dock; the reference crop is intentionally wider than that constraint.

No P0, P1, or P2 visual issues remain in the focused Dock comparison. The local API limitation affects only live product/config content, not the Dock layout or interactions.

## Responsive and theme evidence

| viewport | light Dock width | dark Dock width | document scroll width | result |
| ---: | ---: | ---: | ---: | --- |
| 320px | 296px | 296px | 305px | pass, no horizontal overflow |
| 375px | 351px | 351px | 360px | pass, no horizontal overflow |
| 390px | 366px | 366px | 375px | pass, no horizontal overflow |
| 768px | 480px | 480px | 768px | pass, max width held |
| 1440px | 480px | 480px | 1440px | pass, max width held |

The measured glass surface used `backdrop-filter: blur(22px) saturate(1.5)` in both themes. Light mode resolved to a translucent white surface (`rgba(255, 255, 255, 0.74)`); dark mode resolved to a layered dark surface (`rgba(16, 16, 18, 0.78)`). The CSS includes the WebKit prefix, edge/highlight/inset/external shadows, `env(safe-area-inset-bottom)`, pointer-event layering, and an opaque fallback when backdrop blur is unsupported.

## Interaction checks

- Search opens, focuses automatically, closes with Escape, toggles from the detached button, and remains usable above the Dock.
- Existing query, sort, in-stock, min-price, max-price, clear, and reset behavior is wired through the existing SearchContext.
- Search state remains visible as a detached-button indicator when the panel is closed.
- Navigation closes Search; non-home Search returns to `/` with the panel open.
- Active route semantics keep `/my/orders` on Orders rather than Me.
- Reduced-motion handling disables the spring transitions when the user preference requests it.
