import {useContext, useState} from "react";
import {AppContext} from "../../context/AppContext.jsx";
import DisplayCategory from "../../components/DisplayCategory/DisplayCategory.jsx";
import DisplayItems from "../../components/DisplayItems/DisplayItems.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import PageShell from "../../ui/PageShell.jsx";
import Button from "../../ui/Button.jsx";
import {useNavigate} from "react-router-dom";

const Explore = () => {
    const {categories, isCatalogLoading, cartItems, cartCount} = useContext(AppContext);
    const navigate = useNavigate();
    // display-only running total; the backend prices the order at checkout
    const subtotal = cartItems.reduce((total, item) => total + item.price * item.quantity, 0);
    const [selectedCategory, setSelectedCategory] = useState("");

    if (isCatalogLoading) {
        return (
            <PageShell wide>
                <LoadingState label="Loading store floor..." />
            </PageShell>
        );
    }

    return (
        <div className="mx-auto grid min-h-[calc(100vh-5.5rem)] w-full max-w-[1440px] gap-5 px-4 py-5 sm:px-6 lg:grid-cols-[minmax(0,1fr)_380px] lg:px-8">
            <section className="flex min-h-0 flex-col gap-5">
                <div className="border-2 border-ink bg-surface p-4 shadow-[3px_3px_0_#111827] sm:p-5">
                    <h1 className="text-3xl font-extrabold tracking-tight">Select Category</h1>
                    <div className="mt-4 overflow-x-auto pb-1">
                        <DisplayCategory
                            selectedCategory={selectedCategory}
                            setSelectedCategory={setSelectedCategory}
                            categories={categories} />
                    </div>
                </div>
                <div className="min-h-0 flex-1 overflow-y-auto border-2 border-ink bg-surface p-4 shadow-[3px_3px_0_#111827] sm:p-5">
                    <DisplayItems selectedCategory={selectedCategory} />
                </div>
            </section>
            <aside className="flex flex-col self-start border-2 border-ink bg-surface shadow-[3px_3px_0_#111827]">
                <div className="border-b-2 border-ink bg-primary/10 px-4 py-3">
                    <h2 className="text-lg font-extrabold uppercase tracking-wide">Your cart</h2>
                    <p className="text-xs font-bold text-muted">Review items and check out on the cart page</p>
                </div>
                <div className="space-y-3 px-4 py-4">
                    <div className="flex justify-between text-sm font-bold">
                        <span>Items</span>
                        <span>{cartCount}</span>
                    </div>
                    <div className="flex justify-between text-sm font-bold">
                        <span>Subtotal</span>
                        <span>₹{subtotal.toFixed(2)}</span>
                    </div>
                    <Button variant="primary" className="w-full" onClick={() => navigate("/cart")}>
                        <i className="bi bi-cart3"></i>
                        {cartCount > 0 ? "View cart & checkout" : "Open cart"}
                    </Button>
                </div>
            </aside>
        </div>
    )
}

export default Explore;
