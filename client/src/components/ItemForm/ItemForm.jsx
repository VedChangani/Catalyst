import {useContext, useState} from "react";
import {assets} from "../../assets/assets.js";
import {AppContext} from "../../context/AppContext.jsx";
import toast from "react-hot-toast";
import {addItem} from "../../Service/ItemService.js";
import Input from "../../ui/Input.jsx";
import Select from "../../ui/Select.jsx";
import Button from "../../ui/Button.jsx";

const ItemForm = () => {
    const {categories, setItemsData, itemsData, setCategories} = useContext(AppContext);
    const [image, setImage] = useState(false);
    const [loading, setLoading] = useState(false);
    const [data, setData] = useState({
        name: "",
        categoryId: "",
        price: "",
        description: "",
    });

    const onChangeHandler = (e) => {
        const value = e.target.value;
        const name = e.target.name;
        setData((data) => ({...data, [name]: value}));
    }

    const onSubmitHandler = async (e) => {
        e.preventDefault();
        setLoading(true);
        const formData = new FormData();
        formData.append("item", JSON.stringify(data));
        formData.append("file", image);
        try {
            if (!image) {
                toast.error("Select image");
                return;
            }

            const response = await addItem(formData);
            if (response.status === 201) {
                setItemsData([...itemsData, response.data]);
                setCategories((prevCategories) =>
                prevCategories.map((category) => category.categoryId === data.categoryId ? {...category, items: category.items + 1} : category));
                toast.success("Item added");
                setData({
                    name: "",
                    description: "",
                    price: "",
                    categoryId: "",
                })
                setImage(false);
            } else {
                toast.error("Unable to add item");
            }
        } catch (error) {
            console.error(error);
            toast.error("Unable to add item");
        } finally {
            setLoading(false);
        }
    }

    return (
        <form onSubmit={onSubmitHandler} className="space-y-4">
            <div>
                <label htmlFor="image" className="inline-flex cursor-pointer items-center gap-3 border-2 border-ink bg-paper px-3 py-2 font-bold">
                    <img src={image ? URL.createObjectURL(image) : assets.upload} alt="" width={48} className="h-12 w-12 object-contain" />
                    <span>{image ? "Change image" : "Upload image"}</span>
                </label>
                <input type="file" name="image" id="image" className="sr-only" onChange={(e) => setImage(e.target.files[0])} />
            </div>
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
            <Button type="submit" className="w-full" disabled={loading}>{loading ? "Loading..." : "Save"}</Button>
        </form>
    )
}

export default ItemForm;
