package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ActivityResponse {
    private Long id;
    private LocalDateTime createdAt;
    private String actorUserId;
    private String actorName;
    private String actorRole;
    private AuditAction action;
    private AuditTargetType targetType;
    private String targetId;
    private Map<String, Object> details;
}
