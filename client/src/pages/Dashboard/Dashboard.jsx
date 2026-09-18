import {useEffect, useState} from "react";
import {fetchDashboardData} from "../../Service/Dashboard.js";
import toast from "react-hot-toast";
import PageShell from "../../ui/PageShell.jsx";
import PageHeader from "../../ui/PageHeader.jsx";
import StatCard from "../../ui/StatCard.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import EmptyState from "../../ui/EmptyState.jsx";
import Badge from "../../ui/Badge.jsx";
import Button from "../../ui/Button.jsx";

const Dashboard = () => {
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [reloadToken, setReloadToken] = useState(0);
    useEffect(() => {
        const loadData = async () => {
            setLoading(true);
            try {
                const response = await fetchDashboardData();
                setData(response.data);
            } catch (error) {
                console.error(error);
                toast.error(error.friendlyMessage || "Unable to view the data");
            } finally {
                setLoading(false);
            }
        }
        loadData();
    }, [reloadToken]);

    if (loading) {
        return (
            <PageShell>
                <LoadingState label="Loading dashboard..." />
            </PageShell>
        );
    }

    if (!data) {
        return (
            <PageShell>
                <EmptyState
                    title="Failed to load the dashboard data..."
                    description="The operations snapshot could not be retrieved. Try again in a moment."
                    action={
                        <Button variant="dark" size="sm" onClick={() => setReloadToken((token) => token + 1)}>
                            Try again
                        </Button>
                    }
                />
            </PageShell>
        );
    }

    const paymentTone = (method) => {
        const value = (method || "").toLowerCase();
        if (value === "cash") return "success";
        if (value === "upi") return "info";
        return "muted";
    };

    const orderStatusTone = (status) => {
        switch (status) {
            case "PAID": return "success";
            case "PENDING_PAYMENT": return "warning";
            case "PAYMENT_FAILED": return "danger";
            case "CANCELLED": return "muted";
            default: return "muted";
        }
    };

    return (
        <PageShell>
            <PageHeader
                kicker="Operations"
                title="Dashboard"
                description="Today's live sales snapshot and the most recent orders."
            />
            <div className="mb-8 grid gap-5 sm:grid-cols-2">
                <StatCard
                    accent
                    label="Today's Sales"
                    value={`₹${data.todaySales.toFixed(2)}`}
                    description="Revenue collected today"
                    icon={<i className="bi bi-currency-rupee"></i>}
                />
                <StatCard
                    label="Today's Orders"
                    value={data.todayOrderCount}
                    description="Bills closed today"
                    icon={<i className="bi bi-cart-check"></i>}
                />
            </div>
            <section className="border-2 border-ink bg-surface p-5 shadow-[3px_3px_0_#111827]">
                <h2 className="mb-5 flex items-center gap-2 text-lg font-extrabold uppercase tracking-wide">
                    <i className="bi bi-clock-history text-muted"></i>
                    Recent Orders
                </h2>
                {(!data.recentOrders || data.recentOrders.length === 0) ? (
                    <EmptyState title="No recent orders" description="New sales will appear here as they close." />
                ) : (
                    <div className="overflow-x-auto">
                        <table className="nb-table min-w-[720px]">
                            <thead>
                            <tr>
                                <th>Order Id</th>
                                <th>Customer</th>
                                <th>Amount</th>
                                <th>Payment</th>
                                <th>Order Status</th>
                                <th>Time</th>
                            </tr>
                            </thead>
                            <tbody>
                            {data.recentOrders.map((order) => (
                                <tr key={order.orderId}>
                                    <td className="font-bold">{order.orderId.substring(0,8)}...</td>
                                    <td>{order.customerName}</td>
                                    <td className="text-lg font-extrabold">₹{order.grandTotal.toFixed(2)}</td>
                                    <td>
                                        <Badge tone={paymentTone(order.paymentMethod)}>
                                            {order.paymentMethod}
                                        </Badge>
                                    </td>
                                    <td>
                                        <Badge tone={orderStatusTone(order.orderStatus)}>
                                            {(order.orderStatus || "").replace("_", " ")}
                                        </Badge>
                                    </td>
                                    <td>
                                        {new Date(order.createdAt).toLocaleDateString([], {
                                            hour: '2-digit',
                                            minute: '2-digit',
                                        })}
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </section>
        </PageShell>
    )
}

export default Dashboard;
