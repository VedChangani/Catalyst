import {useContext} from "react";
import {AppContext} from "../../context/AppContext.jsx";
import Button from "../../ui/Button.jsx";
import EmptyState from "../../ui/EmptyState.jsx";

const CartItems = () => {
    const {cartItems, itemsData, removeFromCart, updateQuantity} = useContext(AppContext);

    // Cross-checks each cart line against the latest fetched catalog data (display/UX only - the
    // backend re-validates authoritatively at checkout). A line with no matching catalog entry
    // means the item was removed/deactivated since it was added to the cart.
    const availabilityFor = (itemId) => {
        const catalogItem = itemsData.find(item => item.itemId === itemId);
        if (!catalogItem) {
            return {catalogItem: null, isUnavailable: true, availableQuantity: null};
        }
        const isInactive = catalogItem.active !== true;
        const availableQuantity = catalogItem.availableQuantity;
        return {
            catalogItem,
            isUnavailable: isInactive || availableQuantity == null || availableQuantity <= 0,
            availableQuantity
        };
    }

    return (
        <div className="h-full overflow-y-auto p-2">
            {cartItems.length === 0 ? (
                <EmptyState
                    title="Your cart is empty."
                    description="Add products from the catalog to start a bill."
                />
            ) : (
                <div className="space-y-3">
                    {cartItems.map((item, index) => {
                        const {isUnavailable, availableQuantity} = availabilityFor(item.itemId);
                        const exceedsAvailable = !isUnavailable && availableQuantity != null && item.quantity > availableQuantity;
                        const atLimit = !isUnavailable && availableQuantity != null && item.quantity >= availableQuantity;

                        return (
                        <div key={index} className="border-2 border-ink bg-paper p-3">
                            <div className="mb-2 flex items-start justify-between gap-2">
                                <h6 className="font-extrabold">{item.name}</h6>
                                <p className="text-lg font-extrabold">
                                    ₹{(item.price * item.quantity).toFixed(2)}
                                </p>
                            </div>
                            {(isUnavailable || exceedsAvailable) && (
                                <p className="mb-2 border-2 border-ink bg-danger/15 px-2 py-1 text-sm font-semibold text-danger">
                                    {isUnavailable
                                        ? "No longer available - remove this item to continue."
                                        : `Only ${availableQuantity} left - reduce the quantity to continue.`}
                                </p>
                            )}
                            <div className="flex items-center justify-between gap-2">
                                <div className="flex items-center gap-2">
                                    <Button
                                        size="sm"
                                        variant="danger"
                                        onClick={() => updateQuantity(item.itemId, item.quantity - 1)}
                                        disabled={item.quantity === 1}
                                        aria-label="Decrease quantity"
                                    >
                                        <i className="bi bi-dash"></i>
                                    </Button>
                                    <span className="min-w-6 text-center font-extrabold">{item.quantity}</span>
                                    <Button
                                        size="sm"
                                        variant="primary"
                                        onClick={() => updateQuantity(item.itemId, item.quantity + 1)}
                                        disabled={isUnavailable || atLimit}
                                        aria-label="Increase quantity"
                                    >
                                        <i className="bi bi-plus"></i>
                                    </Button>
                                </div>
                                <Button
                                    size="sm"
                                    variant="danger"
                                    onClick={() => removeFromCart(item.itemId)}
                                    aria-label="Remove item"
                                >
                                    <i className="bi bi-trash"></i>
                                </Button>
                            </div>
                        </div>
                        );
                    })}
                </div>
            )}
        </div>
    )
}

export default CartItems;
