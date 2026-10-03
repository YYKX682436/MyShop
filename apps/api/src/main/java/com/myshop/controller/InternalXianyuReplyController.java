package com.myshop.controller;

import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.MessageAction;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.MessageEvent;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ReplyResolution;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Phase 1 reply boundary skeleton; reply strategies are intentionally absent. */
@RestController
@RequestMapping("/internal/xianyu/reply")
public class InternalXianyuReplyController {

    @PostMapping("/resolve")
    public ReplyResolution resolve(@Valid @RequestBody MessageEvent event) {
        return new ReplyResolution(event.messageId(), MessageAction.NO_REPLY, null, null);
    }
}
