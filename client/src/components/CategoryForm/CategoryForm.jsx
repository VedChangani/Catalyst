import {useContext, useState} from "react";
import {assets} from "../../assets/assets.js";
import toast from "react-hot-toast";
import {addCategory} from "../../Service/CategoryService.js";
import {AppContext} from "../../context/AppContext.jsx";
import Input from "../../ui/Input.jsx";
import Button from "../../ui/Button.jsx";

const CategoryForm = () => {
    const {setCategories, categories} = useContext(AppContext);
    const [loading, setLoading] = useState(false);
    const [image, setImage] = useState(false);

    const [data, setData] = useState({
        name: "",
        description: "",
        bgColor: "#2c2c2c",
    });

    const onChangeHandler = (e) => {
        const value = e.target.value;
        const name = e.target.name;
        setData((data) => ({...data, [name]: value}));
    }

    const onSubmitHandler = async (e) => {
        e.preventDefault();
        if (loading) return;

        if (!image) {
            toast.error("Select image for category");
            return;
        }
        setLoading(true);
        const formData = new FormData();
        formData.append("category", JSON.stringify(data));
        formData.append("file", image);
        try {
            const response = await addCategory(formData);
            if (response.status === 201) {
                setCategories([...categories, response.data]);
                toast.success("Category added");
                setData({
                    name: "",
                    description: "",
                    bgColor: "#2c2c2c",
                });
                setImage(false);
            }
        }catch(err) {
            console.error(err);
            toast.error(err.friendlyMessage || "Error adding category");
        }finally {
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
                placeholder="Category Name"
                onChange={onChangeHandler}
                value={data.name}
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
            <div className="flex flex-col gap-1.5">
                <label htmlFor="bgcolor" className="text-sm font-bold">Background color</label>
                <input
                    type="color"
                    name="bgColor"
                    id="bgcolor"
                    className="h-12 w-24 cursor-pointer border-2 border-ink bg-surface p-1"
                    onChange={onChangeHandler}
                    value={data.bgColor}
                    placeholder="#ffffff"
                />
            </div>
            <Button type="submit" className="w-full" disabled={loading}>{loading ? "Loading..." : "Submit"}</Button>
        </form>
    )
}

export default CategoryForm;
