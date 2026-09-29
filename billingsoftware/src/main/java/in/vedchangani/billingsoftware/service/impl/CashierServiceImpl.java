package in.vedchangani.billingsoftware.service.impl;

import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.exception.ResourceNotFoundException;
import in.vedchangani.billingsoftware.io.AuditAction;
import in.vedchangani.billingsoftware.io.AuditTargetType;
import in.vedchangani.billingsoftware.io.CashierCreateRequest;
import in.vedchangani.billingsoftware.io.CashierResponse;
import in.vedchangani.billingsoftware.io.OrderStatus;
import in.vedchangani.billingsoftware.io.SalesChannel;
import in.vedchangani.billingsoftware.io.UserResponse;
import in.vedchangani.billingsoftware.repository.CashierSalesStats;
import in.vedchangani.billingsoftware.repository.OrderEntityRepository;
import in.vedchangani.billingsoftware.repository.UserRepository;
import in.vedchangani.billingsoftware.service.AuditService;
import in.vedchangani.billingsoftware.service.CashierService;
import in.vedchangani.billingsoftware.service.UserService;
import in.vedchangani.billingsoftware.util.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CashierServiceImpl implements CashierService {

    private static final String CASHIER_ROLE = "ROLE_CASHIER";

    private final UserService userService;
    private final UserRepository userRepository;
    private final OrderEntityRepository orderEntityRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Override
    @Transactional
    public CashierResponse createCashier(CashierCreateRequest request) {
        UserResponse created = userService.createCashier(request);
        auditService.record(AuditAction.CASHIER_CREATED, AuditTargetType.CASHIER, created.getUserId(), Map.of());
        return toResponse(findCashier(created.getUserId()), null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CashierResponse> listCashiers() {
        List<UserEntity> cashiers = userRepository.findByRoleOrderByNameAsc(CASHIER_ROLE);
        if (cashiers.isEmpty()) {
            return List.of();
        }
        Map<Long, CashierSalesStats> statsByCashier = orderEntityRepository
                .cashierSalesStats(cashiers.stream().map(UserEntity::getId).toList(), SalesChannel.POS, OrderStatus.PAID)
                .stream()
                .collect(Collectors.toMap(CashierSalesStats::getCashierId, Function.identity()));
        return cashiers.stream()
                .map(cashier -> toResponse(cashier, statsByCashier.get(cashier.getId())))
                .toList();
    }

    @Override
    @Transactional
    public CashierResponse setEnabled(String cashierUserId, boolean enabled) {
        UserEntity before = findCashier(cashierUserId);
        Long id = before.getId();
        boolean wasEnabled = before.isAccountEnabled();
        if (enabled) {
            userRepository.enable(id);
        } else {
            userRepository.disableAndRevokeTokens(id);
        }
        if (wasEnabled != enabled) {
            auditService.record(enabled ? AuditAction.CASHIER_REACTIVATED : AuditAction.CASHIER_DEACTIVATED,
                    AuditTargetType.CASHIER, cashierUserId,
                    Map.of("from", wasEnabled ? "ACTIVE" : "INACTIVE", "to", enabled ? "ACTIVE" : "INACTIVE"));
        }
        UserEntity cashier = findCashier(cashierUserId);
        List<CashierSalesStats> stats = orderEntityRepository
                .cashierSalesStats(List.of(cashier.getId()), SalesChannel.POS, OrderStatus.PAID);
        return toResponse(cashier, stats.isEmpty() ? null : stats.get(0));
    }

    @Override
    @Transactional
    public void resetPassword(String cashierUserId, String newPassword) {
        UserEntity cashier = findCashier(cashierUserId);
        userRepository.updatePasswordAndRevokeTokens(cashier.getId(), passwordEncoder.encode(newPassword));
        auditService.record(AuditAction.CASHIER_PASSWORD_RESET, AuditTargetType.CASHIER, cashierUserId, Map.of());
    }

    private UserEntity findCashier(String cashierUserId) {
        return userRepository.findByUserId(cashierUserId)
                .filter(user -> CASHIER_ROLE.equals(user.getRole()))
                .orElseThrow(() -> new ResourceNotFoundException("Cashier not found"));
    }

    private CashierResponse toResponse(UserEntity cashier, CashierSalesStats stats) {
        return CashierResponse.builder()
                .userId(cashier.getUserId())
                .name(cashier.getName())
                .email(cashier.getEmail())
                .mobile(cashier.getMobile())
                .enabled(cashier.isAccountEnabled())
                .createdAt(cashier.getCreatedAt())
                .ordersProcessed(stats == null || stats.getOrdersProcessed() == null ? 0 : stats.getOrdersProcessed())
                .posRevenue(Money.forResponse(stats == null ? BigDecimal.ZERO : Money.zeroIfNull(stats.getPosRevenue())))
                .lastPosSaleAt(stats == null ? null : stats.getLastSaleAt())
                .build();
    }
}
