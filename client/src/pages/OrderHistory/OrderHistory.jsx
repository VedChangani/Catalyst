import {useContext, useEffect, useState} from "react";
import {useNavigate} from "react-router-dom";
import {myOrders} from "../../Service/OrderService.js";
import {AppContext} from "../../context/AppContext.jsx";
import PageShell from "../../ui/PageShell.jsx";
import PageHeader from "../../ui/PageHeader.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import EmptyState from "../../ui/EmptyState.jsx";
import Badge from "../../ui/Badge.jsx";
import Button from "../../ui/Button.jsx";
import AdminOrders from "./AdminOrders.jsx";
import {
    channelLabel,
    channelTone,
    formatDate,
    orderStatusTone,
    paymentStatusTone,
    statusLabel
} from "../../util/orderFormat.js";

// The customer's unified purchase history: online orders and in-store (POS) purchases that a
// cashier linked to this account. The backend returns exactly that list (newest first, all of it,
// no pagination); walk-in POS sales are never part of it. Admins get the order-management list.
const MyOrders = () => {
    const navigate = useNavigate();
    const [orders, setOrders] = useState([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState(null);
    const [reloadToken, setReloadToken] = useState(0);

    useEffect(() => {
        const controller = new AbortController();
        setLoading(true);
        setLoadError(null);
        // Only the caller's own orders; ownership is enforced backend-side, not just here.
        myOrders(controller.signal)
            .then((response) => setOrders(Array.isArray(response.data) ? response.data : []))
            .catch((error) => {
                if (error.code === "ERR_CANCELED") {
                    return;
                }
                console.error(error);
                setLoadError(error.friendlyMessage || "Unable to load orders");
            })
            .finally(() => {
                if (!controller.signal.aborted) {
                    setLoading(false);
                }
            });
        return () => controller.abort();
    }, [reloadToken]);

    const formatItems = (items) => {
        return (items || []).map((item) => `${item.name} x ${item.quantity}`).join(', ');
    }

    const header = (
        <PageHeader
            kicker="Sales"
            title="My Orders"
            description="Your purchases, online and in store, with payment status."
        />
    );

    if (loading) {
        return (
            <PageShell wide>
                <LoadingState label="Loading orders..." />
            </PageShell>
        );
    }

    if (loadError) {
        return (
            <PageShell wide>
                {header}
                <EmptyState
                    title="Couldn't load orders"
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

    if (orders.length === 0) {
        return (
            <PageShell wide>
                {header}
                <EmptyState title="No orders found" description="Completed checkouts will show up in this ledger." />
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
                        <th>Channel</th>
                        <th>Items</th>
                        <th>Total</th>
                        <th>Payment</th>
                        <th>Order Status</th>
                        <th>Payment Status</th>
                        <th><span className="sr-only">Actions</span></th>
                    </tr>
                    </thead>
                    <tbody>
                    {orders.map(order => (
                        <tr key={order.orderId}>
                            <td className="font-bold">{order.orderId}</td>
                            <td>{formatDate(order.createdAt)}</td>
                            <td><Badge tone={channelTone(order.salesChannel)}>{channelLabel(order.salesChannel)}</Badge></td>
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
                                    onClick={() => navigate(`/orders/${encodeURIComponent(order.orderId)}`)}
                                    aria-label={`View order ${order.orderId}`}
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
    )
}

const OrderHistory = () => {
    const {auth} = useContext(AppContext);
    return auth?.role === "ROLE_ADMIN" ? <AdminOrders /> : <MyOrders />;
}

export default OrderHistory;
