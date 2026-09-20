package in.vedchangani.billingsoftware.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.vedchangani.billingsoftware.entity.AuditLogEntity;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.exception.ResourceNotFoundException;
import in.vedchangani.billingsoftware.io.ActivityQuery;
import in.vedchangani.billingsoftware.io.ActivityResponse;
import in.vedchangani.billingsoftware.io.AuditAction;
import in.vedchangani.billingsoftware.io.AuditTargetType;
import in.vedchangani.billingsoftware.io.PagedResponse;
import in.vedchangani.billingsoftware.repository.AuditLogRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_DETAILS_LENGTH = 1000;
    private static final Set<String> FILTERABLE_ROLES =
            Set.of("ROLE_USER", "ROLE_CASHIER", "ROLE_ADMIN", AuditLogEntity.SYSTEM_ROLE);
    // Defence in depth: even though every call site builds its own metadata, a key that looks like
    // credential material never reaches the database.
    private static final Pattern SENSITIVE_KEY =
            Pattern.compile("(?i).*(password|passwd|hash|token|secret|signature|authorization|credential).*");
    // Newest first, id as the deterministic tie-breaker for events in the same microsecond.
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    // ---- writing ----

    @Override
    @Transactional
    public void record(AuditAction action, AuditTargetType targetType, String targetId, Map<String, ?> details) {
        recordFor(currentUser(), action, targetType, targetId, details);
    }

    @Override
    @Transactional
    public void recordFor(UserEntity actor, AuditAction action, AuditTargetType targetType, String targetId,
                          Map<String, ?> details) {
        if (actor == null || actor.getId() == null) {
            throw new IllegalStateException("An audit event needs a persisted actor");
        }
        save(AuditLogEntity.builder()
                .actorUserId(actor.getId())
                .actorPublicId(actor.getUserId())
                .actorName(actor.getName())
                .actorRole(actor.getRole())
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .details(serialize(details)));
    }

    @Override
    @Transactional
    public void recordSystem(AuditAction action, AuditTargetType targetType, String targetId, Map<String, ?> details) {
        save(AuditLogEntity.builder()
                .actorRole(AuditLogEntity.SYSTEM_ROLE)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .details(serialize(details)));
    }

    @Override
    @Transactional
    public void recordLoginSuccess(String accountEmail) {
        UserEntity account = userRepository.findByEmail(accountEmail)
                .orElseThrow(() -> new IllegalStateException("Authenticated account not found"));
        recordFor(account, AuditAction.AUTH_LOGIN_SUCCESS, AuditTargetType.ACCOUNT, account.getUserId(), Map.of());
    }

    private void save(AuditLogEntity.AuditLogEntityBuilder entry) {
        auditLogRepository.save(entry.build());
    }

    private String serialize(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        details.forEach((key, value) -> {
            if (key == null || SENSITIVE_KEY.matcher(key).matches()) {
                log.warn("Dropped a sensitive-looking key from audit metadata");
                return;
            }
            safe.put(key, value instanceof Enum<?> e ? e.name() : value);
        });
        if (safe.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(safe);
            return json.length() <= MAX_DETAILS_LENGTH ? json : "{\"truncated\":true}";
        } catch (JsonProcessingException ex) {
            log.warn("Could not serialize audit metadata for an event");
            return null;
        }
    }

    // ---- reading ----

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<ActivityResponse> getMyActivity(Integer page, Integer size) {
        UserEntity me = currentUser();
        // Ownership is the query itself: only rows whose actor is the authenticated caller.
        return toPage(auditLogRepository.findByActorUserId(me.getId(), pageable(page, size)));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<ActivityResponse> getSystemActivity(ActivityQuery query) {
        Pageable pageable = pageable(query.getPage(), query.getSize());

        String actorRole = blankToNull(query.getActorRole());
        if (actorRole != null) {
            actorRole = actorRole.toUpperCase();
            if (!FILTERABLE_ROLES.contains(actorRole)) {
                throw new IllegalArgumentException("actorRole must be one of ROLE_USER, ROLE_CASHIER, ROLE_ADMIN or SYSTEM");
            }
        }
        if (query.getDateFrom() != null && query.getDateTo() != null && query.getDateFrom().isAfter(query.getDateTo())) {
            throw new IllegalArgumentException("dateFrom must not be after dateTo");
        }

        Long actorId = null;
        String actorPublicId = blankToNull(query.getActorUserId());
        if (actorPublicId != null) {
            UserEntity actor = userRepository.findByUserId(actorPublicId).orElse(null);
            if (actor == null) {
                // an unknown actor has no events - never fall through to "no actor filter"
                return toPage(Page.empty(pageable));
            }
            actorId = actor.getId();
        }

        return toPage(auditLogRepository.search(
                query.getAction(),
                actorId,
                actorRole,
                query.getTargetType(),
                query.getDateFrom() == null ? null : query.getDateFrom().atStartOfDay(),
                query.getDateTo() == null ? null : query.getDateTo().plusDays(1).atStartOfDay(),
                pageable));
    }

    private static Pageable pageable(Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_PAGE_SIZE : size;
        if (p < 0) {
            throw new IllegalArgumentException("page must be 0 or greater");
        }
        if (s < 1 || s > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(p, s, NEWEST_FIRST);
    }

    private PagedResponse<ActivityResponse> toPage(Page<AuditLogEntity> page) {
        return PagedResponse.<ActivityResponse>builder()
                .content(page.getContent().stream().map(this::toResponse).toList())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .build();
    }

    private ActivityResponse toResponse(AuditLogEntity entry) {
        return ActivityResponse.builder()
                .id(entry.getId())
                .createdAt(entry.getCreatedAt())
                .actorUserId(entry.getActorPublicId())
                .actorName(entry.getActorName())
                .actorRole(entry.getActorRole())
                .action(entry.getAction())
                .targetType(entry.getTargetType())
                .targetId(entry.getTargetId())
                .details(deserialize(entry.getDetails()))
                .build();
    }

    private Map<String, Object> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private UserEntity currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("No authenticated user found");
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
