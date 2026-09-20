import {useContext, useState} from "react";
import {AppContext} from "../../context/AppContext.jsx";
import {adjustStock, deleteItem} from "../../Service/ItemService.js";
import toast from "react-hot-toast";
import Button from "../../ui/Button.jsx";
import Badge from "../../ui/Badge.jsx";
import EmptyState from "../../ui/EmptyState.jsx";

const ItemList = ({onEdit}) => {
    const {itemsData, setItemsData} = useContext(AppContext);
    const [searchTerm, setSearchTerm] = useState("");
    const [deletingId, setDeletingId] = useState(null);
    const [adjustingId, setAdjustingId] = useState(null);
    const [deltaDrafts, setDeltaDrafts] = useState({});

    const filteredItems = itemsData.filter((item) => {
        return item.name.toLowerCase().includes(searchTerm.toLowerCase());
    })

    const removeItem = async (itemId) => {
        if (deletingId) return;
        setDeletingId(itemId);
        try {
            const response = await deleteItem(itemId);
            if (response.status === 204) {
                const updatedItems = itemsData.filter(item => item.itemId !== itemId);
                setItemsData(updatedItems);
                toast.success("Item deleted");
            } else {
                toast.error("Unable to delete item");
            }
        }catch(err) {
            console.error(err);
            toast.error(err.friendlyMessage || "Unable to delete item");
        } finally {
            setDeletingId(null);
        }
    }

    const applyStockAdjustment = async (itemId) => {
        if (adjustingId) return;
        const raw = deltaDrafts[itemId];
        const delta = Number(raw);
        if (raw === undefined || raw === "" || Number.isNaN(delta) || delta === 0) {
            toast.error("Enter a non-zero amount to adjust stock by");
            return;
        }
        setAdjustingId(itemId);
        try {
            const response = await adjustStock(itemId, delta);
            if (response.status === 200) {
                setItemsData(itemsData.map((item) => item.itemId === response.data.itemId ? response.data : item));
                setDeltaDrafts((drafts) => ({...drafts, [itemId]: ""}));
                toast.success("Stock updated");
            } else {
                toast.error("Unable to adjust stock");
            }
        } catch (err) {
            console.error(err);
            toast.error(err.friendlyMessage || "Unable to adjust stock");
        } finally {
            setAdjustingId(null);
        }
    }

    return (
        <div className="space-y-4">
            <div className="flex border-2 border-ink bg-surface shadow-[2px_2px_0_#111827]">
                <input
                    type="text"
                    name="keyword"
                    id="keyword"
                    placeholder="Search by keyword"
                    className="w-full bg-transparent px-3 py-2.5 outline-none"
                    onChange={(e) => setSearchTerm(e.target.value)}
                    value={searchTerm}
                />
                <span className="flex items-center border-l-2 border-ink bg-primary/10 px-3">
                    <i className="bi bi-search"></i>
                </span>
            </div>
            {filteredItems.length === 0 ? (
                <EmptyState title="No items found" description="Add a product or try a different search." />
            ) : (
                <div className="space-y-3">
                    {filteredItems.map((item, index) => {
                        const availableQuantity = item.availableQuantity ?? item.stockQuantity;
                        const isOutOfStock = availableQuantity != null && availableQuantity <= 0;
                        const isLowStock = !isOutOfStock && availableQuantity != null && item.lowStockThreshold != null
                            && availableQuantity <= item.lowStockThreshold;

                        return (
                        <div key={item.itemId || index} className="flex flex-wrap items-center gap-4 border-2 border-ink bg-paper p-3 shadow-[2px_2px_0_#111827]">
                            <img src={item.imgUrl} alt={item.name} className="h-16 w-16 border-2 border-ink object-cover" />
                            <div className="min-w-0 flex-1">
                                <h6 className="font-extrabold">{item.name}</h6>
                                <p className="text-sm text-muted">
                                    Category: {item.categoryName}{item.sku ? ` · SKU: ${item.sku}` : ""}
                                </p>
                                <div className="mt-1 flex flex-wrap gap-1.5">
                                    <Badge tone="info">₹{item.price}</Badge>
                                    <Badge tone={item.active === false ? "muted" : "default"}>
                                        {item.active === false ? "Inactive" : "Active"}
                                    </Badge>
                                    {item.stockQuantity != null && (
                                        <Badge tone="default">Stock: {item.stockQuantity}</Badge>
                                    )}
                                    {availableQuantity != null && (
                                        <Badge tone="default">Available: {availableQuantity}</Badge>
                                    )}
                                    {isOutOfStock && <Badge tone="danger">Out of stock</Badge>}
                                    {isLowStock && <Badge tone="warning">Low stock</Badge>}
                                </div>
                            </div>
                            <div className="flex items-center gap-1.5">
                                <input
                                    type="number"
                                    aria-label={`Stock adjustment for ${item.name}`}
                                    placeholder="±qty"
                                    className="w-20 border-2 border-ink bg-transparent px-2 py-1.5 text-sm outline-none"
                                    value={deltaDrafts[item.itemId] ?? ""}
                                    onChange={(e) => setDeltaDrafts((drafts) => ({...drafts, [item.itemId]: e.target.value}))}
                                />
                                <Button
                                    variant="secondary"
                                    size="sm"
                                    onClick={() => applyStockAdjustment(item.itemId)}
                                    disabled={adjustingId === item.itemId}
                                    aria-label="Apply stock adjustment"
                                >
                                    {adjustingId === item.itemId ? "…" : "Apply"}
                                </Button>
                            </div>
                            <Button
                                variant="secondary"
                                size="sm"
                                onClick={() => onEdit?.(item)}
                                aria-label="Edit item"
                            >
                                <i className="bi bi-pencil"></i>
                            </Button>
                            <Button
                                variant="danger"
                                size="sm"
                                onClick={() => removeItem(item.itemId)}
                                disabled={deletingId === item.itemId}
                                aria-label="Delete item"
                            >
                                <i className="bi bi-trash"></i>
                            </Button>
                        </div>
                        );
                    })}
                </div>
            )}
        </div>
    )
}

export default ItemList;
