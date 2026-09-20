import {useContext} from "react";
import {Link, useNavigate} from "react-router-dom";
import {AppContext} from "../../context/AppContext.jsx";
import CartItems from "../../components/CartItems/CartItems.jsx";
import CartSummary from "../../components/CartSummary/CartSummary.jsx";
import PageShell from "../../ui/PageShell.jsx";
import PageHeader from "../../ui/PageHeader.jsx";
import EmptyState from "../../ui/EmptyState.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import Button from "../../ui/Button.jsx";

// Customer-only (ROLE_USER, see App.jsx): the current purchase and its checkout. The cart itself
// is AppContext.cartItems - the same state the Shop page adds to - and checkout is the existing
// CartSummary flow. Identity is never collected here: the backend uses the logged-in account.
const Cart = () => {
    const navigate = useNavigate();
    const {cartItems, cartCount, isCatalogLoading} = useContext(AppContext);

    if (isCatalogLoading) {
        return (
            <PageShell wide>
                <LoadingState label="Loading your cart..." />
            </PageShell>
        );
    }

    const isEmpty = cartItems.length === 0;

    return (
        <PageShell wide>
            <PageHeader
                kicker="Checkout"
                title="Your Cart"
                description={isEmpty ? undefined : `${cartCount} ${cartCount === 1 ? "item" : "items"} · Your account details are used for this order.`}
                actions={
                    <Button variant="secondary" size="sm" onClick={() => navigate("/explore")}>
                        <i className="bi bi-arrow-left"></i> Continue shopping
                    </Button>
                }
            />
            {isEmpty && (
                <EmptyState
                    title="Your cart is empty."
                    description="Browse the shop and add products to start an order."
                    action={
                        <Link
                            to="/explore"
                            className="mt-2 inline-flex items-center gap-2 border-2 border-ink bg-primary px-4 py-2.5 text-sm font-bold text-white shadow-[2px_2px_0_#111827] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
                        >
                            Go to shop
                        </Link>
                    }
                />
            )}
            {/* One CartSummary instance in a stable position: the cart empties the moment a sale is
                paid, and the receipt shown for that sale lives inside CartSummary. */}
            <div className={isEmpty ? "" : "grid gap-5 lg:grid-cols-[minmax(0,1fr)_380px]"}>
                {!isEmpty && (
                    <section aria-label="Cart items" className="border-2 border-ink bg-surface p-2 shadow-[3px_3px_0_#111827]">
                        <CartItems />
                    </section>
                )}
                <section
                    aria-label="Order summary"
                    className={isEmpty ? "" : "self-start border-2 border-ink bg-paper p-4 shadow-[3px_3px_0_#111827]"}
                >
                    <CartSummary hideWhenEmpty onReceiptClose={() => navigate("/orders")} />
                </section>
            </div>
        </PageShell>
    );
};

export default Cart;
