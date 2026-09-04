package com.myshop.service.impl;

import com.myshop.entity.CardKey;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final UserRepository userRepository;

    @Value("${mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${mail.site-url:http://localhost:3000}")
    private String siteUrl;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Async
    @Override
    public void sendDeliveryEmail(UUID orderId, String lookupToken, List<UUID> orderItemIds) {
        if (!mailEnabled) {
            return;
        }

        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null || order.getUserId() == null) {
                // Guest orders have no recipient by design. This is a normal
                // path, so do not emit a warning or create a noisy failure.
                return;
            }
            String recipient = userRepository.findById(order.getUserId())
                    .map(user -> user.getEmail())
                    .filter(email -> email != null && !email.isBlank())
                    .orElse(null);
            if (recipient == null) {
                return;
            }

            JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
            if (mailSender == null) {
                log.warn("Delivery email is enabled but no JavaMailSender is configured");
                return;
            }

            List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
            List<CardKey> keys = cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
            Set<UUID> selected = orderItemIds == null ? Set.of() : new LinkedHashSet<>(orderItemIds);
            Map<UUID, OrderItem> itemMap = items.stream()
                    .filter(item -> selected.isEmpty() || selected.contains(item.getId()))
                    .collect(Collectors.toMap(OrderItem::getId, i -> i, (first, ignored) -> first, LinkedHashMap::new));
            Map<UUID, List<CardKey>> grouped = keys.stream()
                    .filter(k -> k.getOrderItemId() != null)
                    .filter(k -> selected.isEmpty() || selected.contains(k.getOrderItemId()))
                    .collect(Collectors.groupingBy(CardKey::getOrderItemId, LinkedHashMap::new, Collectors.toList()));

            String siteName = siteConfigRepository.findByConfigKey("site_name")
                    .map(c -> c.getConfigValue())
                    .orElse("MyShop");

            String html = buildHtml(order, itemMap, grouped, siteName, lookupToken);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(recipient);
            helper.setSubject("【" + siteName + "】订单发货通知 - " + order.getOrderNo());
            helper.setText(html, true);

            mailSender.send(message);
            log.info("Delivery email sent for order {}", orderId);
        } catch (MessagingException e) {
            log.error("Failed to send delivery email for order {}: {}", orderId, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error sending delivery email for order {}: {}", orderId, e.getMessage(), e);
        }
    }

    private String buildHtml(Order order, Map<UUID, OrderItem> itemMap,
                             Map<UUID, List<CardKey>> grouped, String siteName, String lookupToken) {
        Long orderNo = order.getOrderNo();
        BigDecimal amount = order.getActualAmount() != null ? order.getActualAmount() : order.getTotalAmount();
        LocalDateTime createdAt = order.getCreatedAt();
        String orderUrl = siteUrl + "/order/query?orderNo=" + orderNo;
        if (lookupToken != null && !lookupToken.isBlank()) {
            orderUrl += "&lookup_token=" + URLEncoder.encode(lookupToken, StandardCharsets.UTF_8);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>");
        sb.append("<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">");
        sb.append("</head><body style=\"margin:0;padding:0;background-color:#f4f4f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;\">");

        // Container
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#f4f4f7;padding:24px 0;\">");
        sb.append("<tr><td align=\"center\">");
        sb.append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;width:100%;background-color:#ffffff;border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08);\">");

        // Header
        sb.append("<tr><td style=\"background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:32px 40px;text-align:center;\">");
        sb.append("<h1 style=\"margin:0;color:#ffffff;font-size:24px;font-weight:600;letter-spacing:1px;\">")
          .append(escapeHtml(siteName)).append("</h1>");
        sb.append("</td></tr>");

        // Title
        sb.append("<tr><td style=\"padding:32px 40px 0;\">");
        sb.append("<h2 style=\"margin:0 0 8px;color:#333333;font-size:20px;font-weight:600;\">订单发货通知</h2>");
        sb.append("<p style=\"margin:0;color:#666666;font-size:14px;line-height:1.6;\">您的订单已完成发货，以下是您购买的卡密信息：</p>");
        sb.append("</td></tr>");

        // Order info
        sb.append("<tr><td style=\"padding:24px 40px;\">");
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#f8f9fa;border-radius:6px;padding:16px;\">");
        sb.append("<tr><td style=\"padding:4px 16px;\"><span style=\"color:#888;font-size:13px;\">订单编号</span></td>");
        sb.append("<td style=\"padding:4px 16px;text-align:right;\"><span style=\"color:#333;font-size:13px;font-family:monospace;\">")
          .append(orderNo).append("</span></td></tr>");
        sb.append("<tr><td style=\"padding:4px 16px;\"><span style=\"color:#888;font-size:13px;\">支付金额</span></td>");
        sb.append("<td style=\"padding:4px 16px;text-align:right;\"><span style=\"color:#333;font-size:13px;font-weight:600;\">¥")
          .append(amount).append("</span></td></tr>");
        if (createdAt != null) {
            sb.append("<tr><td style=\"padding:4px 16px;\"><span style=\"color:#888;font-size:13px;\">下单时间</span></td>");
            sb.append("<td style=\"padding:4px 16px;text-align:right;\"><span style=\"color:#333;font-size:13px;\">")
              .append(createdAt.format(DATE_FMT)).append("</span></td></tr>");
        }
        sb.append("</table>");
        sb.append("</td></tr>");

        // Card keys grouped by product
        sb.append("<tr><td style=\"padding:0 40px 24px;\">");
        int itemNumber = 0;
        for (Map.Entry<UUID, OrderItem> itemEntry : itemMap.entrySet()) {
            List<CardKey> itemKeys = grouped.getOrDefault(itemEntry.getKey(), List.of());
            if (itemKeys.isEmpty()) continue;
            itemNumber++;
            OrderItem item = itemEntry.getValue();

            String title = escapeHtml(item.getProductTitle());
            if (item.getSpecName() != null && !item.getSpecName().isBlank()) {
                title += " <span style=\"color:#888;font-size:12px;\">[" + escapeHtml(item.getSpecName()) + "]</span>";
            }

            sb.append("<div style=\"margin-bottom:16px;border:1px solid #e8e8e8;border-radius:6px;overflow:hidden;\">");
            sb.append("<div style=\"background-color:#f0f0f5;padding:10px 16px;font-size:14px;font-weight:600;color:#333;\">")
              .append("第").append(itemNumber).append("项 · ").append(title).append("</div>");
            sb.append("<div style=\"padding:12px 16px;\">");
            if (item.getCustomerInputs() != null && !item.getCustomerInputs().isEmpty()) {
                sb.append("<div style=\"margin-bottom:12px;padding:8px 10px;background-color:#f8f9fa;border-radius:4px;font-size:12px;color:#666;\">");
                for (var input : item.getCustomerInputs()) {
                    sb.append("<div style=\"margin-bottom:4px;\"><span style=\"color:#888;\">")
                      .append(escapeHtml(input.label())).append("：</span>")
                      .append(escapeHtml(input.value())).append("</div>");
                }
                sb.append("</div>");
            }
            for (int cardIndex = 0; cardIndex < itemKeys.size(); cardIndex++) {
                CardKey key = itemKeys.get(cardIndex);
                        List<String> parts = key.getContentParts();
                sb.append("<div style=\"margin-bottom:10px;padding:8px 0 2px;color:#666;font-size:12px;\">第 ")
                  .append(cardIndex + 1).append(" 份</div>");
                for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
                    sb.append("<div style=\"background-color:#fafafa;border:1px solid #eee;border-radius:4px;padding:8px 12px;margin-bottom:6px;font-family:'Courier New',Courier,monospace;font-size:13px;color:#222;word-break:break-all;\">")
                      .append("卡券内容 ").append(partIndex + 1).append(": ")
                      .append(escapeHtml(parts.get(partIndex))).append("</div>");
                }
            }
            sb.append("</div></div>");
        }
        sb.append("</td></tr>");

        // Warning
        sb.append("<tr><td style=\"padding:0 40px 24px;\">");
        sb.append("<div style=\"background-color:#fff8e1;border:1px solid #ffe082;border-radius:6px;padding:12px 16px;font-size:13px;color:#f57f17;\">");
        sb.append("&#9888; 请妥善保管卡密信息，切勿泄露给他人。如有任何问题，请联系客服。");
        sb.append("</div></td></tr>");

        // CTA button
        sb.append("<tr><td style=\"padding:0 40px 32px;text-align:center;\">");
        sb.append("<a href=\"").append(escapeHtml(orderUrl)).append("\" style=\"display:inline-block;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:#ffffff;text-decoration:none;padding:12px 32px;border-radius:6px;font-size:14px;font-weight:600;\">查看订单详情</a>");
        sb.append("</td></tr>");

        // Footer
        sb.append("<tr><td style=\"background-color:#f8f9fa;padding:20px 40px;text-align:center;border-top:1px solid #eee;\">");
        sb.append("<p style=\"margin:0 0 4px;color:#999;font-size:12px;\">此邮件由系统自动发送，请勿直接回复</p>");
        sb.append("<p style=\"margin:0;color:#bbb;font-size:11px;\">&copy; ").append(java.time.Year.now().getValue())
          .append(" ").append(escapeHtml(siteName)).append("</p>");
        sb.append("</td></tr>");

        sb.append("</table></td></tr></table>");
        sb.append("</body></html>");

        return sb.toString();
    }

    private static String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
    }
}
