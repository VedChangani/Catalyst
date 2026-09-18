import {useContext} from "react";
import {AppContext} from "../../context/AppContext.jsx";
import Button from "../../ui/Button.jsx";
import Badge from "../../ui/Badge.jsx";

const Item = ({itemName, itemPrice, itemImage, itemId, categoryName}) => {
    const {addToCart} = useContext(AppContext);
    const handleAddToCart = () => {
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
                <Button variant="primary" className="mt-auto w-full" onClick={handleAddToCart}>
                    <i className="bi bi-cart-plus"></i>
                    Add to cart
                </Button>
            </div>
        </article>
    )
}

export default Item;
