import {useContext, useState} from "react";
import {AppContext} from "../../context/AppContext.jsx";
import DisplayCategory from "../../components/DisplayCategory/DisplayCategory.jsx";
import DisplayItems from "../../components/DisplayItems/DisplayItems.jsx";
import CustomerForm from "../../components/CustomerForm/CustomerForm.jsx";
import CartItems from "../../components/CartItems/CartItems.jsx";
import CartSummary from "../../components/CartSummary/CartSummary.jsx";
import PosCustomerSelector from "../../components/PosCustomerSelector/PosCustomerSelector.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import PageShell from "../../ui/PageShell.jsx";

// Cashier (and admin) in-store billing: the same catalog, cart and payment components as the
// storefront, plus an optional, explicit registered-customer selection. Orders are sent to
// POST /pos/orders; the backend decides the sales channel and the creating staff member.
const PosBilling = () => {
    const {categories, isCatalogLoading} = useContext(AppContext);
    const [selectedCategory, setSelectedCategory] = useState("");
    const [customerName, setCustomerName] = useState("");
    const [mobileNumber, setMobileNumber] = useState("");
    const [posCustomer, setPosCustomer] = useState(null);

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
                    <p className="text-xs font-extrabold uppercase tracking-[0.18em] text-muted">Point of sale</p>
                    <h1 className="mt-1 text-3xl font-extrabold tracking-tight">Scan → Add to cart → Take payment</h1>
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
            <aside className="flex min-h-[32rem] flex-col border-2 border-ink bg-surface shadow-[3px_3px_0_#111827] lg:min-h-0">
                <div className="border-b-2 border-ink bg-primary/10 px-4 py-3">
                    <h2 className="text-lg font-extrabold uppercase tracking-wide">Cart / Billing</h2>
                    <p className="text-xs font-bold text-muted">Customer · Items · Total · Payment</p>
                </div>
                <div className="space-y-4 border-b-2 border-ink px-4 py-3">
                    <PosCustomerSelector
                        selectedCustomer={posCustomer}
                        onSelect={setPosCustomer}
                        onClear={() => setPosCustomer(null)}
                    />
                    <CustomerForm
                        optional
                        customerName={customerName}
                        mobileNumber={mobileNumber}
                        setMobileNumber={setMobileNumber}
                        setCustomerName={setCustomerName}
                    />
                </div>
                <div className="min-h-0 flex-1 overflow-y-auto px-2 py-2">
                    <CartItems />
                </div>
                <div className="border-t-2 border-ink bg-paper px-4 py-4">
                    <CartSummary
                        posMode
                        posCustomer={posCustomer}
                        onSaleFinished={() => setPosCustomer(null)}
                        customerName={customerName}
                        mobileNumber={mobileNumber}
                        setMobileNumber={setMobileNumber}
                        setCustomerName={setCustomerName}
                    />
                </div>
            </aside>
        </div>
    )
}

export default PosBilling;
