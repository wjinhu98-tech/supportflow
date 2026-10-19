package dev.supportflow.common.security.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * 平台全局买家身份，不绑定某一家商铺。
 *
 * <p>正式请求中，应由服务端根据认证结果创建。</p>
 */
public record CustomerIdentity(
        long buyerId
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public CustomerIdentity {
        if (buyerId <= 0) {
            throw new IllegalArgumentException(
                    "buyerId must be positive"
            );
        }
    }
}
