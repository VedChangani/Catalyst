import {useContext, useEffect, useState} from "react";
import {latestOrders, myOrders} from "../../Service/OrderService.js";
import {AppContext} from "../../context/AppContext.jsx";
import PageShell from "../../ui/PageShell.jsx";
import PageHeader from "../../ui/PageHeader.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import EmptyState from "../../ui/EmptyState.jsx";
import Badge from "../../ui/Badge.jsx";

const OrderHistory = () => {
    const {auth} = useContext(AppContext);
    const isAdmin = auth?.role === "ROLE_ADMIN";

    const [orders, setOrders] = useState([]);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        const fetchOrders = async () => {
            try {
                // Admins see every order placed in the system; regular users only ever
                // see their own orders (enforced backend-side, not just here).
                const response = isAdmin ? await latestOrders() : await myOrders();
                setOrders(response.data);
            } catch (error) {
                console.log(error);
            } finally {
                setLoading(false);
            }
        }
        fetchOrders();
    }, [isAdmin]);

    const formatItems = (items) => {
        return items.map((item) => `${item.name} x ${item.quantity}`).join(', ');
    }

    const formatDate = (dateString) => {
        const options = {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit',
        }
        return new Date(dateString).toLocaleDateString('en-US', options);
    }

    const orderStatusTone = (status) => {
        switch (status) {
            case "PAID": return "success";
            case "PENDING_PAYMENT": return "warning";
            case "PAYMENT_FAILED": return "danger";
            case "CANCELLED": return "muted";
            default: return "muted";
        }
    };

    const paymentStatusTone = (status) => {
        switch (status) {
            case "COMPLETED": return "success";
            case "PENDING": return "warning";
            case "FAILED": return "danger";
            default: return "muted";
        }
    };

    if (loading) {
        return (
            <PageShell wide>
                <LoadingState label="Loading orders..." />
            </PageShell>
        );
    }

    if (orders.length === 0) {
        return (
            <PageShell wide>
                <PageHeader
                    kicker="Sales"
                    title={isAdmin ? "All Orders" : "My Orders"}
                    description="Closed bills and payment status for this counter."
                />
                <EmptyState title="No orders found" description="Completed checkouts will show up in this ledger." />
            </PageShell>
        );
    }

    return (
        <PageShell wide>
            <PageHeader
                kicker="Sales"
                title={isAdmin ? "All Orders" : "My Orders"}
                description="Closed bills and payment status for this counter."
            />
            <div className="overflow-x-auto border-2 border-ink bg-surface shadow-[3px_3px_0_#111827]">
                <table className="nb-table min-w-[960px]">
                    <thead>
                    <tr>
                        <th>Order Id</th>
                        <th>Customer</th>
                        <th>Items</th>
                        <th>Total</th>
                        <th>Payment</th>
                        <th>Order Status</th>
                        <th>Payment Status</th>
                        <th>Date</th>
                    </tr>
                    </thead>
                    <tbody>
                    {orders.map(order => (
                        <tr key={order.orderId}>
                            <td className="font-bold">{order.orderId}</td>
                            <td>
                                {order.customerName} <br/>
                                <small className="text-muted">{order.phoneNumber}</small>
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
        </PageShell>
    )
}

export default OrderHistory;
