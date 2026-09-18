import {useContext, useState} from "react";
import ItemForm from "../../components/ItemForm/ItemForm.jsx";
import ItemList from "../../components/ItemList/ItemList.jsx";
import PageHeader from "../../ui/PageHeader.jsx";
import LoadingState from "../../ui/LoadingState.jsx";
import {AppContext} from "../../context/AppContext.jsx";

const ManageItems = () => {
    const {isCatalogLoading} = useContext(AppContext);
    const [editingItem, setEditingItem] = useState(null);

    return (
        <div className="mx-auto w-full max-w-[1440px] px-4 py-6 sm:px-6 lg:px-8">
            <PageHeader
                kicker="Catalog"
                title="Manage Items"
                description="Add products, set prices, and keep the floor stock list current."
            />
            <div className="grid gap-5 lg:grid-cols-[minmax(280px,0.42fr)_minmax(0,1fr)]">
                <div className="border-2 border-ink bg-surface p-4 shadow-[3px_3px_0_#111827] sm:p-5">
                    <h2 className="mb-4 text-lg font-extrabold">{editingItem ? "Edit item" : "Add item"}</h2>
                    <ItemForm editingItem={editingItem} onDone={() => setEditingItem(null)} />
                </div>
                <div className="border-2 border-ink bg-surface p-4 shadow-[3px_3px_0_#111827] sm:p-5">
                    <h2 className="mb-4 text-lg font-extrabold">Product list</h2>
                    {isCatalogLoading ? <LoadingState label="Loading products..." /> : <ItemList onEdit={setEditingItem} />}
                </div>
            </div>
        </div>
    )
}

export default ManageItems;
