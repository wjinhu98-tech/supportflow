package dev.supportflow.merchant;

/** Only public shop/merchant display identifiers are returned; internal staff IDs stay on the server. */
public record MerchantShop(long id, String name, long merchantId, String merchantName) { }
