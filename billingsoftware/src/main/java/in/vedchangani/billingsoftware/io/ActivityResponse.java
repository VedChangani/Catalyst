package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

// One activity entry as returned by GET /activity/me and GET /admin/activity. Only safe fields:
// no internal database ids, no credentials/tokens/signatures of any kind.
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ActivityResponse {
    private Long id;
    private LocalDateTime createdAt;
    // public user id of the actor (null for SYSTEM events)
    private String actorUserId;
    private String actorName;
    private String actorRole;
    private AuditAction action;
    private AuditTargetType targetType;
    private String targetId;
    private Map<String, Object> details;
}
