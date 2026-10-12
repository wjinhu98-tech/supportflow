package dev.supportflow.common.security.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * 客户访问业务数据时使用的身份范围。
 *
 * <p>正式请求中，应由服务端根据认证结果创建。</p>
 */
public record CustomerIdentity(
        long tenantId,
        long customerId
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public CustomerIdentity {
        if (tenantId <= 0) {
            throw new IllegalArgumentException(
                    "tenantId must be positive"
            );
        }

        if (customerId <= 0) {
            throw new IllegalArgumentException(
                    "customerId must be positive"
            );
        }
    }
}