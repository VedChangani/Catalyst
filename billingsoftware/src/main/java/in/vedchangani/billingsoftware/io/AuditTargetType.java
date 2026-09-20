package in.vedchangani.billingsoftware.io;

// What kind of record an audit event is about; the event's targetId is that record's public id.
public enum AuditTargetType {
    ACCOUNT,
    CASHIER,
    ORDER,
    ITEM,
    CATEGORY
}
