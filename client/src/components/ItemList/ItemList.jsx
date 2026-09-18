import {useContext, useState} from "react";
import {AppContext} from "../../context/AppContext.jsx";
import {deleteItem} from "../../Service/ItemService.js";
import toast from "react-hot-toast";
import Button from "../../ui/Button.jsx";
import Badge from "../../ui/Badge.jsx";
import EmptyState from "../../ui/EmptyState.jsx";

const ItemList = () => {
    const {itemsData, setItemsData} = useContext(AppContext);
    const [searchTerm, setSearchTerm] = useState("");

    const filteredItems = itemsData.filter((item) => {
        return item.name.toLowerCase().includes(searchTerm.toLowerCase());
    })

    const removeItem = async (itemId) => {
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
            toast.error("Unable to delete item");
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
                    {filteredItems.map((item, index) => (
                        <div key={item.itemId || index} className="flex items-center gap-4 border-2 border-ink bg-paper p-3 shadow-[2px_2px_0_#111827]">
                            <img src={item.imgUrl} alt={item.name} className="h-16 w-16 border-2 border-ink object-cover" />
                            <div className="min-w-0 flex-1">
                                <h6 className="font-extrabold">{item.name}</h6>
                                <p className="text-sm text-muted">
                                    Category: {item.categoryName}
                                </p>
                                <Badge tone="info" className="mt-1">
                                    ₹{item.price}
                                </Badge>
                            </div>
                            <Button variant="danger" size="sm" onClick={() => removeItem(item.itemId)} aria-label="Delete item">
                                <i className="bi bi-trash"></i>
                            </Button>
                        </div>
                    ))}
                </div>
            )}
        </div>
    )
}

export default ItemList;
