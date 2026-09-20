import {useEffect, useMemo, useState} from "react";
import {useSearchParams} from "react-router-dom";
import {fetchAnalytics} from "../../Service/Analytics.js";
import {formatCurrency} from "../../util/orderFormat.js";
import AnalyticsSections from "./AnalyticsSections.jsx";
import PageShell from "../../ui/PageShell.jsx";
import PageHeader from "../../ui/PageHeader.jsx";
import StatCard from "../../ui/StatCard.jsx";
import Select from "../../ui/Select.jsx";
import Input from "../../ui/Input.jsx";
import Button from "../../ui/Button.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import EmptyState from "../../ui/EmptyState.jsx";

const DEFAULT_RANGE = "7d";
const RANGES = [
    {value: "today", label: "Today"},
    {value: "7d", label: "7 days"},
    {value: "30d", label: "30 days"},
    {value: "custom", label: "Custom"},
];
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

// Small guard so a malformed response shows a safe error instead of crashing the render.
const isValidResponse = (data) =>
    Boolean(data && typeof data === "object"
        && data.kpis && typeof data.kpis.revenue === "number" && typeof data.kpis.paidOrders === "number"
        && typeof data.kpis.averageOrderValue === "number"
        && data.inventory && typeof data.inventory.lowStock === "number"
        && typeof data.inventory.outOfStock === "number");

const Analytics = () => {
    // The selected range lives in the URL (?range=&from=&to=): refresh and back/forward restore it.
    const [searchParams, setSearchParams] = useSearchParams();
    const rangeParam = searchParams.get("range");
    const range = RANGES.some((r) => r.value === rangeParam) ? rangeParam : DEFAULT_RANGE;
    const from = searchParams.get("from") || "";
    const to = searchParams.get("to") || "";

    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState(null);
    const [reloadToken, setReloadToken] = useState(0);

    // Obvious invalid input only; the backend still enforces the full rules (e.g. maximum span).
    let validationError = null;
    if (range === "custom") {
        if (!from || !to) {
            validationError = "Select both a From and a To date.";
        } else if (!DATE_PATTERN.test(from) || !DATE_PATTERN.test(to)) {
            validationError = "Enter valid dates.";
        } else if (from > to) {
            validationError = "From date must not be after To date.";
        }
    }

    const apiParams = useMemo(
        () => (range === "custom" ? {range, from, to} : {range}),
        [range, from, to]
    );

    useEffect(() => {
        if (validationError) {
            setData(null);
            setLoadError(null);
            setLoading(false);
            return undefined;
        }
        // A newer range selection aborts the previous request, so a slow older response can
        // never overwrite the newest one. Old data is cleared so it is never shown for the new range.
        const controller = new AbortController();
        setLoading(true);
        setLoadError(null);
        setData(null);
        fetchAnalytics(apiParams, controller.signal)
            .then((response) => {
                if (controller.signal.aborted) return;
                if (isValidResponse(response.data)) {
                    setData(response.data);
                } else {
                    setLoadError("The analytics data could not be read. Please try again.");
                }
            })
            .catch((error) => {
                if (error.code === "ERR_CANCELED" || controller.signal.aborted) return;
                console.error(error);
                setLoadError(error.friendlyMessage || "Unable to load analytics");
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false);
            });
        return () => controller.abort();
    }, [apiParams, validationError, reloadToken]);

    const changeRange = (next) => {
        const params = new URLSearchParams();
        params.set("range", next);
        if (next === "custom") {
            if (from) params.set("from", from);
            if (to) params.set("to", to);
        }
        setSearchParams(params);
    };

    const changeDate = (key, value) => {
        const params = new URLSearchParams(searchParams);
        params.set("range", "custom");
        if (value) params.set(key, value); else params.delete(key);
        setSearchParams(params, {replace: true});
    };

    const isZero = data && data.kpis.paidOrders === 0;

    return (
        <PageShell>
            <PageHeader
                kicker="Insights"
                title="Analytics"
                description="Sales performance and stock health for the selected period."
            />

            <section
                className="mb-8 border-2 border-ink bg-surface p-5 shadow-[3px_3px_0_#111827]"
                aria-label="Date range"
            >
                <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                    <Select
                        id="analytics-range"
                        label="Range"
                        value={range}
                        onChange={(e) => changeRange(e.target.value)}
                    >
                        {RANGES.map((r) => (
                            <option key={r.value} value={r.value}>{r.label}</option>
                        ))}
                    </Select>
                    {range === "custom" && (
                        <>
                            <Input
                                id="analytics-from"
                                label="From"
                                type="date"
                                value={from}
                                max={to || undefined}
                                onChange={(e) => changeDate("from", e.target.value)}
                            />
                            <Input
                                id="analytics-to"
                                label="To"
                                type="date"
                                value={to}
                                min={from || undefined}
                                onChange={(e) => changeDate("to", e.target.value)}
                            />
                        </>
                    )}
                </div>
                {validationError && (
                    <p role="alert" className="mt-4 border-2 border-ink bg-danger/15 px-2 py-1 text-sm font-semibold text-danger">
                        {validationError}
                    </p>
                )}
            </section>

            {loading && <LoadingState label="Loading analytics..." />}

            {!loading && loadError && (
                <div role="alert">
                    <EmptyState
                        title="Couldn't load analytics"
                        description={loadError}
                        action={
                            <Button variant="dark" size="sm" onClick={() => setReloadToken((t) => t + 1)}>
                                Retry
                            </Button>
                        }
                    />
                </div>
            )}

            {!loading && !loadError && data && (
                <>
                    <p className="mb-4 text-sm font-bold text-muted">
                        Showing {data.range?.from} to {data.range?.to}
                    </p>
                    <div className="mb-5 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
                        <StatCard
                            accent
                            label="Revenue"
                            value={formatCurrency(data.kpis.revenue)}
                            description="Paid orders only"
                            icon={<i className="bi bi-currency-rupee"></i>}
                        />
                        <StatCard
                            label="Paid Orders"
                            value={data.kpis.paidOrders}
                            description="Orders paid in this period"
                            icon={<i className="bi bi-cart-check"></i>}
                        />
                        <StatCard
                            label="Average Order Value"
                            value={formatCurrency(data.kpis.averageOrderValue)}
                            description="Revenue per paid order"
                            icon={<i className="bi bi-receipt"></i>}
                        />
                    </div>
                    <div className="mb-8 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
                        <StatCard
                            label="Low Stock"
                            value={data.inventory.lowStock}
                            description="Items running low now (ignores the date range)"
                            icon={<i className="bi bi-exclamation-triangle"></i>}
                        />
                        <StatCard
                            label="Out of Stock"
                            value={data.inventory.outOfStock}
                            description="Items unavailable now (ignores the date range)"
                            icon={<i className="bi bi-x-octagon"></i>}
                        />
                    </div>
                    {isZero && (
                        <p className="mb-8 border-2 border-dashed border-ink/40 bg-paper px-4 py-3 text-sm text-muted">
                            No paid orders in this period.
                        </p>
                    )}
                    <AnalyticsSections data={data} />
                </>
            )}
        </PageShell>
    );
};

export default Analytics;
