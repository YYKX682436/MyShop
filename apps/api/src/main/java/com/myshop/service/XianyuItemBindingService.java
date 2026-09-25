package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.exception.BusinessException;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.saleschannel.SalesChannelProviderClient;
import com.myshop.saleschannel.SalesChannelProviderClientException;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuItemBindingResponse;
import com.myshop.saleschannel.model.SalesChannelDtos.CatalogItem;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsQueryCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsQueryData;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Phase 2B orchestration. The Agent catalog read deliberately happens before
 * the local transaction so no database row lock is held while waiting on HTTP.
 */
@Service
@RequiredArgsConstructor
public class XianyuItemBindingService {

    private final SalesChannelProviderClient providerClient;
    private final SalesChannelAccountRepository accountRepository;
    private final XianyuItemBindingTransactionService transactionService;

    public XianyuItemBindingResponse link(UUID accountId, String itemId, UUID productId) {
        SalesChannelAccount account = findEnabledXianyuAccount(accountId);
        validateItemId(itemId);
        verifyExactCatalogItem(account, itemId);
        return transactionService.link(accountId, itemId, productId);
    }

    private void verifyExactCatalogItem(SalesChannelAccount account, String requestedItemId) {
        IntegrationEnvelope<ItemsQueryData> response = providerClient.queryItems(
                new ItemsQueryCommand(account.getProviderAccountId(), 1, 100, requestedItemId));
        if (response == null || response.outcome() != Outcome.SUCCEEDED || response.data() == null) {
            String code = response == null || response.error() == null
                    ? "AGENT_REQUEST_FAILED" : response.error().code();
            String message = response == null || response.error() == null
                    || response.error().message() == null || response.error().message().isBlank()
                    ? "闲鱼商品目录读取失败" : response.error().message();
            boolean retryable = response != null && response.error() != null
                    && Boolean.TRUE.equals(response.error().retryable());
            throw new SalesChannelProviderClientException(
                    retryable ? SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE
                            : SalesChannelProviderClientException.Disposition.SAFE_PERMANENT,
                    blank(code) ? "AGENT_REQUEST_FAILED" : code, message);
        }

        List<CatalogItem> items = response.data().items() == null ? List.of() : response.data().items();
        boolean exactMatch = items.stream().anyMatch(item -> item != null
                && requestedItemId.equals(item.itemId())
                && account.getProviderAccountId().equals(item.accountId()));
        if (!exactMatch) {
            throw new BusinessException(ErrorCode.XIANYU_ITEM_NOT_FOUND,
                    "闲鱼商品不存在或不属于当前账号", HttpStatus.NOT_FOUND);
        }
    }

    private SalesChannelAccount findEnabledXianyuAccount(UUID accountId) {
        SalesChannelAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "闲鱼账号不存在", HttpStatus.NOT_FOUND));
        if (account.getProvider() != SalesChannelProvider.XIANYU) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "所选账号不是闲鱼账号");
        }
        if (!account.isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "闲鱼账号已禁用");
        }
        return account;
    }

    private void validateItemId(String itemId) {
        if (blank(itemId) || itemId.length() > 128) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "闲鱼商品 ID 无效");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
