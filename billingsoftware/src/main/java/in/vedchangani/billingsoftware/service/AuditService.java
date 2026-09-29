package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.ActivityQuery;
import in.vedchangani.billingsoftware.io.ActivityResponse;
import in.vedchangani.billingsoftware.io.AuditAction;
import in.vedchangani.billingsoftware.io.AuditTargetType;
import in.vedchangani.billingsoftware.io.PagedResponse;

import java.util.Map;

public interface AuditService {

    void record(AuditAction action, AuditTargetType targetType, String targetId, Map<String, ?> details);

    void recordFor(UserEntity actor, AuditAction action, AuditTargetType targetType, String targetId,
                   Map<String, ?> details);

    void recordSystem(AuditAction action, AuditTargetType targetType, String targetId, Map<String, ?> details);

    void recordLoginSuccess(String accountEmail);

    PagedResponse<ActivityResponse> getMyActivity(Integer page, Integer size);

    PagedResponse<ActivityResponse> getSystemActivity(ActivityQuery query);
}
