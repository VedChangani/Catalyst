import {useContext, useEffect, useMemo, useState} from "react";
import {useSearchParams} from "react-router-dom";
import {AppContext} from "../../context/AppContext.jsx";
import {fetchMyActivity, fetchSystemActivity} from "../../Service/ActivityService.js";
import {
    ACTOR_ROLES,
    AUDIT_ACTIONS,
    AUDIT_TARGET_TYPES,
    actionLabel,
    actionTone,
    buildActivityParams,
    formatDetails,
    roleLabel,
} from "../../util/activityFormat.js";
import {ROLE_ADMIN} from "../../util/roles.js";
import PageShell from "../../ui/PageShell.jsx";
import PageHeader from "../../ui/PageHeader.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import EmptyState from "../../ui/EmptyState.jsx";
import Button from "../../ui/Button.jsx";
import Badge from "../../ui/Badge.jsx";
import Input from "../../ui/Input.jsx";
import Select from "../../ui/Select.jsx";
import {formatDate} from "../../util/orderFormat.js";

const FILTER_KEYS = ["action", "actorRole", "targetType", "actorUserId", "dateFrom", "dateTo"];

const readFilters = (searchParams) =>
    Object.fromEntries(FILTER_KEYS.map((key) => [key, (searchParams.get(key) || "").slice(0, 100)]));

// One page for every role. ADMIN sees System Activity (everyone's events, with filters); customers
// and cashiers see their own Activity Log. Which log is returned is decided by the backend from
// the login - this page never sends a user id for the personal log.
const Activity = () => {
    const {auth} = useContext(AppContext);
    const system = auth.role === ROLE_ADMIN;
    const [searchParams, setSearchParams] = useSearchParams();

    const page = Math.max(0, Number.parseInt(searchParams.get("page") ?? "", 10) || 0);
    const filters = useMemo(() => readFilters(searchParams), [searchParams]);
    const [draft, setDraft] = useState(filters);

    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState(null);
    const [reloadToken, setReloadToken] = useState(0);

    // keep the filter form in step with the URL (back/forward, Clear)
    useEffect(() => setDraft(filters), [filters]);

    useEffect(() => {
        const controller = new AbortController();
        setLoading(true);
        setLoadError(null);
        const params = buildActivityParams({system, page, filters});
        (system ? fetchSystemActivity : fetchMyActivity)(params, controller.signal)
            .then((response) => setData(response.data))
            .catch((error) => {
                if (error.code === "ERR_CANCELED") return;
                console.error(error);
                setLoadError(error.friendlyMessage || "Unable to load activity");
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false);
            });
        return () => controller.abort();
    }, [system, page, filters, reloadToken]);

    const applyFilters = (e) => {
        e.preventDefault();
        const next = {};
        FILTER_KEYS.forEach((key) => {
            const value = (draft[key] || "").trim();
            if (value) next[key] = value;
        });
        setSearchParams(next);   // a new filter starts again at the first page
    };

    const clearFilters = () => setSearchParams({});

    const goToPage = (target) => {
        const next = new URLSearchParams(searchParams);
        if (target > 0) next.set("page", String(target));
        else next.delete("page");
        setSearchParams(next);
    };

    const onDraftChange = (e) => {
        const {name, value} = e.target;
        setDraft((current) => ({...current, [name]: value}));
    };

    const header = system ? (
        <PageHeader
            kicker="Admin"
            title="System Activity"
            description="Every recorded action across the store - by customers, cashiers, admins and the system itself. Newest first."
        />
    ) : (
        <PageHeader
            kicker="Account"
            title="Activity Log"
            description="Your own sign-ins, account changes, orders and payments. Newest first."
        />
    );

    const renderBody = () => {
        if (loading) return <LoadingState label="Loading activity..." />;
        if (loadError) {
            return (
                <EmptyState
                    title="Couldn't load activity"
                    description={loadError}
                    action={<Button variant="dark" size="sm" onClick={() => setReloadToken((t) => t + 1)}>Try again</Button>}
                />
            );
        }
        if (!data || data.content.length === 0) {
            return (
                <EmptyState
                    title="No activity found"
                    description={system && FILTER_KEYS.some((key) => filters[key])
                        ? "No events match these filters."
                        : "Recorded actions will show up here."}
                />
            );
        }
        const firstRow = data.page * data.size + 1;
        const lastRow = firstRow + data.content.length - 1;
        return (
            <>
                <div className="overflow-x-auto border-2 border-ink bg-surface shadow-[3px_3px_0_#111827]">
                    <table className="nb-table min-w-[760px]">
                        <thead>
                        <tr>
                            <th>When</th>
                            {system && <th>Actor</th>}
                            <th>Action</th>
                            <th>Target</th>
                            <th>Details</th>
                        </tr>
                        </thead>
                        <tbody>
                        {data.content.map((event) => {
                            const details = formatDetails(event.details);
                            return (
                                <tr key={event.id}>
                                    <td className="whitespace-nowrap">{formatDate(event.createdAt)}</td>
                                    {system && (
                                        <td>
                                            <span className="font-bold">{event.actorName || roleLabel(event.actorRole)}</span><br/>
                                            <small className="text-muted">{roleLabel(event.actorRole)}</small>
                                        </td>
                                    )}
                                    <td><Badge tone={actionTone(event.action)}>{actionLabel(event.action)}</Badge></td>
                                    <td>
                                        {event.targetType ? (
                                            <>
                                                <span className="text-xs font-extrabold uppercase tracking-wide">{event.targetType}</span><br/>
                                                <small className="break-all text-muted">{event.targetId}</small>
                                            </>
                                        ) : "—"}
                                    </td>
                                    <td className="max-w-sm">
                                        {details.length === 0 ? <span className="text-muted">—</span> : (
                                            <ul className="space-y-0.5 text-sm">
                                                {details.map((line) => <li key={line}>{line}</li>)}
                                            </ul>
                                        )}
                                    </td>
                                </tr>
                            );
                        })}
                        </tbody>
                    </table>
                </div>
                <nav className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between" aria-label="Activity pages">
                    <p className="text-sm font-bold text-ink">
                        Showing {firstRow}–{lastRow} of {data.totalElements} events · Page {data.page + 1} of {data.totalPages}
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
            {header}
            {system && (
                <form onSubmit={applyFilters} aria-label="Filter system activity"
                      className="mb-6 border-2 border-ink bg-surface p-4 shadow-[3px_3px_0_#111827]">
                    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                        <Select id="activity-action" label="Action" name="action" value={draft.action} onChange={onDraftChange}>
                            <option value="">All actions</option>
                            {AUDIT_ACTIONS.map((action) => <option key={action} value={action}>{actionLabel(action)}</option>)}
                        </Select>
                        <Select id="activity-role" label="Actor role" name="actorRole" value={draft.actorRole} onChange={onDraftChange}>
                            <option value="">All roles</option>
                            {ACTOR_ROLES.map((role) => <option key={role} value={role}>{roleLabel(role)}</option>)}
                        </Select>
                        <Select id="activity-target" label="Target" name="targetType" value={draft.targetType} onChange={onDraftChange}>
                            <option value="">All targets</option>
                            {AUDIT_TARGET_TYPES.map((type) => <option key={type} value={type}>{type}</option>)}
                        </Select>
                        <Input id="activity-actor" label="Actor user ID" name="actorUserId" value={draft.actorUserId}
                               maxLength={100} placeholder="Exact user ID" onChange={onDraftChange} />
                        <Input id="activity-from" label="From" type="date" name="dateFrom" value={draft.dateFrom} onChange={onDraftChange} />
                        <Input id="activity-to" label="To" type="date" name="dateTo" value={draft.dateTo} onChange={onDraftChange} />
                    </div>
                    <div className="mt-4 flex gap-2">
                        <Button type="submit" variant="primary" size="sm">Apply filters</Button>
                        <Button variant="secondary" size="sm" onClick={clearFilters}>Clear</Button>
                    </div>
                </form>
            )}
            {renderBody()}
        </PageShell>
    );
};

export default Activity;
