import {useContext, useEffect, useMemo, useState} from "react";
import {Link, useNavigate} from "react-router-dom";
import {myOrders} from "../../Service/OrderService.js";
import {fetchMyAccount} from "../../Service/AccountService.js";
import {AppContext} from "../../context/AppContext.jsx";
import PageShell from "../../ui/PageShell.jsx";
import PageHeader from "../../ui/PageHeader.jsx";
import SectionHeader from "../../ui/SectionHeader.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import EmptyState from "../../ui/EmptyState.jsx";
import StatCard from "../../ui/StatCard.jsx";
import Card from "../../ui/Card.jsx";
import Badge from "../../ui/Badge.jsx";
import Button from "../../ui/Button.jsx";
import {channelLabel, channelTone, formatCurrency, formatDate, orderStatusTone, statusLabel} from "../../util/orderFormat.js";
import {recentlyPurchased, summarizeOrders} from "../../util/customerHome.js";

// Customer landing page. Reuses GET /orders/my-orders (the caller's own ONLINE + linked POS
// orders; walk-in sales are never in it) and GET /account/me for the name. Product images come
// from the already-loaded catalog by itemId.
const CustomerHome = () => {
    const navigate = useNavigate();
    const {itemsData} = useContext(AppContext);
    const [orders, setOrders] = useState([]);
    const [name, setName] = useState("");
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState(null);
    const [reloadToken, setReloadToken] = useState(0);

    useEffect(() => {
        const controller = new AbortController();
        setLoading(true);
        setLoadError(null);
        Promise.all([myOrders(controller.signal), fetchMyAccount(controller.signal)])
            .then(([orderResponse, accountResponse]) => {
                setOrders(Array.isArray(orderResponse.data) ? orderResponse.data : []);
                setName(accountResponse.data?.name || "");
            })
            .catch((error) => {
                if (error.code === "ERR_CANCELED") return;
                console.error(error);
                setLoadError(error.friendlyMessage || "Unable to load your activity");
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false);
            });
        return () => controller.abort();
    }, [reloadToken]);

    const summary = useMemo(() => summarizeOrders(orders), [orders]);
    const purchased = useMemo(() => recentlyPurchased(orders), [orders]);
    const imageFor = (itemId) => itemsData.find((item) => item.itemId === itemId)?.imgUrl;

    if (loading) {
        return (
            <PageShell>
                <LoadingState label="Loading your home..." />
            </PageShell>
        );
    }

    if (loadError) {
        return (
            <PageShell>
                <EmptyState
                    title="Couldn't load your home"
                    description={loadError}
                    action={<Button variant="dark" size="sm" onClick={() => setReloadToken((t) => t + 1)}>Try again</Button>}
                />
            </PageShell>
        );
    }

    return (
        <PageShell>
            <PageHeader
                title={`Welcome back${name ? `, ${name}` : ""}`}
                description="Here’s a quick look at your Catalyst activity."
            />
            <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-3">
                <StatCard label="Active Orders" value={summary.activeOrders} />
                <StatCard label="Total Orders" value={summary.totalOrders} />
                <StatCard label="Total Spent" value={formatCurrency(summary.totalSpent)} />
            </div>

            <div className="mt-8 grid grid-cols-1 gap-6 lg:grid-cols-2">
                <section>
                    <SectionHeader
                        title="Recent Orders"
                        actions={<Button variant="secondary" size="sm" onClick={() => navigate("/orders")}>View All Orders</Button>}
                    />
                    {orders.length === 0 ? (
                        <EmptyState
                            title="No orders yet"
                            action={<Button variant="dark" size="sm" onClick={() => navigate("/explore")}>Shop Now</Button>}
                        />
                    ) : (
                        <div className="flex flex-col gap-3">
                            {orders.slice(0, 4).map((order) => (
                                <Link key={order.orderId} to={`/orders/${encodeURIComponent(order.orderId)}`} className="block">
                                    <Card className="flex flex-wrap items-center justify-between gap-2 p-3">
                                        <div className="min-w-0">
                                            <p className="truncate font-extrabold text-ink">{order.orderId}</p>
                                            <p className="text-sm text-muted">{formatDate(order.createdAt)}</p>
                                        </div>
                                        <div className="flex flex-wrap items-center gap-2">
                                            <Badge tone={channelTone(order.salesChannel)}>{channelLabel(order.salesChannel)}</Badge>
                                            <Badge tone={orderStatusTone(order.orderStatus)}>{statusLabel(order.orderStatus)}</Badge>
                                            <span className="font-extrabold">{formatCurrency(order.grandTotal)}</span>
                                        </div>
                                    </Card>
                                </Link>
                            ))}
                        </div>
                    )}
                </section>

                <section>
                    <SectionHeader title="Recently Purchased" />
                    {purchased.length === 0 ? (
                        <EmptyState
                            title="No purchases yet"
                            action={<Button variant="dark" size="sm" onClick={() => navigate("/explore")}>Shop</Button>}
                        />
                    ) : (
                        <div className="flex flex-col gap-3">
                            {purchased.map((item) => (
                                <Card key={item.itemId} className="flex items-center gap-3 p-3">
                                    {imageFor(item.itemId) ? (
                                        <img src={imageFor(item.itemId)} alt={item.name} className="h-12 w-12 border-2 border-ink object-cover" />
                                    ) : (
                                        <div className="h-12 w-12 border-2 border-ink bg-paper" aria-hidden="true" />
                                    )}
                                    <p className="min-w-0 flex-1 truncate font-extrabold text-ink">{item.name}</p>
                                    <span className="font-bold">{formatCurrency(item.price)}</span>
                                </Card>
                            ))}
                        </div>
                    )}
                </section>
            </div>
        </PageShell>
    );
};

export default CustomerHome;
