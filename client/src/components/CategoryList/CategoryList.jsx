import {useContext, useState} from "react";
import {AppContext} from "../../context/AppContext.jsx";
import {deleteCategory} from "../../Service/CategoryService.js";
import toast from "react-hot-toast";
import Button from "../../ui/Button.jsx";
import EmptyState from "../../ui/EmptyState.jsx";

const CategoryList = () => {
    const {categories, setCategories} = useContext(AppContext);
    const [searchTerm, setSearchTerm] = useState('');
    const [deletingId, setDeletingId] = useState(null);

    const filteredCategories = categories.filter(category =>
        category.name.toLowerCase().includes(searchTerm.toLowerCase())
    );

    const deleteByCategoryId = async (categoryId) => {
        if (deletingId) return;
        setDeletingId(categoryId);
        try {
            const response = await deleteCategory(categoryId);
            if (response.status === 204) {
                const updatedCategories = categories.filter(category => category.categoryId !== categoryId);
                setCategories(updatedCategories);
                toast.success("Category deleted");
            } else {
                toast.error("Unable to delete category");
            }
        } catch (error) {
            console.error(error);
            // A category that still has items reports a specific 409 conflict message from the
            // backend (e.g. "Cannot delete category 'X' because 3 item(s) still reference it") -
            // show it instead of a generic failure message.
            toast.error(error.friendlyMessage || "Unable to delete category");
        } finally {
            setDeletingId(null);
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
            {filteredCategories.length === 0 ? (
                <EmptyState title="No categories found" description="Create a category or adjust your search." />
            ) : (
                <div className="grid gap-3 sm:grid-cols-2">
                    {filteredCategories.map((category, index) => (
                        <div key={category.categoryId || index} className="flex items-center gap-3 border-2 border-ink bg-surface p-3 shadow-[2px_2px_0_#111827]">
                            <span className="h-16 w-2 shrink-0 border-2 border-ink" style={{backgroundColor: category.bgColor}} />
                            <img src={category.imgUrl} alt={category.name} className="h-14 w-14 border-2 border-ink object-cover" />
                            <div className="min-w-0 flex-1">
                                <h5 className="font-extrabold">{category.name}</h5>
                                {category.description && (
                                    <p className="line-clamp-2 text-sm text-muted">{category.description}</p>
                                )}
                                <p className="text-sm font-bold">{category.items} Items</p>
                            </div>
                            <Button
                                variant="danger"
                                size="sm"
                                onClick={() => deleteByCategoryId(category.categoryId)}
                                disabled={deletingId === category.categoryId}
                                aria-label="Delete category"
                            >
                                <i className="bi bi-trash"></i>
                            </Button>
                        </div>
                    ))}
                </div>
            )}
        </div>
    )
}

export default CategoryList;
