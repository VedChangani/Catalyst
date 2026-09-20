package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

// ADMIN system-activity filters (all optional) plus paging. These only narrow the already
// authorized system-wide result; they never grant access to anything.
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ActivityQuery {
    private AuditAction action;
    // public user id of the actor
    private String actorUserId;
    private String actorRole;
    private AuditTargetType targetType;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private Integer page;
    private Integer size;
}
