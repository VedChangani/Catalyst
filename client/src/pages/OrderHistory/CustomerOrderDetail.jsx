import {useEffect, useState} from "react";
import {useNavigate, useParams} from "react-router-dom";
import {myOrder} from "../../Service/OrderService.js";
import PageShell from "../../ui/PageShell.jsx";
import PageHeader from "../../ui/PageHeader.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import EmptyState from "../../ui/EmptyState.jsx";
import Badge from "../../ui/Badge.jsx";
import Button from "../../ui/Button.jsx";
import {
    channelLabel,
    channelTone,
    formatDate,
    orderStatusTone,
    paymentStatusTone,
    statusLabel
} from "../../util/orderFormat.js";

const money = (value) => `₹${Number(value ?? 0).toFixed(2)}`;

// One order from the customer's own history, as a receipt-style summary. Everything shown -
// including item names, unit prices and line totals - is the historical snapshot returned by the
// backend; the current catalog is never consulted. Ownership is decided by the backend only.
// Also used for a cashier's own POS sale (from My Sales): the same GET /orders/{id} returns it
// only when the cashier entered that sale, so only the back link differs.
const CustomerOrderDetail = ({backTo = "/orders", backLabel = "My Orders"}) => {
    const {orderId} = useParams();
    const navigate = useNavigate();
    const [order, setOrder] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [reloadToken, setReloadToken] = useState(0);

    useEffect(() => {
        const controller = new AbortController();
        setOrder(null);
        setError(null);
        setLoading(true);
        myOrder(orderId, controller.signal)
            .then((response) => {
                if (!response.data || typeof response.data !== "object" || !Array.isArray(response.data.items)) {
                    setError({message: "This order could not be displayed.", retry: false});
                    return;
                }
                setOrder(response.data);
            })
            .catch((requestError) => {
                if (requestError.code === "ERR_CANCELED") {
                    return;
                }
                console.error(requestError);
                const status = requestError.response?.status;
                // Missing and not-yours look identical to the customer: nothing is revealed
                // about whether someone else's order exists.
                if (status === 403 || status === 404) {
                    setError({message: "We couldn't find this order in your purchase history.", retry: false});
                } else {
                    setError({message: requestError.friendlyMessage || "Unable to load this order.", retry: true});
                }
            })
            .finally(() => {
                if (!controller.signal.aborted) {
                    setLoading(false);
                }
            });
        return () => controller.abort();
    }, [orderId, reloadToken]);

    const backButton = (
        <Button variant="secondary" size="sm" onClick={() => navigate(backTo)}>
            ← Back to {backLabel}
        </Button>
    );

    if (loading) {
        return (
            <PageShell>
                <LoadingState label="Loading order..." />
            </PageShell>
        );
    }

    if (error || !order) {
        return (
            <PageShell>
                <PageHeader kicker="Sales" title="Order" actions={backButton} />
                <EmptyState
                    title="Order unavailable"
                    description={error?.message}
                    action={error?.retry ? (
                        <Button variant="dark" size="sm" onClick={() => setReloadToken((token) => token + 1)}>
                            Try again
                        </Button>
                    ) : null}
                />
            </PageShell>
        );
    }

    const paymentStatus = order.paymentStatus || order.paymentDetails?.status;
    const showPaymentIds = order.paymentDetails?.razorpayOrderId || order.paymentDetails?.razorpayPaymentId;

    return (
        <PageShell>
            <PageHeader
                kicker="Sales"
                title="Order Summary"
                description={`Receipt for order ${order.orderId}`}
                actions={backButton}
            />

            <div className="border-2 border-ink bg-surface shadow-[3px_3px_0_#111827]">
                <div className="grid gap-4 border-b-2 border-ink p-4 sm:grid-cols-2 sm:p-5">
                    <div className="space-y-1">
                        <p className="text-xs font-extrabold uppercase tracking-wide text-muted">Order</p>
                        <p className="text-lg font-extrabold">{order.orderId}</p>
                        <p className="text-sm">{formatDate(order.createdAt)}</p>
                        {(order.customerName || order.phoneNumber) && (
                            <p className="text-sm">
                                {order.customerName}
                                {order.customerName && order.phoneNumber ? " · " : ""}
                                {order.phoneNumber}
                            </p>
                        )}
                    </div>
                    <div className="flex flex-wrap content-start gap-x-6 gap-y-3">
                        <div>
                            <p className="mb-1 text-xs font-extrabold uppercase tracking-wide text-muted">Channel</p>
                            <Badge tone={channelTone(order.salesChannel)}>{channelLabel(order.salesChannel)}</Badge>
                        </div>
                        <div>
                            <p className="mb-1 text-xs font-extrabold uppercase tracking-wide text-muted">Order status</p>
                            <Badge tone={orderStatusTone(order.orderStatus)}>{statusLabel(order.orderStatus)}</Badge>
                        </div>
                        <div>
                            <p className="mb-1 text-xs font-extrabold uppercase tracking-wide text-muted">Payment status</p>
                            <Badge tone={paymentStatusTone(paymentStatus)}>{paymentStatus || "UNKNOWN"}</Badge>
                        </div>
                    </div>
                </div>

                <div className="overflow-x-auto">
                    <table className="nb-table min-w-[480px]">
                        <thead>
                        <tr>
                            <th>Item</th>
                            <th>Qty</th>
                            <th>Unit price</th>
                            <th>Line total</th>
                        </tr>
                        </thead>
                        <tbody>
                        {order.items.map((item, index) => (
                            <tr key={`${item.itemId}-${index}`}>
                                <td className="font-bold">{item.name}</td>
                                <td>{item.quantity}</td>
                                <td>{money(item.price)}</td>
                                <td className="font-extrabold">
                                    {money(item.lineTotal ?? item.price * item.quantity)}
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>

                <div className="grid gap-4 border-t-2 border-ink bg-paper p-4 sm:grid-cols-2 sm:p-5">
                    <div className="space-y-1 text-sm">
                        <p className="text-xs font-extrabold uppercase tracking-wide text-muted">Payment</p>
                        <p><strong>Method:</strong> {order.paymentMethod}</p>
                        {showPaymentIds && (
                            <>
                                {order.paymentDetails.razorpayOrderId && (
                                    <p className="break-all"><strong>Payment order ref:</strong> {order.paymentDetails.razorpayOrderId}</p>
                                )}
                                {order.paymentDetails.razorpayPaymentId && (
                                    <p className="break-all"><strong>Payment ref:</strong> {order.paymentDetails.razorpayPaymentId}</p>
                                )}
                            </>
                        )}
                    </div>
                    <div className="space-y-2">
                        <div className="flex justify-between text-sm font-bold">
                            <span>Subtotal</span>
                            <span>{money(order.subtotal)}</span>
                        </div>
                        <div className="flex justify-between text-sm font-bold">
                            <span>Tax</span>
                            <span>{money(order.tax)}</span>
                        </div>
                        <div className="flex items-end justify-between border-t-2 border-ink pt-2">
                            <span className="text-xs font-extrabold uppercase tracking-[0.16em]">Total</span>
                            <span className="text-3xl font-extrabold leading-none">{money(order.grandTotal)}</span>
                        </div>
                    </div>
                </div>
            </div>
        </PageShell>
    );
}

export default CustomerOrderDetail;
