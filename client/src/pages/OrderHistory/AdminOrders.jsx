import {useCallback, useEffect, useMemo, useState} from "react";
import {useSearchParams} from "react-router-dom";
import {adminOrders} from "../../Service/OrderService.js";
import PageShell from "../../ui/PageShell.jsx";
import PageHeader from "../../ui/PageHeader.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import EmptyState from "../../ui/EmptyState.jsx";
import Badge from "../../ui/Badge.jsx";
import Button from "../../ui/Button.jsx";
import Input from "../../ui/Input.jsx";
import Select from "../../ui/Select.jsx";
import {formatDate, orderStatusTone, paymentStatusTone} from "../../util/orderFormat.js";

// Values the backend (GET /admin/orders) actually accepts. The URL is untrusted input, so anything
// outside these lists is dropped before it can reach the API.
const CHANNELS = ["ONLINE", "POS"];
const ORDER_STATUSES = ["PENDING_PAYMENT", "PAID", "PAYMENT_FAILED", "CANCELLED"];
const PAYMENT_METHODS = ["CASH", "UPI"];
const PAYMENT_STATUSES = ["PENDING", "COMPLETED", "FAILED"];
const PAGE_SIZES = [10, 20, 50, 100];
const DEFAULT_PAGE_SIZE = 20;
const DEFAULT_SORT = "newest";
// UI key -> backend sort value (only createdAt / grandTotal are exposed).
const SORTS = {
    newest: {label: "Newest first", value: "createdAt,desc"},
    oldest: {label: "Oldest first", value: "createdAt,asc"},
    highest: {label: "Highest amount", value: "grandTotal,desc"},
    lowest: {label: "Lowest amount", value: "grandTotal,asc"},
};
const DEBOUNCE_MS = 400;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

const pickOne = (searchParams, key, allowed) => {
    const value = searchParams.get(key);
    return allowed.includes(value) ? value : "";
};

const pickDate = (searchParams, key) => {
    const value = searchParams.get(key);
    return value && DATE_PATTERN.test(value) ? value : "";
};

const pickAmount = (searchParams, key) => {
    const value = searchParams.get(key);
    return value !== null && value.trim() !== "" && Number.isFinite(Number(value)) && Number(value) >= 0 ? value : "";
};

const pickId = (searchParams, key) => (searchParams.get(key) || "").slice(0, 100);

const parseFilters = (searchParams) => {
    const page = Number.parseInt(searchParams.get("page") ?? "", 10);
    const size = Number.parseInt(searchParams.get("size") ?? "", 10);
    const sort = searchParams.get("sort");
    return {
        search: (searchParams.get("search") || "").trim().slice(0, 100),
        salesChannel: pickOne(searchParams, "salesChannel", CHANNELS),
        orderStatus: pickOne(searchParams, "orderStatus", ORDER_STATUSES),
        paymentMethod: pickOne(searchParams, "paymentMethod", PAYMENT_METHODS),
        paymentStatus: pickOne(searchParams, "paymentStatus", PAYMENT_STATUSES),
        dateFrom: pickDate(searchParams, "dateFrom"),
        dateTo: pickDate(searchParams, "dateTo"),
        minAmount: pickAmount(searchParams, "minAmount"),
        maxAmount: pickAmount(searchParams, "maxAmount"),
        customerUserId: pickId(searchParams, "customerUserId"),
        createdByUserId: pickId(searchParams, "createdByUserId"),
        sort: SORTS[sort] ? sort : DEFAULT_SORT,
        page: Number.isInteger(page) && page >= 0 ? page : 0,
        size: PAGE_SIZES.includes(size) ? size : DEFAULT_PAGE_SIZE,
    };
};

// Pushes a locally-edited value into the URL only after the user pauses typing.
const useDebouncedCommit = (draft, committed, commit) => {
    useEffect(() => {
        if (draft === committed) {
            return undefined;
        }
        const timer = setTimeout(() => commit(draft), DEBOUNCE_MS);
        return () => clearTimeout(timer);
    }, [draft, committed, commit]);
};

const AdminOrders = () => {
    const [searchParams, setSearchParams] = useSearchParams();
    const filters = useMemo(() => parseFilters(searchParams), [searchParams]);

    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState(null);
    const [reloadToken, setReloadToken] = useState(0);

    const [searchDraft, setSearchDraft] = useState(filters.search);
    const [minDraft, setMinDraft] = useState(filters.minAmount);
    const [maxDraft, setMaxDraft] = useState(filters.maxAmount);

    // Any filter change returns to the first page; a plain page change keeps every filter.
    const update = useCallback((changes, {resetPage = true, replace = false} = {}) => {
        setSearchParams((previous) => {
            const next = new URLSearchParams(previous);
            Object.entries(changes).forEach(([key, value]) => {
                if (value === "" || value === null || value === undefined) {
                    next.delete(key);
                } else {
                    next.set(key, String(value));
                }
            });
            if (resetPage) {
                next.delete("page");
            }
            return next;
        }, {replace});
    }, [setSearchParams]);

    const commitSearch = useCallback((value) => update({search: value.trim()}, {replace: true}), [update]);
    const commitMin = useCallback((value) => update({minAmount: value.trim()}, {replace: true}), [update]);
    const commitMax = useCallback((value) => update({maxAmount: value.trim()}, {replace: true}), [update]);
    useDebouncedCommit(searchDraft, filters.search, commitSearch);
    useDebouncedCommit(minDraft, filters.minAmount, commitMin);
    useDebouncedCommit(maxDraft, filters.maxAmount, commitMax);

    // Back/forward navigation or "clear" changes the URL: mirror it into the text inputs.
    useEffect(() => setSearchDraft(filters.search), [filters.search]);
    useEffect(() => setMinDraft(filters.minAmount), [filters.minAmount]);
    useEffect(() => setMaxDraft(filters.maxAmount), [filters.maxAmount]);

    const dateRangeError = filters.dateFrom && filters.dateTo && filters.dateFrom > filters.dateTo
        ? "From date must not be after To date" : null;
    const amountRangeError = filters.minAmount !== "" && filters.maxAmount !== ""
        && Number(filters.minAmount) > Number(filters.maxAmount)
        ? "Min amount must not be greater than Max amount" : null;
    const rangeError = dateRangeError || amountRangeError;

    const apiParams = useMemo(() => ({
        page: filters.page,
        size: filters.size,
        sort: SORTS[filters.sort].value,
        search: filters.search,
        salesChannel: filters.salesChannel,
        orderStatus: filters.orderStatus,
        paymentMethod: filters.paymentMethod,
        paymentStatus: filters.paymentStatus,
        dateFrom: filters.dateFrom,
        dateTo: filters.dateTo,
        minAmount: filters.minAmount,
        maxAmount: filters.maxAmount,
        customerUserId: filters.customerUserId,
        createdByUserId: filters.createdByUserId,
    }), [filters]);

    useEffect(() => {
        if (rangeError) {
            return undefined;
        }
        // Each request is cancelled when a newer filter/page selection replaces it, so a slow
        // older response can never overwrite the newer result.
        const controller = new AbortController();
        setLoading(true);
        setLoadError(null);
        adminOrders(apiParams, controller.signal)
            .then((response) => setData(response.data))
            .catch((error) => {
                if (error.code === "ERR_CANCELED") {
                    return;
                }
                console.error(error);
                setData(null);
                setLoadError(error.friendlyMessage || "Unable to load orders");
            })
            .finally(() => {
                if (!controller.signal.aborted) {
                    setLoading(false);
                }
            });
        return () => controller.abort();
    }, [apiParams, rangeError, reloadToken]);

    const hasActiveFilters = Boolean(
        filters.search || filters.salesChannel || filters.orderStatus || filters.paymentMethod
        || filters.paymentStatus || filters.dateFrom || filters.dateTo || filters.minAmount !== ""
        || filters.maxAmount !== "" || filters.customerUserId || filters.createdByUserId
    );

    const clearFilters = () => {
        setSearchDraft("");
        setMinDraft("");
        setMaxDraft("");
        // keeps the chosen sort and page size; only the filters are reset
        const keep = {};
        if (filters.sort !== DEFAULT_SORT) keep.sort = filters.sort;
        if (filters.size !== DEFAULT_PAGE_SIZE) keep.size = filters.size;
        setSearchParams(keep);
    };

    const goToPage = (page) => update({page: page > 0 ? page : ""}, {resetPage: false});

    const customerCell = (order) => {
        if (order.customer) {
            return (
                <>
                    <button
                        type="button"
                        className="text-left font-bold underline decoration-dotted underline-offset-2 hover:text-primary"
                        title="Show only this customer's orders"
                        onClick={() => update({customerUserId: order.customer.userId})}
                    >
                        {order.customer.name}
                    </button>
                    <br/>
                    <small className="text-muted">{order.phoneNumber}</small>
                </>
            );
        }
        if (order.salesChannel === "POS") {
            return (
                <>
                    <span className="font-bold">Walk-in</span>
                    {order.customerName && <span> ({order.customerName})</span>}
                    <br/>
                    <small className="text-muted">{order.phoneNumber}</small>
                </>
            );
        }
        // legacy order with no linked account: show the billing name recorded on the order
        return (
            <>
                {order.customerName || "—"} <br/>
                <small className="text-muted">{order.phoneNumber}</small>
            </>
        );
    };

    const createdByCell = (order) => {
        if (order.createdBy) {
            return (
                <button
                    type="button"
                    className="text-left font-bold underline decoration-dotted underline-offset-2 hover:text-primary"
                    title="Show only sales entered by this person"
                    onClick={() => update({createdByUserId: order.createdBy.userId})}
                >
                    {order.createdBy.name}
                </button>
            );
        }
        return <span className="text-muted">{order.salesChannel === "ONLINE" ? "Online" : "—"}</span>;
    };

    const channelBadge = (channel) => {
        if (channel === "ONLINE") return <Badge tone="info">Online</Badge>;
        if (channel === "POS") return <Badge tone="warning">POS</Badge>;
        return <Badge tone="muted">Unknown</Badge>;
    };

    const renderResults = () => {
        if (rangeError) {
            return <EmptyState title="Check your filters" description={rangeError}/>;
        }
        if (loading) {
            return <LoadingState label="Loading orders..."/>;
        }
        if (loadError) {
            return (
                <EmptyState
                    title="Couldn't load orders"
                    description={loadError}
                    action={
                        <div className="flex flex-wrap gap-2">
                            <Button variant="dark" size="sm" onClick={() => setReloadToken((token) => token + 1)}>
                                Try again
                            </Button>
                            {hasActiveFilters && (
                                <Button variant="secondary" size="sm" onClick={clearFilters}>Clear filters</Button>
                            )}
                        </div>
                    }
                />
            );
        }
        if (!data) {
            return null;
        }
        if (data.content.length === 0) {
            if (data.totalElements > 0) {
                // e.g. a bookmarked page number that no longer exists
                return (
                    <EmptyState
                        title="That page is empty"
                        description="There are no orders on this page."
                        action={<Button variant="dark" size="sm" onClick={() => goToPage(0)}>Go to first page</Button>}
                    />
                );
            }
            return hasActiveFilters ? (
                <EmptyState
                    title="No orders match these filters"
                    description="Try widening the search or clear the filters."
                    action={<Button variant="dark" size="sm" onClick={clearFilters}>Clear filters</Button>}
                />
            ) : (
                <EmptyState title="No orders found" description="Completed checkouts will show up in this ledger."/>
            );
        }

        const firstRow = data.page * data.size + 1;
        const lastRow = firstRow + data.content.length - 1;
        return (
            <>
                <div className="overflow-x-auto border-2 border-ink bg-surface shadow-[3px_3px_0_#111827]">
                    <table className="nb-table min-w-[1100px]">
                        <thead>
                        <tr>
                            <th>Order Id</th>
                            <th>Customer</th>
                            <th>Channel</th>
                            <th>Created By</th>
                            <th>Total</th>
                            <th>Payment</th>
                            <th>Order Status</th>
                            <th>Payment Status</th>
                            <th>Date</th>
                        </tr>
                        </thead>
                        <tbody>
                        {data.content.map((order) => (
                            <tr key={order.orderId}>
                                <td className="font-bold">{order.orderId}</td>
                                <td>{customerCell(order)}</td>
                                <td>{channelBadge(order.salesChannel)}</td>
                                <td>{createdByCell(order)}</td>
                                <td className="text-lg font-extrabold">₹{order.grandTotal}</td>
                                <td>
                                    <Badge tone={(order.paymentMethod || "").toLowerCase() === "cash" ? "success" : "info"}>
                                        {order.paymentMethod}
                                    </Badge>
                                </td>
                                <td>
                                    <Badge tone={orderStatusTone(order.orderStatus)}>
                                        {(order.orderStatus || "").replace("_", " ")}
                                    </Badge>
                                </td>
                                <td>
                                    <Badge tone={paymentStatusTone(order.paymentStatus)}>
                                        {order.paymentStatus || "UNKNOWN"}
                                    </Badge>
                                </td>
                                <td>{formatDate(order.createdAt)}</td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>
                <nav className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between" aria-label="Order pages">
                    <p className="text-sm font-bold text-ink">
                        Showing {firstRow}–{lastRow} of {data.totalElements} orders · Page {data.page + 1} of {data.totalPages}
                    </p>
                    <div className="flex items-center gap-2">
                        <Button variant="secondary" size="sm" disabled={data.first} onClick={() => goToPage(data.page - 1)}>
                            Previous
                        </Button>
                        <Button variant="secondary" size="sm" disabled={data.last} onClick={() => goToPage(data.page + 1)}>
                            Next
                        </Button>
                    </div>
                </nav>
            </>
        );
    };

    return (
        <PageShell wide>
            <PageHeader
                kicker="Sales"
                title="All Orders"
                description="Online and in-store (POS) bills with payment status. Filters, sorting and paging are applied by the server."
            />

            <div className="mb-6 border-2 border-ink bg-surface p-4 shadow-[3px_3px_0_#111827]">
                <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                    <Input
                        id="order-search"
                        label="Search"
                        type="search"
                        maxLength={100}
                        placeholder="Order ID, name or phone"
                        value={searchDraft}
                        onChange={(event) => setSearchDraft(event.target.value)}
                    />
                    <Select
                        id="order-channel"
                        label="Sales channel"
                        value={filters.salesChannel}
                        onChange={(event) => update({salesChannel: event.target.value})}
                    >
                        <option value="">All</option>
                        {CHANNELS.map((channel) => <option key={channel} value={channel}>{channel}</option>)}
                    </Select>
                    <Select
                        id="order-status"
                        label="Order status"
                        value={filters.orderStatus}
                        onChange={(event) => update({orderStatus: event.target.value})}
                    >
                        <option value="">All</option>
                        {ORDER_STATUSES.map((status) => <option key={status} value={status}>{status.replace("_", " ")}</option>)}
                    </Select>
                    <Select
                        id="payment-status"
                        label="Payment status"
                        value={filters.paymentStatus}
                        onChange={(event) => update({paymentStatus: event.target.value})}
                    >
                        <option value="">All</option>
                        {PAYMENT_STATUSES.map((status) => <option key={status} value={status}>{status}</option>)}
                    </Select>
                    <Select
                        id="payment-method"
                        label="Payment method"
                        value={filters.paymentMethod}
                        onChange={(event) => update({paymentMethod: event.target.value})}
                    >
                        <option value="">All</option>
                        {PAYMENT_METHODS.map((method) => <option key={method} value={method}>{method}</option>)}
                    </Select>
                    <Input
                        id="date-from"
                        label="From date"
                        type="date"
                        value={filters.dateFrom}
                        error={dateRangeError}
                        onChange={(event) => update({dateFrom: event.target.value})}
                    />
                    <Input
                        id="date-to"
                        label="To date"
                        type="date"
                        value={filters.dateTo}
                        onChange={(event) => update({dateTo: event.target.value})}
                    />
                    <Input
                        id="min-amount"
                        label="Min amount (₹)"
                        type="number"
                        min="0"
                        step="any"
                        value={minDraft}
                        error={amountRangeError}
                        onChange={(event) => setMinDraft(event.target.value)}
                    />
                    <Input
                        id="max-amount"
                        label="Max amount (₹)"
                        type="number"
                        min="0"
                        step="any"
                        value={maxDraft}
                        onChange={(event) => setMaxDraft(event.target.value)}
                    />
                    <Select
                        id="order-sort"
                        label="Sort by"
                        value={filters.sort}
                        onChange={(event) => update({sort: event.target.value === DEFAULT_SORT ? "" : event.target.value})}
                    >
                        {Object.entries(SORTS).map(([key, option]) => <option key={key} value={key}>{option.label}</option>)}
                    </Select>
                    <Select
                        id="order-page-size"
                        label="Per page"
                        value={filters.size}
                        onChange={(event) => update({size: Number(event.target.value) === DEFAULT_PAGE_SIZE ? "" : event.target.value})}
                    >
                        {PAGE_SIZES.map((size) => <option key={size} value={size}>{size}</option>)}
                    </Select>
                    <div className="flex items-end">
                        <Button variant="dark" className="w-full" disabled={!hasActiveFilters} onClick={clearFilters}>
                            Clear filters
                        </Button>
                    </div>
                </div>
                {(filters.customerUserId || filters.createdByUserId) && (
                    <div className="mt-4 flex flex-wrap gap-2">
                        {filters.customerUserId && (
                            <Badge tone="info">
                                One customer only
                                <button type="button" className="ml-2" aria-label="Remove customer filter"
                                        onClick={() => update({customerUserId: ""})}>✕</button>
                            </Badge>
                        )}
                        {filters.createdByUserId && (
                            <Badge tone="warning">
                                One creator only
                                <button type="button" className="ml-2" aria-label="Remove creator filter"
                                        onClick={() => update({createdByUserId: ""})}>✕</button>
                            </Badge>
                        )}
                    </div>
                )}
            </div>

            {renderResults()}
        </PageShell>
    );
}

export default AdminOrders;
