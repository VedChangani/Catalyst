import {useContext} from "react";
import {AppContext} from "../../context/AppContext.jsx";
import Button from "../../ui/Button.jsx";
import Badge from "../../ui/Badge.jsx";

const Item = ({itemName, itemPrice, itemImage, itemId, categoryName, active, availableQuantity, lowStockThreshold}) => {
    const {addToCart, cartItems} = useContext(AppContext);

    // Mirrors the backend's own rule (OrderServiceImpl.createOrder): only an explicit
    // active === true is sellable, so a legacy item with no value yet is treated the same way
    // the backend already treats it - as unavailable, not as "assume it's fine".
    const isInactive = active !== true;
    // availableQuantity is display/UX information only - the backend re-checks it authoritatively
    // at checkout. A missing value is treated as unknown/unavailable rather than assumed in stock.
    const isOutOfStock = availableQuantity == null || availableQuantity <= 0;
    const isLowStock = !isOutOfStock && lowStockThreshold != null && availableQuantity <= lowStockThreshold;

    const currentQuantityInCart = cartItems.find(cartItem => cartItem.itemId === itemId)?.quantity || 0;
    const isAtCartLimit = !isInactive && !isOutOfStock && currentQuantityInCart >= availableQuantity;

    const isDisabled = isInactive || isOutOfStock || isAtCartLimit;

    const buttonLabel = isInactive
        ? "Unavailable"
        : isOutOfStock
            ? "Out of stock"
            : isAtCartLimit
                ? "Max in cart"
                : "Add to cart";

    const handleAddToCart = () => {
        if (isDisabled) return;
        addToCart({
            name: itemName,
            price: itemPrice,
            quantity: 1,
            itemId: itemId
        });
    }

    return (
        <article className="flex h-full flex-col border-2 border-ink bg-paper shadow-[2px_2px_0_#111827] transition-all duration-150 hover:translate-x-[1px] hover:translate-y-[1px] hover:shadow-[1px_1px_0_#111827]">
            <div className="border-b-2 border-ink bg-surface p-3">
                <img src={itemImage} alt={itemName} className="h-36 w-full object-contain" />
            </div>
            <div className="flex flex-1 flex-col gap-3 p-4">
                <div className="flex items-start justify-between gap-2">
                    <h3 className="text-lg font-extrabold leading-tight">{itemName}</h3>
                    {categoryName && <Badge tone="muted">{categoryName}</Badge>}
                </div>
                <p className="text-2xl font-extrabold tracking-tight">₹{itemPrice}</p>
                <div className="flex flex-wrap gap-1.5">
                    {isInactive && <Badge tone="muted">Unavailable</Badge>}
                    {!isInactive && isOutOfStock && <Badge tone="danger">Out of stock</Badge>}
                    {!isInactive && !isOutOfStock && isLowStock && <Badge tone="warning">Low stock</Badge>}
                    {!isInactive && !isOutOfStock && (
                        <Badge tone="info">{availableQuantity} available</Badge>
                    )}
                </div>
                <Button
                    variant="primary"
                    className="mt-auto w-full"
                    onClick={handleAddToCart}
                    disabled={isDisabled}
                >
                    <i className="bi bi-cart-plus"></i>
                    {buttonLabel}
                </Button>
            </div>
        </article>
    )
}

export default Item;
