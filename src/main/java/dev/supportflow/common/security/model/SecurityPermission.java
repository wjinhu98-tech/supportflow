package dev.supportflow.common.security.model;

import org.springframework.security.core.GrantedAuthority;

/**
 * 应用支持的操作权限。
 */
public enum SecurityPermission implements GrantedAuthority {

    ORDER_READ("order:read");

    private final String authority;

    SecurityPermission(String authority) {
        this.authority = authority;
    }

    @Override
    public String getAuthority() {
        return authority;
    }
}