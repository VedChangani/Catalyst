package in.vedchangani.billingsoftware.service;

import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.io.ActivityQuery;
import in.vedchangani.billingsoftware.io.ActivityResponse;
import in.vedchangani.billingsoftware.io.AuditAction;
import in.vedchangani.billingsoftware.io.AuditTargetType;
import in.vedchangani.billingsoftware.io.PagedResponse;

import java.util.Map;

/**
 * The single place audit events are written and read.
 *
 * Writing: business services call one of the record methods at the point a state change has
 * actually happened. Recording joins the caller's transaction (default propagation), so if the
 * business transaction rolls back, its audit event rolls back with it. The actor always comes from
 * server-side state - the authenticated principal, or an account the server itself just
 * created/authenticated - never from request data. `details` must be small, server-built metadata;
 * keys that look like credentials are dropped regardless.
 */
public interface AuditService {

    /** Actor = the currently authenticated user. */
    void record(AuditAction action, AuditTargetType targetType, String targetId, Map<String, ?> details);

    /** Actor = an account the server has already resolved (e.g. the one just registered). */
    void recordFor(UserEntity actor, AuditAction action, AuditTargetType targetType, String targetId,
                   Map<String, ?> details);

    /** No human actor: a state change the system made on its own (actorRole = SYSTEM). */
    void recordSystem(AuditAction action, AuditTargetType targetType, String targetId, Map<String, ?> details);

    /** AUTH_LOGIN_SUCCESS for the account that has just authenticated successfully. */
    void recordLoginSuccess(String accountEmail);

    /** The authenticated caller's own events only, newest first. */
    PagedResponse<ActivityResponse> getMyActivity(Integer page, Integer size);

    /** ADMIN: every recorded event, newest first, with optional server-side filters. */
    PagedResponse<ActivityResponse> getSystemActivity(ActivityQuery query);
}
