package com.myshop.controller;

import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.MessageAction;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.MessageEvent;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ReplyResolution;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> invalidMessageBody() {
        return java.util.Map.of("error", "INVALID_MESSAGE_EVENT");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public java.util.Map<String, String> unsupportedContentType() {
        return java.util.Map.of("error", "UNSUPPORTED_CONTENT_TYPE");
    }
}
