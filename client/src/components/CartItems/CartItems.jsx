import {useContext} from "react";
import {AppContext} from "../../context/AppContext.jsx";
import Button from "../../ui/Button.jsx";
import EmptyState from "../../ui/EmptyState.jsx";

const CartItems = () => {
    const {cartItems, removeFromCart, updateQuantity} = useContext(AppContext);
    return (
        <div className="h-full overflow-y-auto p-2">
            {cartItems.length === 0 ? (
                <EmptyState
                    title="Your cart is empty."
                    description="Add products from the catalog to start a bill."
                />
            ) : (
                <div className="space-y-3">
                    {cartItems.map((item, index) => (
                        <div key={index} className="border-2 border-ink bg-paper p-3">
                            <div className="mb-2 flex items-start justify-between gap-2">
                                <h6 className="font-extrabold">{item.name}</h6>
                                <p className="text-lg font-extrabold">
                                    ₹{(item.price * item.quantity).toFixed(2)}
                                </p>
                            </div>
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
                    ))}
                </div>
            )}
        </div>
    )
}

export default CartItems;
