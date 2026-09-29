package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.io.ActivityQuery;
import in.vedchangani.billingsoftware.io.ActivityResponse;
import in.vedchangani.billingsoftware.io.AuditAction;
import in.vedchangani.billingsoftware.io.AuditTargetType;
import in.vedchangani.billingsoftware.io.PagedResponse;
import in.vedchangani.billingsoftware.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
public class ActivityController {

    private final AuditService auditService;

    @GetMapping("/activity/me")
    public PagedResponse<ActivityResponse> myActivity(@RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return auditService.getMyActivity(page, size);
    }

    @GetMapping("/admin/activity")
    public PagedResponse<ActivityResponse> systemActivity(
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) String actorUserId,
            @RequestParam(required = false) String actorRole,
            @RequestParam(required = false) AuditTargetType targetType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return auditService.getSystemActivity(ActivityQuery.builder()
                .action(action).actorUserId(actorUserId).actorRole(actorRole).targetType(targetType)
                .dateFrom(dateFrom).dateTo(dateTo).page(page).size(size)
                .build());
    }
}
