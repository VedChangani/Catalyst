import {useContext, useEffect, useState} from "react";
import {assets} from "../../assets/assets.js";
import {AppContext} from "../../context/AppContext.jsx";
import toast from "react-hot-toast";
import {addItem, updateItem} from "../../Service/ItemService.js";
import Input from "../../ui/Input.jsx";
import Select from "../../ui/Select.jsx";
import Button from "../../ui/Button.jsx";

const emptyData = {
    name: "",
    categoryId: "",
    price: "",
    description: "",
    sku: "",
    stockQuantity: "",
    lowStockThreshold: "",
    active: true,
};

// editingItem: null for "add new item"; an ItemResponse-shaped object to edit its metadata.
// onDone: called after a successful edit save (or cancel) so the parent can clear edit mode.
const ItemForm = ({editingItem = null, onDone}) => {
    const {categories, setItemsData, itemsData, setCategories} = useContext(AppContext);
    const [image, setImage] = useState(false);
    const [loading, setLoading] = useState(false);
    const [data, setData] = useState(emptyData);

    const isEditing = Boolean(editingItem);

    // Pre-fill the form when an item is selected for editing, and reset it back to blank
    // when editing ends - stockQuantity/reservedQuantity are deliberately left out of this
    // form's editable fields in edit mode; stock only changes via the dedicated adjustment
    // control in ItemList.
    useEffect(() => {
        if (editingItem) {
            setData({
                name: editingItem.name || "",
                categoryId: editingItem.categoryId || "",
                price: editingItem.price ?? "",
                description: editingItem.description || "",
                sku: editingItem.sku || "",
                stockQuantity: editingItem.stockQuantity ?? "",
                lowStockThreshold: editingItem.lowStockThreshold ?? "",
                active: editingItem.active ?? true,
            });
        } else {
            setData(emptyData);
        }
        setImage(false);
    }, [editingItem]);

    const onChangeHandler = (e) => {
        const {name, value, type, checked} = e.target;
        setData((data) => ({...data, [name]: type === "checkbox" ? checked : value}));
    }

    const resetForm = () => {
        setData(emptyData);
        setImage(false);
    }

    const onSubmitHandler = async (e) => {
        e.preventDefault();
        if (loading) return;

        if (isEditing) {
            setLoading(true);
            try {
                const payload = {
                    name: data.name,
                    price: data.price === "" ? undefined : Number(data.price),
                    categoryId: data.categoryId,
                    description: data.description,
                    sku: data.sku || undefined,
                    lowStockThreshold: data.lowStockThreshold === "" ? undefined : Number(data.lowStockThreshold),
                    active: data.active,
                };
                const response = await updateItem(editingItem.itemId, payload);
                if (response.status === 200) {
                    setItemsData(itemsData.map((item) => item.itemId === response.data.itemId ? response.data : item));
                    toast.success("Item updated");
                    onDone?.();
                } else {
                    toast.error("Unable to update item");
                }
            } catch (error) {
                console.error(error);
                toast.error(error.friendlyMessage || "Unable to update item");
            } finally {
                setLoading(false);
            }
            return;
        }

        if (!image) {
            toast.error("Select image");
            return;
        }
        if (data.stockQuantity === "" || Number(data.stockQuantity) < 0) {
            toast.error("Stock quantity is required and must not be negative");
            return;
        }
        setLoading(true);
        const payload = {
            name: data.name,
            categoryId: data.categoryId,
            price: data.price,
            description: data.description,
            sku: data.sku || undefined,
            stockQuantity: Number(data.stockQuantity),
            lowStockThreshold: data.lowStockThreshold === "" ? undefined : Number(data.lowStockThreshold),
            active: data.active,
        };
        const formData = new FormData();
        formData.append("item", JSON.stringify(payload));
        formData.append("file", image);
        try {
            const response = await addItem(formData);
            if (response.status === 201) {
                setItemsData([...itemsData, response.data]);
                setCategories((prevCategories) =>
                prevCategories.map((category) => category.categoryId === data.categoryId ? {...category, items: category.items + 1} : category));
                toast.success("Item added");
                resetForm();
            } else {
                toast.error("Unable to add item");
            }
        } catch (error) {
            console.error(error);
            toast.error(error.friendlyMessage || "Unable to add item");
        } finally {
            setLoading(false);
        }
    }

    return (
        <form onSubmit={onSubmitHandler} className="space-y-4">
            {!isEditing && (
                <div>
                    <label htmlFor="image" className="inline-flex cursor-pointer items-center gap-3 border-2 border-ink bg-paper px-3 py-2 font-bold">
                        <img src={image ? URL.createObjectURL(image) : assets.upload} alt="" width={48} className="h-12 w-12 object-contain" />
                        <span>{image ? "Change image" : "Upload image"}</span>
                    </label>
                    <input type="file" name="image" id="image" className="sr-only" onChange={(e) => setImage(e.target.files[0])} />
                </div>
            )}
            <Input
                label="Name"
                type="text"
                name="name"
                id="name"
                placeholder="Item Name"
                onChange={onChangeHandler}
                value={data.name}
                required
            />
            <Select name="categoryId" id="category" label="Category" onChange={onChangeHandler} value={data.categoryId} required>
                <option value="">--SELECT CATEGORY--</option>
                {categories.map((category, index) => (
                    <option key={index} value={category.categoryId}>{category.name}</option>
                ))}
            </Select>
            <Input
                label="Price"
                type="number"
                name="price"
                id="price"
                placeholder="₹200.00"
                onChange={onChangeHandler}
                value={data.price}
                required
            />
            <Input
                label="SKU (optional)"
                type="text"
                name="sku"
                id="sku"
                placeholder="e.g. BEV-COLA-500"
                onChange={onChangeHandler}
                value={data.sku}
            />
            {isEditing ? (
                <p className="border-2 border-ink bg-paper px-3 py-2 text-sm text-muted">
                    Current stock: <span className="font-bold text-ink">{editingItem.stockQuantity ?? "—"}</span>.
                    Use the stock control in the product list to adjust it.
                </p>
            ) : (
                <Input
                    label="Stock quantity"
                    type="number"
                    name="stockQuantity"
                    id="stockQuantity"
                    placeholder="0"
                    min="0"
                    onChange={onChangeHandler}
                    value={data.stockQuantity}
                    required
                />
            )}
            <Input
                label="Low-stock threshold (optional)"
                type="number"
                name="lowStockThreshold"
                id="lowStockThreshold"
                placeholder="5"
                min="0"
                onChange={onChangeHandler}
                value={data.lowStockThreshold}
            />
            <label htmlFor="active" className="flex items-center gap-2 border-2 border-ink bg-paper px-3 py-2 font-bold">
                <input
                    type="checkbox"
                    name="active"
                    id="active"
                    checked={data.active}
                    onChange={onChangeHandler}
                />
                Active (available for sale)
            </label>
            <div className="flex flex-col gap-1.5">
                <label htmlFor="description" className="text-sm font-bold">Description</label>
                <textarea
                    rows="5"
                    name="description"
                    id="description"
                    className="nb-input"
                    placeholder="Write content here.."
                    onChange={onChangeHandler}
                    value={data.description}
                ></textarea>
            </div>
            <div className="flex gap-3">
                <Button type="submit" className="w-full" disabled={loading}>
                    {loading ? "Loading..." : isEditing ? "Update" : "Save"}
                </Button>
                {isEditing && (
                    <Button type="button" variant="secondary" disabled={loading} onClick={() => onDone?.()}>
                        Cancel
                    </Button>
                )}
            </div>
        </form>
    )
}

export default ItemForm;
