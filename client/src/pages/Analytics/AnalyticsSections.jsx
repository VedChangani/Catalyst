import {useNavigate} from "react-router-dom";
import Badge from "../../ui/Badge.jsx";
import Button from "../../ui/Button.jsx";
import SectionHeader from "../../ui/SectionHeader.jsx";
import {channelLabel, channelTone, formatCurrency, orderStatusTone, statusLabel} from "../../util/orderFormat.js";

// Presentational sections for the analytics page. Every value comes from the single
// /admin/analytics response; nothing here recalculates a metric. Shares from the backend are
// fractions (0..1) and are only scaled to a percentage for display.

const STATUSES = ["PAID", "PENDING_PAYMENT", "PAYMENT_FAILED", "CANCELLED"];

const list = (value) => (Array.isArray(value) ? value : []);
const num = (value) => (Number.isFinite(value) ? value : 0);
const formatPercent = (fraction) => `${(num(fraction) * 100).toFixed(1)}%`;

const Panel = ({title, description, actions, children}) => (
    <section className="min-w-0 border-2 border-ink bg-surface p-5 shadow-[3px_3px_0_#111827]">
        <SectionHeader title={title} description={description} actions={actions} />
        {children}
    </section>
);

const EmptyNote = ({children}) => (
    <p className="border-2 border-dashed border-ink/40 bg-paper px-4 py-3 text-sm text-muted">{children}</p>
);

// Proportion bar: width is the backend share (or a value relative to the max for the trends).
const ProportionBar = ({fraction, label}) => (
    <div
        className="h-3 w-full border-2 border-ink bg-paper"
        role="img"
        aria-label={label}
    >
        <div className="h-full bg-primary" style={{width: `${Math.min(Math.max(num(fraction), 0), 1) * 100}%`}} />
    </div>
);

// One bar per backend day, in order. Labels are sparse (first / middle / last) so a 366-day range
// stays readable; every value is still available in the hover title and the data table below.
const Trend = ({title, description, days, valueOf, format, unit}) => {
    const values = days.map((d) => num(valueOf(d)));
    const max = Math.max(0, ...values);
    const mid = days[Math.floor(days.length / 2)];
    return (
        <Panel title={title} description={description}>
            {days.length === 0 ? (
                <EmptyNote>No data for this period.</EmptyNote>
            ) : (
                <>
                    <div
                        className="flex h-40 items-end gap-px border-b-2 border-ink"
                        role="img"
                        aria-label={`${title}: ${days.length} days, from ${days[0].date} to ${days[days.length - 1].date}, peak ${format(max)}. Full values are in the table below.`}
                    >
                        {days.map((day, i) => (
                            <div
                                key={day.date}
                                className="flex h-full min-w-0 flex-1 items-end"
                                title={`${day.date}: ${format(values[i])}`}
                            >
                                <div
                                    className={max > 0 && values[i] > 0 ? "w-full bg-primary" : "w-full bg-ink/20"}
                                    style={{height: max > 0 && values[i] > 0 ? `${(values[i] / max) * 100}%` : "2px"}}
                                />
                            </div>
                        ))}
                    </div>
                    <div className="mt-1 flex justify-between text-xs font-bold text-muted">
                        <span>{days[0].date}</span>
                        {days.length > 2 && <span className="hidden sm:inline">{mid.date}</span>}
                        <span>{days[days.length - 1].date}</span>
                    </div>
                    <p className="mt-2 text-sm text-muted">Peak day: {format(max)} {unit}</p>
                </>
            )}
        </Panel>
    );
};

const DailyTable = ({days}) => (
    <details className="mt-6 border-2 border-ink bg-surface p-5 shadow-[3px_3px_0_#111827]">
        <summary className="cursor-pointer text-sm font-extrabold uppercase tracking-wide">
            Daily values ({days.length} days)
        </summary>
        <div className="mt-4 max-h-80 overflow-auto">
            <table className="nb-table">
                <caption className="sr-only">Revenue and paid orders per day</caption>
                <thead>
                <tr><th scope="col">Date</th><th scope="col">Revenue</th><th scope="col">Paid orders</th></tr>
                </thead>
                <tbody>
                {days.map((d) => (
                    <tr key={d.date}>
                        <td>{d.date}</td>
                        <td>{formatCurrency(d.revenue)}</td>
                        <td>{num(d.orders)}</td>
                    </tr>
                ))}
                </tbody>
            </table>
        </div>
    </details>
);

const Breakdown = ({title, description, rows, nameOf, badgeOf, emptyText}) => (
    <Panel title={title} description={description}>
        {rows.length === 0 ? (
            <EmptyNote>{emptyText}</EmptyNote>
        ) : (
            <div className="overflow-x-auto">
                <table className="nb-table">
                    <thead>
                    <tr>
                        <th scope="col">{nameOf.header}</th>
                        <th scope="col">Orders</th>
                        <th scope="col">Revenue</th>
                        <th scope="col">Share</th>
                    </tr>
                    </thead>
                    <tbody>
                    {rows.map((row) => (
                        <tr key={nameOf.key(row)}>
                            <td><Badge tone={badgeOf(row)}>{nameOf.label(row)}</Badge></td>
                            <td>
                                {num(row.orders)}
                                <span className="block text-xs text-muted">{formatPercent(row.orderShare)}</span>
                            </td>
                            <td>
                                {formatCurrency(row.revenue)}
                                <span className="block text-xs text-muted">{formatPercent(row.revenueShare)}</span>
                            </td>
                            <td className="min-w-24">
                                <ProportionBar
                                    fraction={row.revenueShare}
                                    label={`${nameOf.label(row)} revenue share ${formatPercent(row.revenueShare)}`}
                                />
                            </td>
                        </tr>
                    ))}
                    </tbody>
                </table>
            </div>
        )}
    </Panel>
);

const methodTone = (method) => (method === "CASH" ? "success" : method === "UPI" ? "info" : "muted");
const methodLabel = (method) => (method === "CASH" ? "Cash" : method === "UPI" ? "UPI" : "Unknown");

const TopProducts = ({title, rows, primary}) => (
    <Panel title={title}>
        {rows.length === 0 ? (
            <EmptyNote>No products sold in this period.</EmptyNote>
        ) : (
            <div className="overflow-x-auto">
                <table className="nb-table">
                    <thead>
                    <tr>
                        <th scope="col">#</th>
                        <th scope="col">Item</th>
                        {primary === "quantity" ? (
                            <>
                                <th scope="col">Quantity</th>
                                <th scope="col">Product revenue</th>
                            </>
                        ) : (
                            <>
                                <th scope="col">Product revenue</th>
                                <th scope="col">Quantity</th>
                            </>
                        )}
                    </tr>
                    </thead>
                    <tbody>
                    {rows.map((p, i) => (
                        <tr key={p.itemId || i}>
                            <td className="font-bold">{i + 1}</td>
                            <td className="font-bold">{p.name || "Unnamed item"}</td>
                            {primary === "quantity" ? (
                                <>
                                    <td>{num(p.quantity)}</td>
                                    <td>{formatCurrency(p.revenue)}</td>
                                </>
                            ) : (
                                <>
                                    <td>{formatCurrency(p.revenue)}</td>
                                    <td>{num(p.quantity)}</td>
                                </>
                            )}
                        </tr>
                    ))}
                    </tbody>
                </table>
            </div>
        )}
    </Panel>
);

const AnalyticsSections = ({data}) => {
    const navigate = useNavigate();
    const days = list(data.daily);
    const channels = list(data.channels);
    const methods = list(data.paymentMethods);
    const statusCounts = data.orderStatusCounts || {};
    const inventory = data.inventory || {};
    const upi = data.upiSuccessRate;

    return (
        <>
            <div className="mb-8 grid gap-5 lg:grid-cols-2">
                <Trend
                    title="Revenue trend"
                    description="Revenue from paid orders, per day"
                    days={days}
                    valueOf={(d) => d.revenue}
                    format={formatCurrency}
                    unit=""
                />
                <Trend
                    title="Order volume"
                    description="Paid orders, per day"
                    days={days}
                    valueOf={(d) => d.orders}
                    format={(v) => String(v)}
                    unit="orders"
                />
            </div>
            {days.length > 0 && <DailyTable days={days} />}

            <div className="my-8 grid gap-5 lg:grid-cols-2">
                <Breakdown
                    title="Sales channel"
                    description="Paid orders by where the sale originated"
                    rows={channels}
                    nameOf={{
                        header: "Channel",
                        key: (r) => r.channel,
                        label: (r) => (r.channel === "UNKNOWN" ? "Unknown" : channelLabel(r.channel)),
                    }}
                    badgeOf={(r) => channelTone(r.channel)}
                    emptyText="No paid orders in this period."
                />
                <Breakdown
                    title="Payment method"
                    description="Paid orders by how they were paid"
                    rows={methods}
                    nameOf={{header: "Method", key: (r) => r.method, label: (r) => methodLabel(r.method)}}
                    badgeOf={(r) => methodTone(r.method)}
                    emptyText="No paid orders in this period."
                />
            </div>

            <div className="mb-8 grid gap-5 lg:grid-cols-2">
                <Panel
                    title="Order status"
                    description="Orders created in this period, by their current status (not by paid time)"
                >
                    <ul className="grid grid-cols-2 gap-3">
                        {STATUSES.map((status) => (
                            <li key={status} className="border-2 border-ink bg-paper p-3">
                                <Badge tone={orderStatusTone(status)}>{statusLabel(status)}</Badge>
                                <p className="mt-2 text-2xl font-extrabold leading-none">{num(statusCounts[status])}</p>
                            </li>
                        ))}
                    </ul>
                </Panel>
                <Panel title="UPI success rate" description="How often UPI payments end up paid">
                    {upi === null || upi === undefined ? (
                        <>
                            <p className="text-2xl font-extrabold">No UPI attempts</p>
                            <p className="mt-2 text-sm text-muted">
                                No UPI order created in this period has been settled yet.
                            </p>
                        </>
                    ) : (
                        <>
                            <p className="text-4xl font-extrabold leading-none">{formatPercent(upi)}</p>
                            <div className="mt-3"><ProportionBar fraction={upi} label={`UPI success rate ${formatPercent(upi)}`} /></div>
                        </>
                    )}
                    <p className="mt-3 text-sm text-muted">
                        Paid ÷ (Paid + Payment failed + Cancelled), for UPI orders created in the selected
                        period. Pending payments are not counted.
                    </p>
                </Panel>
            </div>

            <div className="mb-8 grid gap-5 lg:grid-cols-2">
                <TopProducts title="Top products by quantity" rows={list(data.topByQuantity)} primary="quantity" />
                <TopProducts title="Top products by revenue" rows={list(data.topByRevenue)} primary="revenue" />
            </div>
            <p className="-mt-5 mb-8 text-sm text-muted">
                Product revenue is the historical order-line price × quantity for orders paid in this period.
                It is before tax, so it may not equal the revenue collected in the cards above.
            </p>

            <Panel
                title="Inventory"
                description="Current stock state - not affected by the selected date range"
                actions={
                    <Button variant="secondary" size="sm" onClick={() => navigate("/items")}>
                        Manage inventory
                    </Button>
                }
            >
                <div className="grid gap-3 sm:grid-cols-3">
                    <div className="border-2 border-ink bg-paper p-3">
                        <Badge tone="warning">Low stock</Badge>
                        <p className="mt-2 text-2xl font-extrabold leading-none">{num(inventory.lowStock)}</p>
                        <p className="mt-1 text-sm text-muted">Available, at or below the threshold</p>
                    </div>
                    <div className="border-2 border-ink bg-paper p-3">
                        <Badge tone="danger">Out of stock</Badge>
                        <p className="mt-2 text-2xl font-extrabold leading-none">{num(inventory.outOfStock)}</p>
                        <p className="mt-1 text-sm text-muted">No stock available</p>
                    </div>
                    <div className="border-2 border-ink bg-paper p-3">
                        <Badge tone="muted">Untracked</Badge>
                        <p className="mt-2 text-2xl font-extrabold leading-none">{num(inventory.untracked)}</p>
                        <p className="mt-1 text-sm text-muted">Stock never recorded, availability unknown</p>
                    </div>
                </div>
            </Panel>
        </>
    );
};

export default AnalyticsSections;
