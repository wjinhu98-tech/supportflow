package dev.supportflow.common.security.api.controller;

import dev.supportflow.common.security.api.response.CsrfResponse;
import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 向已认证客户端提供 CSRF token。
 */
@RestController
@RequestMapping("/api/v1/security")
public class SecurityController {

    @GetMapping("/me")
    public AccountResponse me(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return new AccountResponse(principal.getUsername(), principal.getAccountType());
    }

    public record AccountResponse(String username, AccountType accountType) { }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(
                csrfToken.getHeaderName(),
                csrfToken.getParameterName(),
                csrfToken.getToken()
        );
    }
}
