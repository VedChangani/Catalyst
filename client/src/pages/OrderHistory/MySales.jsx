import {useEffect, useState} from "react";
import {useNavigate} from "react-router-dom";
import {mySales} from "../../Service/PosService.js";
import PageShell from "../../ui/PageShell.jsx";
import PageHeader from "../../ui/PageHeader.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import EmptyState from "../../ui/EmptyState.jsx";
import Badge from "../../ui/Badge.jsx";
import Button from "../../ui/Button.jsx";
import {formatDate, orderStatusTone, paymentStatusTone, statusLabel} from "../../util/orderFormat.js";

// The cashier's own POS sales: the backend returns only the orders this cashier entered
// (createdBy = the authenticated cashier), whether or not a registered customer was linked.
const MySales = () => {
    const navigate = useNavigate();
    const [sales, setSales] = useState([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState(null);
    const [reloadToken, setReloadToken] = useState(0);

    useEffect(() => {
        const controller = new AbortController();
        setLoading(true);
        setLoadError(null);
        mySales(controller.signal)
            .then((response) => setSales(Array.isArray(response.data) ? response.data : []))
            .catch((error) => {
                if (error.code === "ERR_CANCELED") {
                    return;
                }
                console.error(error);
                setLoadError(error.friendlyMessage || "Unable to load your sales");
            })
            .finally(() => {
                if (!controller.signal.aborted) {
                    setLoading(false);
                }
            });
        return () => controller.abort();
    }, [reloadToken]);

    const formatItems = (items) => (items || []).map((item) => `${item.name} x ${item.quantity}`).join(", ");

    const header = (
        <PageHeader
            kicker="Point of sale"
            title="My Sales"
            description="Sales you entered at the counter, with payment status."
        />
    );

    if (loading) {
        return (
            <PageShell wide>
                <LoadingState label="Loading sales..." />
            </PageShell>
        );
    }

    if (loadError) {
        return (
            <PageShell wide>
                {header}
                <EmptyState
                    title="Couldn't load sales"
                    description={loadError}
                    action={
                        <Button variant="dark" size="sm" onClick={() => setReloadToken((token) => token + 1)}>
                            Try again
                        </Button>
                    }
                />
            </PageShell>
        );
    }

    if (sales.length === 0) {
        return (
            <PageShell wide>
                {header}
                <EmptyState title="No sales yet" description="Sales you complete in the POS will show up here." />
            </PageShell>
        );
    }

    return (
        <PageShell wide>
            {header}
            <div className="overflow-x-auto border-2 border-ink bg-surface shadow-[3px_3px_0_#111827]">
                <table className="nb-table min-w-[960px]">
                    <thead>
                    <tr>
                        <th>Order Id</th>
                        <th>Date</th>
                        <th>Customer</th>
                        <th>Items</th>
                        <th>Total</th>
                        <th>Payment</th>
                        <th>Order Status</th>
                        <th>Payment Status</th>
                        <th><span className="sr-only">Actions</span></th>
                    </tr>
                    </thead>
                    <tbody>
                    {sales.map(order => (
                        <tr key={order.orderId}>
                            <td className="font-bold">{order.orderId}</td>
                            <td>{formatDate(order.createdAt)}</td>
                            <td>
                                {order.customerName || "Walk-in"}
                                {order.customer && <> <Badge tone="success">Registered</Badge></>}
                                {order.phoneNumber && <><br/><small className="text-muted">{order.phoneNumber}</small></>}
                            </td>
                            <td className="max-w-xs">{formatItems(order.items)}</td>
                            <td className="text-lg font-extrabold">₹{order.grandTotal}</td>
                            <td>
                                <Badge tone={(order.paymentMethod || "").toLowerCase() === "cash" ? "success" : "info"}>
                                    {order.paymentMethod}
                                </Badge>
                            </td>
                            <td>
                                <Badge tone={orderStatusTone(order.orderStatus)}>
                                    {statusLabel(order.orderStatus)}
                                </Badge>
                            </td>
                            <td>
                                <Badge tone={paymentStatusTone(order.paymentStatus)}>
                                    {order.paymentStatus || "UNKNOWN"}
                                </Badge>
                            </td>
                            <td>
                                <Button
                                    variant="secondary"
                                    size="sm"
                                    onClick={() => navigate(`/sales/${encodeURIComponent(order.orderId)}`)}
                                    aria-label={`View sale ${order.orderId}`}
                                >
                                    View
                                </Button>
                            </td>
                        </tr>
                    ))}
                    </tbody>
                </table>
            </div>
        </PageShell>
    );
};

export default MySales;
