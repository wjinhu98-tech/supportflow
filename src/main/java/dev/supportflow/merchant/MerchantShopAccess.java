package dev.supportflow.merchant;

/** A freshly checked server-side authorization, never deserialized from client input. */
public record MerchantShopAccess(long staffId, long shopId) { }
