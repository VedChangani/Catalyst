import {useEffect, useRef, useState} from "react";
import {useNavigate} from "react-router-dom";
import toast from "react-hot-toast";
import {createCashier, fetchCashiers, resetCashierPassword, setCashierEnabled} from "../../Service/CashierService.js";
import PageShell from "../../ui/PageShell.jsx";
import PageHeader from "../../ui/PageHeader.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import EmptyState from "../../ui/EmptyState.jsx";
import Button from "../../ui/Button.jsx";
import Badge from "../../ui/Badge.jsx";
import Input from "../../ui/Input.jsx";
import {formatCurrency, formatDate} from "../../util/orderFormat.js";

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const EMPTY_FORM = {name: "", email: "", mobile: "", password: ""};

// Same rules as the backend (and customer registration). The backend re-validates regardless.
const normalizeMobile = (value) => {
    let digits = value.trim().replace(/[\s\-().]/g, "");
    if (digits.startsWith("+91")) {
        digits = digits.slice(3);
    } else if (digits.length === 12 && digits.startsWith("91")) {
        digits = digits.slice(2);
    } else if (digits.length === 11 && digits.startsWith("0")) {
        digits = digits.slice(1);
    }
    return /^[6-9][0-9]{9}$/.test(digits) ? digits : null;
};

const passwordError = (password) => {
    if (!password) return "Password is required";
    if (password.length < 8 || password.length > 72) return "Password must be between 8 and 72 characters";
    if (!/[A-Za-z]/.test(password) || !/[0-9]/.test(password)) {
        return "Password must contain at least one letter and one number";
    }
    return null;
};

const validateCreate = (form) => {
    const errors = {};
    if (!form.name.trim()) errors.name = "Name is required";
    else if (form.name.trim().length > 100) errors.name = "Name must be at most 100 characters";
    if (!form.email.trim()) errors.email = "Email is required";
    else if (!EMAIL_PATTERN.test(form.email.trim())) errors.email = "Enter a valid email address";
    if (!form.mobile.trim()) errors.mobile = "Mobile is required";
    else if (!normalizeMobile(form.mobile)) errors.mobile = "Enter a valid 10-digit Indian mobile number";
    const pwd = passwordError(form.password);
    if (pwd) errors.password = pwd;
    return errors;
};

// ADMIN only (see App.jsx): create cashier accounts, activate/deactivate them, reset their
// password, and jump to their sales in All Orders. Cashiers are never deleted and their role can
// never be changed from here - the backend enforces both.
const ManageCashiers = () => {
    const navigate = useNavigate();
    const [cashiers, setCashiers] = useState([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState(null);
    const [reloadToken, setReloadToken] = useState(0);

    const [form, setForm] = useState(EMPTY_FORM);
    const [formErrors, setFormErrors] = useState({});
    const [creating, setCreating] = useState(false);

    // One row mutation at a time per cashier: a second click while a request is in flight is ignored.
    const [busyId, setBusyId] = useState(null);
    const busyRef = useRef(null);

    // Password reset panel for a single cashier; the typed passwords only ever live in this state.
    const [resetFor, setResetFor] = useState(null);
    const [resetForm, setResetForm] = useState({password: "", confirm: ""});
    const [resetErrors, setResetErrors] = useState({});

    useEffect(() => {
        const controller = new AbortController();
        setLoading(true);
        setLoadError(null);
        fetchCashiers(controller.signal)
            .then((response) => setCashiers(Array.isArray(response.data) ? response.data : []))
            .catch((error) => {
                if (error.code === "ERR_CANCELED") return;
                console.error(error);
                setLoadError(error.friendlyMessage || "Unable to load cashiers");
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false);
            });
        return () => controller.abort();
    }, [reloadToken]);

    const replaceRow = (updated) => {
        setCashiers((rows) => rows.map((row) => (row.userId === updated.userId ? updated : row)));
    };

    const runRowAction = async (cashierId, action) => {
        if (busyRef.current) return;
        busyRef.current = cashierId;
        setBusyId(cashierId);
        try {
            await action();
        } finally {
            busyRef.current = null;
            setBusyId(null);
        }
    };

    const onFormChange = (e) => {
        const {name, value} = e.target;
        setForm((current) => ({...current, [name]: value}));
        setFormErrors((errors) => ({...errors, [name]: undefined}));
    };

    const onCreate = async (e) => {
        e.preventDefault();
        if (creating) return;
        const errors = validateCreate(form);
        setFormErrors(errors);
        if (Object.keys(errors).length > 0) return;
        setCreating(true);
        try {
            const response = await createCashier({
                name: form.name.trim(),
                email: form.email.trim().toLowerCase(),
                mobile: normalizeMobile(form.mobile),
                password: form.password,
            });
            setCashiers((rows) => [...rows, response.data].sort((a, b) => a.name.localeCompare(b.name)));
            setForm(EMPTY_FORM);
            toast.success("Cashier created");
        } catch (error) {
            console.error(error);
            toast.error(error.friendlyMessage || "Unable to create cashier");
        } finally {
            setCreating(false);
        }
    };

    const onToggleStatus = (cashier) => {
        const enable = !cashier.enabled;
        if (!enable && !window.confirm(`Deactivate ${cashier.name}? They will be signed out and unable to use the POS. Their past sales are kept.`)) {
            return;
        }
        runRowAction(cashier.userId, async () => {
            try {
                const response = await setCashierEnabled(cashier.userId, enable);
                replaceRow(response.data);
                toast.success(enable ? "Cashier reactivated" : "Cashier deactivated");
            } catch (error) {
                console.error(error);
                toast.error(error.friendlyMessage || "Unable to update cashier status");
            }
        });
    };

    const openReset = (cashier) => {
        setResetFor(resetFor === cashier.userId ? null : cashier.userId);
        setResetForm({password: "", confirm: ""});
        setResetErrors({});
    };

    const onReset = (e, cashier) => {
        e.preventDefault();
        const errors = {};
        const pwd = passwordError(resetForm.password);
        if (pwd) errors.password = pwd;
        if (resetForm.confirm !== resetForm.password) errors.confirm = "Passwords do not match";
        setResetErrors(errors);
        if (Object.keys(errors).length > 0) return;
        runRowAction(cashier.userId, async () => {
            try {
                await resetCashierPassword(cashier.userId, resetForm.password);
                setResetFor(null);
                setResetForm({password: "", confirm: ""});
                toast.success(`Password reset for ${cashier.name}`);
            } catch (error) {
                console.error(error);
                toast.error(error.friendlyMessage || "Unable to reset password");
            }
        });
    };

    return (
        <PageShell wide>
            <PageHeader
                kicker="Store staff"
                title="Manage Cashiers"
                description="Create cashier accounts, control who can use the POS, and review each cashier's sales."
            />
            <div className="grid gap-5 lg:grid-cols-[minmax(280px,0.36fr)_minmax(0,1fr)]">
                <section aria-labelledby="add-cashier" className="self-start border-2 border-ink bg-surface p-4 shadow-[3px_3px_0_#111827] sm:p-5">
                    <h2 id="add-cashier" className="mb-4 text-lg font-extrabold">Add cashier</h2>
                    <form className="space-y-4" onSubmit={onCreate} noValidate>
                        <Input label="Name" id="cashier-name" name="name" value={form.name} onChange={onFormChange}
                               autoComplete="off" error={formErrors.name} aria-invalid={Boolean(formErrors.name)} />
                        <Input label="Email" id="cashier-email" name="email" type="email" value={form.email}
                               onChange={onFormChange} autoComplete="off" error={formErrors.email}
                               aria-invalid={Boolean(formErrors.email)} />
                        <Input label="Mobile" id="cashier-mobile" name="mobile" type="tel" inputMode="tel"
                               placeholder="98765 43210" value={form.mobile} onChange={onFormChange} autoComplete="off"
                               error={formErrors.mobile} aria-invalid={Boolean(formErrors.mobile)} />
                        <Input label="Initial password" id="cashier-password" name="password" type="password"
                               placeholder="At least 8 characters, a letter and a number" value={form.password}
                               onChange={onFormChange} autoComplete="new-password" error={formErrors.password}
                               aria-invalid={Boolean(formErrors.password)} />
                        <Button type="submit" variant="primary" className="w-full" disabled={creating}>
                            {creating ? "Creating..." : "Create cashier"}
                        </Button>
                    </form>
                </section>

                <section aria-labelledby="cashier-list" className="border-2 border-ink bg-surface p-4 shadow-[3px_3px_0_#111827] sm:p-5">
                    <h2 id="cashier-list" className="mb-4 text-lg font-extrabold">Cashiers</h2>
                    {loading ? (
                        <LoadingState label="Loading cashiers..." />
                    ) : loadError ? (
                        <EmptyState
                            title="Couldn't load cashiers"
                            description={loadError}
                            action={<Button variant="dark" size="sm" onClick={() => setReloadToken((t) => t + 1)}>Try again</Button>}
                        />
                    ) : cashiers.length === 0 ? (
                        <EmptyState title="No cashiers yet" description="Create the first cashier account with the form." />
                    ) : (
                        <div className="overflow-x-auto">
                            <table className="nb-table min-w-[980px]">
                                <thead>
                                <tr>
                                    <th>Cashier</th>
                                    <th>Status</th>
                                    <th>Created</th>
                                    <th>Orders processed</th>
                                    <th>POS revenue</th>
                                    <th>Last POS sale</th>
                                    <th><span className="sr-only">Actions</span></th>
                                </tr>
                                </thead>
                                <tbody>
                                {cashiers.map((cashier) => {
                                    const busy = busyId === cashier.userId;
                                    return (
                                        <tr key={cashier.userId}>
                                            <td>
                                                <span className="font-extrabold">{cashier.name}</span><br/>
                                                <small className="text-muted">{cashier.email}</small><br/>
                                                <small className="text-muted">{cashier.mobile || "—"}</small>
                                            </td>
                                            <td>
                                                <Badge tone={cashier.enabled ? "success" : "danger"}>
                                                    {cashier.enabled ? "Active" : "Inactive"}
                                                </Badge>
                                            </td>
                                            <td>{cashier.createdAt ? formatDate(cashier.createdAt) : "—"}</td>
                                            <td className="font-bold">{cashier.ordersProcessed}</td>
                                            <td className="font-extrabold">{formatCurrency(cashier.posRevenue)}</td>
                                            <td>{cashier.lastPosSaleAt ? formatDate(cashier.lastPosSaleAt) : "No sales yet"}</td>
                                            <td>
                                                <div className="flex flex-wrap gap-2">
                                                    <Button
                                                        size="sm"
                                                        variant={cashier.enabled ? "danger" : "success"}
                                                        disabled={busy}
                                                        onClick={() => onToggleStatus(cashier)}
                                                        aria-label={`${cashier.enabled ? "Deactivate" : "Reactivate"} ${cashier.name}`}
                                                    >
                                                        {cashier.enabled ? "Deactivate" : "Reactivate"}
                                                    </Button>
                                                    <Button
                                                        size="sm"
                                                        variant="secondary"
                                                        disabled={busy}
                                                        onClick={() => openReset(cashier)}
                                                        aria-expanded={resetFor === cashier.userId}
                                                        aria-label={`Reset password for ${cashier.name}`}
                                                    >
                                                        Reset password
                                                    </Button>
                                                    <Button
                                                        size="sm"
                                                        variant="dark"
                                                        onClick={() => navigate(`/orders?createdByUserId=${encodeURIComponent(cashier.userId)}`)}
                                                        aria-label={`View sales by ${cashier.name}`}
                                                    >
                                                        View sales
                                                    </Button>
                                                </div>
                                                {resetFor === cashier.userId && (
                                                    <form className="mt-3 space-y-2 border-2 border-ink bg-paper p-3" onSubmit={(e) => onReset(e, cashier)} noValidate>
                                                        <Input label="New password" id={`reset-${cashier.userId}`} type="password"
                                                               autoComplete="new-password" value={resetForm.password}
                                                               onChange={(e) => setResetForm((f) => ({...f, password: e.target.value}))}
                                                               error={resetErrors.password} aria-invalid={Boolean(resetErrors.password)} />
                                                        <Input label="Confirm password" id={`reset-confirm-${cashier.userId}`} type="password"
                                                               autoComplete="new-password" value={resetForm.confirm}
                                                               onChange={(e) => setResetForm((f) => ({...f, confirm: e.target.value}))}
                                                               error={resetErrors.confirm} aria-invalid={Boolean(resetErrors.confirm)} />
                                                        <div className="flex gap-2">
                                                            <Button type="submit" size="sm" variant="primary" disabled={busy}>
                                                                {busy ? "Saving..." : "Set password"}
                                                            </Button>
                                                            <Button size="sm" variant="secondary" onClick={() => openReset(cashier)} disabled={busy}>
                                                                Cancel
                                                            </Button>
                                                        </div>
                                                    </form>
                                                )}
                                            </td>
                                        </tr>
                                    );
                                })}
                                </tbody>
                            </table>
                        </div>
                    )}
                </section>
            </div>
        </PageShell>
    );
};

export default ManageCashiers;
