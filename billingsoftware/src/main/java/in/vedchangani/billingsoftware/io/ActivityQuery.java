package in.vedchangani.billingsoftware.io;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ActivityQuery {
    private AuditAction action;
    private String actorUserId;
    private String actorRole;
    private AuditTargetType targetType;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private Integer page;
    private Integer size;
}
