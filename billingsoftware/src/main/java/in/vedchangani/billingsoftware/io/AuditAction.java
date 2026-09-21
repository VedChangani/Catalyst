package in.vedchangani.billingsoftware.io;

// Controlled set of audit events. Only meaningful state changes and security-relevant events are
// recorded - never ordinary reads. Values are created by server code only, never from requests.
public enum AuditAction {
    AUTH_LOGIN_SUCCESS,
    ACCOUNT_REGISTERED,

    PROFILE_UPDATED,
    PASSWORD_CHANGED,
    // Customer reset their own password with an emailed one-time code (forgot-password flow).
    PASSWORD_RESET_COMPLETED,

    CASHIER_CREATED,
    CASHIER_DEACTIVATED,
    CASHIER_REACTIVATED,
    CASHIER_PASSWORD_RESET,

    ONLINE_ORDER_CREATED,
    POS_ORDER_CREATED,
    PAYMENT_VERIFIED,
    PAYMENT_FAILED,
    ORDER_CANCELLED,
    // No longer emitted (orders can no longer be deleted). Kept so audit records written before
    // that change still load and display.
    ORDER_DELETED,

    ITEM_CREATED,
    ITEM_UPDATED,
    ITEM_DELETED,
    INVENTORY_ADJUSTED,

    CATEGORY_CREATED,
    CATEGORY_DELETED
}
